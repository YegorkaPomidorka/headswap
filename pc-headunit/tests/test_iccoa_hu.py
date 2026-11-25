"""End-to-end test of iccoa_hu.py on localhost: a minimal Python "phone" (auth -> lane servers -> RTSP source -> RTP/TS)
against the emulator in --no-view mode. No phone, no adb, no window.

    python -X utf8 -m unittest discover -s tests -p "test_iccoa*.py" -v
"""
from __future__ import annotations

import json
import re
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import iccoa_hu as H                                             # noqa: E402
import iccoa_wire as W                                           # noqa: E402
from test_iccoa_wire import ADTS, H264, M3_BODY, M4_BODY, PhoneAuth, rtp_tcp, ts_pes, ts_psi   # noqa: E402

TIMEOUT = 10


class FakePhone:
    """Телефон: слушает control/audio/sensor/RTSP/UIBC на свободных портах, сам ходит на auth и RTP машины."""
    LANES = ("control", "audio", "sensor", "rtsp", "uibc")

    def __init__(self):
        self.srv = {name: socket.create_server(("127.0.0.1", 0)) for name in self.LANES}
        self.cipher: W.Cipher | None = None
        self.seq = 100
        self.rtsp_parser = W.RtspParser()
        self.rtsp_queue: list[W.RtspMsg] = []
        self.opened: list[socket.socket] = []

    def port(self, name: str) -> int:
        return self.srv[name].getsockname()[1]

    def accept(self, name: str) -> socket.socket:
        self.srv[name].settimeout(TIMEOUT)
        conn, _ = self.srv[name].accept()
        conn.settimeout(TIMEOUT)
        self.opened.append(conn)
        return conn

    def close(self):
        for s in [*self.srv.values(), *self.opened]:
            s.close()

    def sign_in(self, port: int, auth: PhoneAuth, confirm: bool = True) -> int:
        """-> result машины; при 0 проверяет ответ по формулам FT, ставит self.cipher и шлёт Confirm."""
        with socket.create_connection(("127.0.0.1", port), timeout=TIMEOUT) as s:
            s.sendall(W.frame(W.CAT_AUTH, 1, W.T_REQUEST, auth.request(), 1))
            fr = W.read_frame(s.makefile("rb"))
            assert (fr.cat, fr.fmt, fr.seq) == (W.CAT_AUTH, 1, 1), fr
            result = W.fields(fr.body).get(7, 0)
            if result == 0 and confirm:
                self.cipher = auth.accept(fr.body)
                s.sendall(W.frame(W.CAT_AUTH, 3, W.T_NOTIFY, auth.confirm(self.cipher, "127.0.0.1"), 2))
            return result

    def send(self, sock: socket.socket, cat: int, method: int, body: bytes = b"", type_: int = W.T_NOTIFY) -> int:
        self.seq += 1
        sock.sendall(W.frame(cat, method, type_, self.cipher.encrypt(body), self.seq))
        return self.seq

    def read_until(self, f, cat: int, method: int) -> tuple[W.Frame, bytes]:
        """Следующий кадр (cat, method); остальные пропускаются. -> (кадр, расшифрованное тело)."""
        while True:
            fr = W.read_frame(f)
            assert fr is not None, f"lane closed while waiting for cat {cat} m{method}"
            if (fr.cat, fr.method) == (cat, method):
                return fr, self.cipher.decrypt(fr.body) if W.is_encrypted(fr.body) else fr.body

    def rtsp_read(self, sock: socket.socket) -> W.RtspMsg:
        while not self.rtsp_queue:
            data = sock.recv(65536)
            assert data, "RTSP closed"
            self.rtsp_queue += self.rtsp_parser.feed(data)
        return self.rtsp_queue.pop(0)


def wait_for(cond, what: str):
    end = time.monotonic() + TIMEOUT
    while not cond():
        assert time.monotonic() < end, f"timeout: {what}"
        time.sleep(0.02)


