"""ICCOA CarLink head unit emulator (the CAR side) for testing Headswap without a car.

    adb reverse tcp:57209 tcp:57209 ; adb reverse tcp:15550 tcp:15550
    adb forward tcp:57219 tcp:57219 (and 57229, 57239, 7236, 4321)
    python iccoa_hu.py

No BLE and no Wi-Fi: the phone app enters with its debug command and host=127.0.0.1. Ports and directions:

    phone -> car   57209 auth      (we listen)   cat 3 m1 sign-in -> m2 response -> m3 Confirm
    car -> phone   57219 control   (we connect)  heartbeat m1 every 2 s, ack for type 3, m24 -> m25 config, m2 get_port
    car -> phone   57229 audio, 57239 sensor     (we connect, frames are counted and logged)
    car -> phone   7236 RTSP       (we connect)  WFD sink: M1..M7, M16 keep-alive, wfd_idr_request
    car -> phone   4321 UIBC       (we connect after PLAY) JSON touches and keys, AES-GCM envelopes (--uibc-plain: plain)
    phone -> car   15550 RTP/TCP   (we listen)   RFC 4571 -> RTP PT 33 -> MPEG-TS -> H.264 (PID 256), AAC (PID 257)

Sign-in with PIN (--pin, default 123456) stores the client in the trust file, the next sign-in of the same clientId goes
without PIN. --force-result N answers every sign-in with result N (1 need PIN, 2 confirm on car, 3 internal, 4 work mode).
After the control lane closes the emulator waits for the next sign-in.

By default a live window opens (carlife_view.py: tkinter + ffmpeg from PATH): mouse = UIBC touches in pixels of the car
screen (--screen), right click / Esc = Back (keycode 4), middle click / Home = Home (3), the button row = steering wheel
keys over control m12. --no-view: no window, input by stdin lines (touch ACTION X Y | key CODE | wheel CODE | idr). Video goes to out/iccoa_video.h264, the log to out/iccoa_hu.log,
--dump-audio writes ADTS AAC to out/iccoa_audio.aac. The PIN and keys are never logged.
"""
from __future__ import annotations

import argparse
import itertools
import re
import socket
import sys
import threading
import time
from pathlib import Path

import iccoa_wire as W
from carlife_view import parse_size

HERE = Path(__file__).resolve().parent
CONNECT_TRIES = 10                  # control и RTSP: телефон может открыть порт чуть позже Confirm
# имена методов cat 1 для лога (docs/plans/iccoa-carlink.md)
METHODS = {1: "heartbeat", 2: "get_port", 3: "get_port_rsp", 6: "mic", 7: "mirror_state", 8: "audio_player_state",
           9: "phone_state", 10: "music_info", 11: "navigation_info", 23: "disconnect", 24: "config_req", 27: "app_list",
           29: "app_detail", 31: "app_state"}
# кнопки руля под окном: подпись -> Android keycode (m12); 8107 - голос на руле
WHEEL_KEYS = [("Голос", 8107), ("|<<", 88), (">>|", 87), ("Play/Pause", 85), ("Play", 126), ("Pause", 127), ("Трубка", 79)]


class IccoaHu:
    def __init__(self, o: argparse.Namespace):
        self.o = o
        self.done = threading.Event()
        self.loglock = threading.Lock()
        self.wlock = threading.Lock()                   # одна запись в любой сокет за раз
        o.out.mkdir(parents=True, exist_ok=True)
        self.logf = open(o.log or o.out / "iccoa_hu.log", "w", encoding="utf-8")
        self.videof = open(o.out / "iccoa_video.h264", "wb")
        self.audiof = open(o.out / "iccoa_audio.aac", "wb") if o.dump_audio else None
        self.auth = W.CarAuth(o.pin, W.Trust(o.trust or o.out / "iccoa_trust.json"), o.force_result)
        self.auth_srv: socket.socket | None = None
        self.rtp_srv: socket.socket | None = None
        self.auth_port, self.rtp_port = o.auth_port, o.rtp_port
        self.video_sink = None                          # окно: on_video(bytes), зовётся с нити RTP
        self.seq = itertools.count(1)
        # текущая сессия
        self.over = threading.Event()
        self.socks: list[socket.socket] = []
        self.cipher: W.Cipher | None = None
        self.control: socket.socket | None = None
        self.rtsp: socket.socket | None = None
        self.uibc: socket.socket | None = None
        self.sink: W.RtspSink | None = None
        # счётчики (итог и тесты)
        self.sessions = 0
        self.rejects = 0
        self.video_units = 0
        self.audio_units = 0
        self.playing = threading.Event()

    def log(self, msg: str):
        line = f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}"
        with self.loglock:
            if not self.o.quiet:
                print(line, flush=True)
            if not self.logf.closed:
                self.logf.write(line + "\n")
                self.logf.flush()

    def listen(self):
        """Открыть свои порты заранее (0 = свободный; настоящие в auth_port / rtp_port)."""
        if self.auth_srv:
            return
        self.auth_srv = socket.create_server((self.o.bind, self.o.auth_port))
        self.auth_srv.settimeout(0.5)
        self.rtp_srv = socket.create_server((self.o.bind, self.o.rtp_port))
        self.auth_port, self.rtp_port = self.auth_srv.getsockname()[1], self.rtp_srv.getsockname()[1]

    def finish(self, reason: str):
        if not self.done.is_set():
            self.done.set()
            self.log(f"finish: {reason}")
        self.over.set()

    # ---- жизненный цикл ----

    def run(self):
        self.listen()
        threading.Thread(target=self._rtp_loop, daemon=True, name="iccoa-rtp").start()
        o = self.o
        self.log(f"ICCOA HU: auth :{self.auth_port}, RTP :{self.rtp_port}, phone {o.phone} (control {o.control_port}, audio "
                 f"{o.audio_port}, sensor {o.sensor_port}, RTSP {o.rtsp_port}, UIBC {o.uibc_port}), screen "
                 f"{o.screen[0]}x{o.screen[1]} dpi {o.dpi} fps {o.fps}")
        end = time.monotonic() + o.duration
        try:
            while not self.done.is_set():
                if time.monotonic() > end:
                    self.finish("duration")
                    break
                try:
                    conn, addr = self.auth_srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                self.log(f"auth: connection from {addr[0]}")
                self.over = threading.Event()
                self.socks = [conn]
                try:
                    if self._sign_in(conn) and self._open_lanes():
                        while not self.over.wait(0.5):
                            if time.monotonic() > end:
                                self.finish("duration")
                except (OSError, EOFError, ValueError) as e:
                    self.log(f"session error: {type(e).__name__}: {e}")
                finally:
                    self._close_session()
        finally:
            self.done.set()
            for s in (self.auth_srv, self.rtp_srv):
                s.close()
            self.log(f"summary: sessions={self.sessions} rejects={self.rejects} video_units={self.video_units} "
                     f"audio_units={self.audio_units}")
            for f in (self.videof, self.audiof, self.logf):
                if f:
                    f.close()

    def _close_session(self):
        self.over.set()
        self.playing.clear()
        for s in self.socks:
            try:
                s.shutdown(socket.SHUT_RDWR)            # close() мало: сокет держит makefile нити чтения
            except OSError:
                pass
            s.close()
        self.control = self.rtsp = self.uibc = self.sink = self.cipher = None

    def _sign_in(self, conn: socket.socket) -> bool:
        conn.settimeout(10)
        f = conn.makefile("rb")
        fr = W.read_frame(f)
        if fr is None or (fr.cat, fr.method) != (W.CAT_AUTH, 1):
            self.log(f"auth: expected cat 3 m1, got {fr and (fr.cat, fr.method)}")
            return False
        resp, sess = self.auth.respond(fr.body)
        conn.sendall(W.frame(W.CAT_AUTH, 2, W.T_RESPONSE, resp, fr.seq))
        if sess is None:
            self.rejects += 1
            self.log(f"auth: sign-in rejected, result={W.fields(resp)[7]} ({W.shape(fr.body)})")
            return False
        fr = W.read_frame(f)
        if fr is None or (fr.cat, fr.method) != (W.CAT_AUTH, 3):
            self.log("auth: no Confirm (cat 3 m3)")
            return False
        addr = self.auth.confirm(fr.body, sess)
        self.cipher = sess.cipher
        self.sessions += 1
        self.log(f"auth: ok, client {sess.client_id!r}, {'quick (no PIN)' if sess.quick else 'PIN'}, Confirm says {addr}")
        conn.close()                                    # auth - стартовый канал, дальше не нужен
        return True

    def _connect(self, port: int, name: str, tries: int = 2) -> socket.socket | None:
        for _ in range(tries):
            if self.over.is_set():
                return None
            try:
                s = socket.create_connection((self.o.phone, port), timeout=3)
            except OSError:
                time.sleep(0.3)
                continue
            s.settimeout(None)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.socks.append(s)
            self.log(f"{name}: connected to {self.o.phone}:{port}")
            return s
        self.log(f"{name}: no connection to {self.o.phone}:{port}")
        return None

    def _open_lanes(self) -> bool:
        self.control = self._connect(self.o.control_port, "control", CONNECT_TRIES)
        if not self.control:
            return False
        over = self.over
        self._thread(self._lane_loop, self.control, "control", over)
        self._thread(self._heartbeat, over)
        self.send_control(2, b"\x08\x01", W.T_REQUEST)  # get_port SENSOR, как машина Toyota (FT IccoaPortNegotiation)
        for port, name in ((self.o.audio_port, "audio"), (self.o.sensor_port, "sensor")):
            s = self._connect(port, name)               # вспомогательные каналы: нет - не беда
            if s:
                self._thread(self._lane_loop, s, name, over)
        self.rtsp = self._connect(self.o.rtsp_port, "RTSP", CONNECT_TRIES)
        if self.rtsp:
            self.sink = W.RtspSink(*self.o.screen, self.o.dpi, self.o.fps, self.rtp_port, self.o.phone)
            self._thread(self._rtsp_loop, self.rtsp, self.sink)
        return True

    def _thread(self, target, *args):
        threading.Thread(target=target, args=args, daemon=True, name=f"iccoa-{target.__name__}").start()

    # ---- кадры cat 1/2/4 ----

    def _send(self, sock: socket.socket | None, data: bytes) -> bool:
        if sock is None:
            return False
        try:
            with self.wlock:
                sock.sendall(data)
            return True
        except OSError:
            return False

    def send_control(self, method: int, body: bytes = b"", type_: int = W.T_NOTIFY) -> bool:
        cipher = self.cipher
        return bool(cipher) and self._send(self.control, W.frame(W.CAT_CONTROL, method, type_, cipher.encrypt(body),
                                                                 next(self.seq)))

    def _heartbeat(self, over: threading.Event):
        while self.send_control(1, W.heartbeat_body()) and not over.wait(2):
            pass

    def _lane_loop(self, sock: socket.socket, name: str, over: threading.Event):
        cipher, n, seen = self.cipher, 0, set()
        try:
            f = sock.makefile("rb")
            while (fr := W.read_frame(f)) is not None:
                n += 1
                body = cipher.decrypt(fr.body) if W.is_encrypted(fr.body) else fr.body
                if fr.type == W.T_NEED_ACK:
                    self._send(sock, W.frame(W.CAT_ACK, 0, W.T_RESPONSE, b"", fr.seq))
                key = (fr.cat, fr.method)
                if key != (W.CAT_CONTROL, 1) and (key not in seen or fr.cat == W.CAT_CONTROL):
                    seen.add(key)                       # PCM и датчики - только первый кадр каждого метода
                    self.log(f"{name} <- cat {fr.cat} m{fr.method} {METHODS.get(fr.method, '') if fr.cat == 1 else ''} "
                             f"type {fr.type} {W.shape(body) if fr.cat != W.CAT_PCM else f'pcm({len(body)})'}")
                if key == (W.CAT_CONTROL, 24):
                    self.send_control(25, W.car_config(self.o.dpi))
                    self.log(f"control -> m25 config: {W.shape(W.car_config(self.o.dpi))}")
                elif key == (W.CAT_CONTROL, 23):
                    break
        except (OSError, EOFError, ValueError) as e:
            if not over.is_set():
                self.log(f"{name}: {type(e).__name__}: {e}")
        self.log(f"{name}: closed, frames={n}")
        if name == "control":                           # обрыв сессии - по закрытию control
            over.set()

    # ---- RTSP (WFD sink) ----

    def _rtsp_loop(self, sock: socket.socket, sink: W.RtspSink):
        parser = W.RtspParser()
        try:
            while data := sock.recv(65536):
                for m in parser.feed(data):
                    if "GET_PARAMETER" not in m.first or m.body:    # M16 keep-alive раз в 20 с не шумит
                        self.log(f"RTSP <- {m.first}" + (f" | {' | '.join(m.body.split())}" if m.body else ""))
                    with self.wlock:
                        out = sink.feed(m)
                    for b in out:
                        self.log("RTSP -> " + b.split(b"\r\n", 1)[0].decode("latin-1"))
                        self._send(sock, b)
                    if sink.playing and not self.playing.is_set():
                        self.uibc = self._connect(self.o.uibc_port, "UIBC")
                        self.playing.set()
                        self.log(f"RTSP: PLAY accepted, waiting for RTP/TCP on :{self.rtp_port}")
        except OSError:
            pass
        self.log("RTSP: closed")

    def request_idr(self):
        sink = self.sink
        if sink:
            with self.wlock:
                data = sink.idr_request()
            self._send(self.rtsp, data)
            self.log("RTSP -> SET_PARAMETER wfd_idr_request")

    # ---- RTP/TCP от телефона ----

    def _rtp_loop(self):
        while not self.done.is_set():
            try:
                conn, addr = self.rtp_srv.accept()
            except OSError:
                return
            self.log(f"RTP: connection from {addr[0]}")
            rfc, ts, packets, first = W.Rfc4571(), W.TsDemux(), 0, True
            try:
                while data := conn.recv(262144):
                    for pkt in rfc.feed(data):
                        pt, marker, payload = W.parse_rtp(pkt)
                        packets += 1
                        if pt != 33:
                            continue
                        for pid, unit in ts.feed(payload, marker):
                            if pid == W.VIDEO_PID:
                                self.video_units += 1
                                self.videof.write(unit)
                                self.videof.flush()     # файл читают, пока эмулятор жив; на Windows его убивают без close
                                if first:
                                    first = False
                                    self.log(f"RTP: first video unit, {len(unit)} bytes")
                                if self.video_sink:
                                    self.video_sink(unit)
                            else:
                                self.audio_units += 1
                                if self.audiof:
                                    self.audiof.write(unit)
            except (OSError, ValueError) as e:
                self.log(f"RTP: {type(e).__name__}: {e}")
            conn.close()
            self.log(f"RTP: closed, packets={packets} video_units={self.video_units} audio_units={self.audio_units} "
                     f"bad_ts={ts.bad}")

    # ---- ввод: UIBC и кнопки руля ----

    def _uibc_send(self, payload: bytes):
        cipher = self.cipher
        if cipher:
            self._send(self.uibc, payload if self.o.uibc_plain else cipher.encrypt(payload))

    def send_touch(self, action: int, x: int, y: int):
        """Касание в пикселях экрана машины (action как у carlife_view: 0 down, 1 up, 2 move)."""
        self._uibc_send(W.uibc_touch(action, x, y, *self.o.screen))
        if action != 2:
            self.log(f"UIBC -> touch action={action} x={x} y={y}")

    def send_uibc_key(self, keycode: int):
        self._uibc_send(W.uibc_key(keycode, False))
        self._uibc_send(W.uibc_key(keycode, True))
        self.log(f"UIBC -> key {keycode}")

    def send_wheel_key(self, keycode: int):
        self.send_control(12, W.key_body(keycode, False))
        self.send_control(12, W.key_body(keycode, True))
        self.log(f"control -> m12 key {keycode}")

    def stdin_loop(self):
        """--no-view: ввод вместо окна строками stdin: touch ACTION X Y | key CODE | wheel CODE | idr."""
        do = {"touch": self.send_touch, "key": self.send_uibc_key, "wheel": self.send_wheel_key, "idr": self.request_idr}
        for line in sys.stdin:
            try:
                cmd, *a = line.split()
                do[cmd](*map(int, a))
            except (ValueError, KeyError, TypeError):
                self.log(f"stdin: bad command {line.strip()!r}")


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--phone", default="127.0.0.1", metavar="HOST", help="phone address (default 127.0.0.1: adb forward)")
    p.add_argument("--screen", default="2422x1272", metavar="WxH", help="car screen reported in RTSP M3 (default: Toyota XA60)")
    p.add_argument("--dpi", type=int, default=160)
    p.add_argument("--fps", type=int, default=60)
    p.add_argument("--pin", default="123456", help="6 digits the phone must enter (default 123456)")
    p.add_argument("--bind", default="127.0.0.1", metavar="IP", help="address of our listeners (0.0.0.0 for a phone over Wi-Fi)")
    p.add_argument("--auth-port", type=int, default=57209, help="we listen: sign-in")
    p.add_argument("--rtp-port", type=int, default=15550, help="we listen: RTP/TCP, reported in RTSP M3")
    p.add_argument("--control-port", type=int, default=57219, help="phone port")
    p.add_argument("--audio-port", type=int, default=57229, help="phone port")
    p.add_argument("--sensor-port", type=int, default=57239, help="phone port")
    p.add_argument("--rtsp-port", type=int, default=7236, help="phone port")
    p.add_argument("--uibc-port", type=int, default=4321, help="phone port")
    p.add_argument("--uibc-plain", action="store_true", help="UIBC as plain JSON instead of AES-GCM envelopes")
    p.add_argument("--force-result", type=int, default=0, metavar="N", help="answer every sign-in with result N (1..4)")
    p.add_argument("--no-view", action="store_true", help="no window (tests)")
    p.add_argument("--view-scale", type=float, default=None, metavar="K", help="window scale (default: fit to the PC screen)")
    p.add_argument("--dump-audio", action="store_true", help="write AAC (PID 257) to out/iccoa_audio.aac")
    p.add_argument("--duration", type=float, default=float("inf"), metavar="N", help="stop after N seconds")
    p.add_argument("--out", type=Path, default=HERE / "out", help="output directory (default pc-headunit/out)")
    p.add_argument("--log", type=Path, default=None, help="log file (default <out>/iccoa_hu.log)")
    p.add_argument("--trust", type=Path, default=None, help="car key and trusted clients (default <out>/iccoa_trust.json)")
    p.add_argument("--quiet", action="store_true", help="do not print the log to stdout")
    a = p.parse_args(argv)
    try:
        a.screen = parse_size(a.screen)
    except ValueError as e:
        p.error(str(e))
    if not re.fullmatch(r"[0-9]{6}", a.pin):
        p.error("--pin must be 6 digits")
    return a