class IccoaHuEndToEnd(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)
        self.phone = FakePhone()
        self.hu = None

    def tearDown(self):
        if self.hu:
            self.hu.finish("test done")
            self.worker.join(TIMEOUT)
        self.phone.close()
        self.tmp.cleanup()

    def start_hu(self, *extra: str) -> H.IccoaHu:
        p = self.phone
        args = ["--no-view", "--quiet", "--out", str(self.out), "--auth-port", "0", "--rtp-port", "0", "--pin", "654321",
                "--screen", "1920x720", "--dpi", "200", "--fps", "30", "--dump-audio"]
        for name in FakePhone.LANES:
            args += [f"--{name}-port", str(p.port(name))]
        self.hu = H.IccoaHu(H.parse_args(args + list(extra)))
        self.hu.listen()
        self.worker = threading.Thread(target=self.hu.run, daemon=True)
        self.worker.start()
        return self.hu

    def test_full_session_then_quick_reconnect(self):
        hu, p = self.start_hu(), self.phone
        auth = PhoneAuth("654321")
        self.assertEqual(p.sign_in(hu.auth_port, auth), 0)

        # control: get_port SENSOR, heartbeat, m24 -> m25, ack на type 3
        control = p.accept("control")
        cf = control.makefile("rb")
        fr, body = p.read_until(cf, W.CAT_CONTROL, 1)
        self.assertTrue(W.is_encrypted(fr.body))
        self.assertLess(abs(W.fields(body)[1] / 1000 - time.time()), 60)
        p.send(control, W.CAT_CONTROL, 24)
        p.send(control, W.CAT_CONTROL, 3, b"\x08\x01\x10\x97\xbe\x03")     # ответ get_port: SENSOR, 57239
        _, body = p.read_until(cf, W.CAT_CONTROL, 25)
        self.assertEqual(W.fields(body), {18: 769, 19: 0, 20: 0, 21: 1, 22: 1, 23: 0, 24: 200})
        seq = p.send(control, W.CAT_CONTROL, 8, b"\x08\x10\x10\x00", W.T_NEED_ACK)
        fr, body = p.read_until(cf, W.CAT_ACK, 0)
        self.assertEqual((fr.seq, fr.type, body), (seq, W.T_RESPONSE, b""))
        audio, sensor = p.accept("audio"), p.accept("sensor")

        # RTSP: телефон - источник, диалог FT M1..M7
        rtsp = p.accept("rtsp")
        rtsp.sendall(W.rtsp_msg("OPTIONS * RTSP/1.0", 1, extra="Require: org.wfa.wfd1.0\r\n"))
        self.assertEqual(p.rtsp_read(rtsp).first, "RTSP/1.0 200 OK")
        m2 = p.rtsp_read(rtsp)
        self.assertTrue(m2.first.startswith("OPTIONS"))
        rtsp.sendall(W.rtsp_msg("RTSP/1.0 200 OK", m2.headers["cseq"], extra="Public: org.wfa.wfd1.0, SETUP, PLAY\r\n"))
        rtsp.sendall(W.rtsp_msg("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 2, M3_BODY))
        m3 = p.rtsp_read(rtsp).body
        self.assertEqual(re.search(r"display=(\d+):(\d+);dpi=(\d+);fps=(\d+)", m3).groups(), ("1920", "720", "200", "30"))
        rtp_port = int(re.search(r"RTP/AVP/TCP;[^\r\n]*?\s(\d+)\s+\d+\s+mode=play", m3).group(1))
        self.assertEqual(rtp_port, hu.rtp_port)
        rtsp.sendall(W.rtsp_msg("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 3, M4_BODY))
        self.assertEqual(p.rtsp_read(rtsp).first, "RTSP/1.0 200 OK")
        rtsp.sendall(W.rtsp_msg("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 4, "wfd_trigger_method: SETUP\r\n"))
        self.assertEqual(p.rtsp_read(rtsp).first, "RTSP/1.0 200 OK")
        m6 = p.rtsp_read(rtsp)
        self.assertTrue(m6.first.startswith("SETUP"))
        self.assertIn(f"client_port={rtp_port}", m6.headers["transport"])
        rtsp.sendall(W.rtsp_msg("RTSP/1.0 200 OK", m6.headers["cseq"],
                                extra=f"Transport: {m6.headers['transport']}\r\nSession: 1;timeout=30\r\n"))
        m7 = p.rtsp_read(rtsp)
        self.assertEqual((m7.first.split()[0], m7.headers["session"]), ("PLAY", "1"))
        rtsp.sendall(W.rtsp_msg("RTSP/1.0 200 OK", m7.headers["cseq"], extra="Session: 1\r\n"))
        self.assertTrue(hu.playing.wait(TIMEOUT))

        # RTP/TCP: PSI, кадр H.264, AAC, ещё кадр
        got = []
        hu.video_sink = got.append
        second = b"\0\0\0\1\x41" + b"p" * 500
        with socket.create_connection(("127.0.0.1", rtp_port), timeout=TIMEOUT) as rtp:
            rtp.sendall(rtp_tcp(ts_psi() + ts_pes(256, 0xE0, H264)) + rtp_tcp(ts_pes(257, 0xC0, ADTS), 5)
                        + rtp_tcp(ts_pes(256, 0xE0, second, 1), 6))
            wait_for(lambda: hu.video_units == 2 and hu.audio_units == 1, "video and audio units")
        self.assertEqual(got, [H264, second])

        # UIBC: шифрованные конверты, координаты в пикселях экрана машины
        uibc = p.accept("uibc")
        hu.send_touch(0, 100, 200)
        hu.send_uibc_key(W.KEY_BACK)
        events, buf = [], b""
        while len(events) < 3:
            buf += uibc.recv(4096)
            while len(buf) >= 20 and len(buf) >= 20 + int.from_bytes(buf[16:20], "big"):
                n = 20 + int.from_bytes(buf[16:20], "big")
                events.append(json.loads(p.cipher.decrypt(buf[:n])))
                buf = buf[n:]
        self.assertEqual(events[0], {"type": 1, "action": 0, "count": 1, "x0": 100, "y0": 200, "trackID0": 0,
                                     "width": 1920, "height": 720})
        self.assertEqual([(e["action"], e["keycode"]) for e in events[1:]], [(0, 4), (1, 4)])

        # кнопка руля -> control m12, запрос IDR -> RTSP
        hu.send_wheel_key(85)
        self.assertEqual(p.read_until(cf, W.CAT_CONTROL, 12)[1], W.key_body(85, False))
        self.assertEqual(p.read_until(cf, W.CAT_CONTROL, 12)[1], W.key_body(85, True))
        hu.request_idr()
        idr = p.rtsp_read(rtsp)
        self.assertIn("wfd_idr_request", idr.body)

        # обрыв control = конец сессии; повторный вход без PIN по запомненному ключу машины
        control.shutdown(socket.SHUT_RDWR)                          # close() не хватит: сокет держит makefile
        wait_for(lambda: hu.control is None, "session end")
        self.assertEqual(sensor.recv(16), b"")                      # машина закрыла остальные каналы
        self.assertEqual(audio.recv(16), b"")
        quick = PhoneAuth(car_pub=auth.car_pub, identity=auth.identity)
        self.assertEqual(p.sign_in(hu.auth_port, quick), 0)
        _, body = p.read_until(p.accept("control").makefile("rb"), W.CAT_CONTROL, 1)   # heartbeat уже новым ключом
        self.assertIn(1, W.fields(body))
        self.assertEqual(hu.sessions, 2)

        hu.finish("test done")
        self.worker.join(TIMEOUT)
        self.assertEqual((self.out / "iccoa_video.h264").read_bytes(), H264 + second)
        self.assertEqual((self.out / "iccoa_audio.aac").read_bytes(), ADTS)
        log = (self.out / "iccoa_hu.log").read_text("utf-8")
        self.assertIn("quick (no PIN)", log)
        self.assertIn("get_port_rsp", log)
        self.assertNotIn("654321", log)                             # PIN в лог не попадает

    def test_wrong_pin_unknown_quick_and_plain_uibc(self):
        hu, p = self.start_hu("--uibc-plain"), self.phone
        self.assertEqual(p.sign_in(hu.auth_port, PhoneAuth("000000")), W.RESULT_NEED_PIN)
        self.assertEqual(p.sign_in(hu.auth_port, PhoneAuth(car_pub=bytes(64))), W.RESULT_NEED_PIN)
        self.assertEqual(hu.sessions, 0)
        # верный PIN после отказов; UIBC открытым JSON
        self.assertEqual(p.sign_in(hu.auth_port, PhoneAuth("654321")), 0)
        p.accept("control")
        rtsp = p.accept("rtsp")
        rtsp.sendall(W.rtsp_msg("OPTIONS * RTSP/1.0", 1))
        p.rtsp_read(rtsp)
        p.rtsp_read(rtsp)
        rtsp.sendall(W.rtsp_msg("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 2, "wfd_trigger_method: SETUP\r\n"))
        p.rtsp_read(rtsp)
        rtsp.sendall(W.rtsp_msg("RTSP/1.0 200 OK", p.rtsp_read(rtsp).headers["cseq"], extra="Session: 7\r\n"))
        play = p.rtsp_read(rtsp)
        self.assertEqual(play.headers["session"], "7")
        rtsp.sendall(W.rtsp_msg("RTSP/1.0 200 OK", play.headers["cseq"]))
        uibc = p.accept("uibc")
        self.assertTrue(hu.playing.wait(TIMEOUT))
        hu.send_touch(1, 5, 6)
        self.assertEqual(json.loads(uibc.recv(4096))["y0"], 6)
        self.assertEqual(hu.rejects, 2)

    def test_force_result(self):
        hu = self.start_hu("--force-result", "2")
        self.assertEqual(self.phone.sign_in(hu.auth_port, PhoneAuth("654321")), W.RESULT_CONFIRM_ON_CAR)
        self.assertEqual(hu.sessions, 0)


if __name__ == "__main__":
    unittest.main()