def run_view(hu: IccoaHu, scale: float | None) -> int:
    """Сессия в рабочей нити, окно tkinter в главной (как carlife_hu.run_view)."""
    import signal
    import traceback
    import carlife_view as V
    ffmpeg = V.find_ffmpeg()
    if not ffmpeg:
        print("the window needs ffmpeg in PATH (winget install Gyan.FFmpeg), or use --no-view", file=sys.stderr)
        return 2
    hu.listen()                                         # порт занят - узнаём сразу, до окна
    wheel = [(label, lambda code=code: hu.send_wheel_key(code)) for label, code in WHEEL_KEYS]
    wheel += [("IDR", hu.request_idr), ("Назад", lambda: hu.send_uibc_key(W.KEY_BACK)),
              ("Домой", lambda: hu.send_uibc_key(W.KEY_HOME))]
    viewer = V.Viewer(ffmpeg, hu.o.screen, scale, hu.send_touch, hu.send_uibc_key, lambda: hu.finish("window closed"),
                      lambda: worker.is_alive(), hu.log, W.KEY_BACK, W.KEY_HOME, title="ICCOA HU", wheel=wheel)
    viewer.pipe.set_video_size(*hu.o.screen)            # машина тянет кадр на свой экран; тач - в его пикселях
    hu.video_sink = viewer.pipe.on_video

    def work():
        try:
            hu.run()
        except BaseException:
            traceback.print_exc()
            hu.finish("error")

    worker = threading.Thread(target=work, name="iccoa-session")
    signal.signal(signal.SIGINT, lambda *_: hu.finish("Ctrl+C"))
    worker.start()
    viewer.run()
    hu.finish("window closed")
    worker.join()
    return 0


def main(argv=None) -> int:
    a = parse_args(argv)
    hu = IccoaHu(a)
    if not a.no_view:
        return run_view(hu, a.view_scale)
    threading.Thread(target=hu.stdin_loop, daemon=True, name="iccoa-stdin").start()
    try:
        hu.run()
    except KeyboardInterrupt:
        hu.finish("Ctrl+C")
    return 0


if __name__ == "__main__":
    sys.exit(main())
