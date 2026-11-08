"""Baidu CarLife head unit emulator (Honda CR-V 2026, China) for testing the Headswap bridge.

    adb reverse tcp:7240 tcp:7240
    python carlife_hu.py --duration 120

The bridge (Android app) plays the phone (MD) and connects to this TCP server over `adb reverse`;
this script plays the head unit (HU). One connection per start. After connect it sends
HU_PROTOCOL_VERSION, after the bridge's MD_INFO the HU init burst (STATISTIC_INFO, HU_INFO,
HU_AUTHEN_REQUEST "2.0;<date>", video init/start, feature config), then a VIDEO_HEARTBEAT every second.
MD_AUTHEN_RESPONSE is checked as md5("CARLIFE" + brand + model + sdk + <random after ';'>).
Video goes to out/carlife_video.h264, the whole log to out/carlife_hu.log. Standard library only.

--pause-cycles N (with --pause-every S, --pause-for S) repeats VIDEO_ENCODER_PAUSE -> wait -> VIDEO_ENCODER_START and
reports start->first frame, start->IDR and frames received during the pause.

--view also plays MEDIA/TTS on the PC speakers when the optional sounddevice module is installed (--mute turns it off).
--view opens a live window (tkinter + ffmpeg from PATH, see carlife_view.py): the bridge's video in real time, mouse clicks
and drags go to the bridge as TOUCH_ACTION (pixels of the video), right click / Esc / BackSpace = hard key BACK, middle
click / Home = hard key HOME. --size WxH is the screen size the HU asks for in VIDEO_ENCODER_INIT (default 1920x720).

Wireless format (--transport tcp6): the HU is the TCP client of 6 phone ports (CMD 7240, VIDEO 8240, MEDIA 9240,
TTS 9241, VR 9242, TOUCH 9340; reach them with `adb forward`). No outer header, each channel is its own socket
with plain inner messages. Session logic is the same. --kill-chan/--kill-after close one socket to see whether
the bridge closes the rest.
"""
from __future__ import annotations

import argparse
import hashlib
import select
import socket
import struct
import sys
import threading
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

from carlife_view import parse_size

HERE = Path(__file__).resolve().parent

# каналы внешнего заголовка (Protocol.kt, object Ch)
CH_CMD, CH_VIDEO, CH_MEDIA, CH_TTS, CH_VR, CH_TOUCH = 1, 2, 3, 4, 5, 6
CH_NAMES = {CH_CMD: "CMD", CH_VIDEO: "VIDEO", CH_MEDIA: "MEDIA", CH_TTS: "TTS", CH_VR: "VR", CH_TOUCH: "TOUCH"}
MAX_OUTER_LEN = 64 * 1024 * 1024
# лимиты длины inner-сообщения в режиме 6 сокетов
MAX_CMD_LEN = 1024 * 1024
MAX_DATA_LEN = 64 * 1024 * 1024

# режим tcp6: порты, которые слушает телефон (беспроводной формат CarLife), порядок подключения = порядок каналов
DEFAULT_PORTS = {CH_CMD: 7240, CH_VIDEO: 8240, CH_MEDIA: 9240, CH_TTS: 9241, CH_VR: 9242, CH_TOUCH: 9340}
DATA_PORT = 9440                    # Data-канал: телефон принимает соединение и игнорирует его

# ветка B (--discover): телефон раздаёт хотспот и шлёт UDP-маяк, ГУ слушает его и подключается к отправителю
BEACON_PORT = 7999                  # UDP-порт приёма маяка (как у эталонной ГУ)
BEACON_PAYLOAD = b"CarlifeHost"     # что шлёт телефон раз в 2 с (содержимое строго не проверяем)
PROBE_PORT = 8999                   # UDP-проба: JSON-запрос на телефон, ответ необязателен
PROBE_REQUEST = b'{"carlifehost":"carlife","deviceInfo":{}}'

# коды сообщений (Protocol.kt, object Msg)
HU_PROTOCOL_VERSION = 0x00018001
MD_INFO = 0x00010004
HU_INFO = 0x00018003
VIDEO_ENCODER_INIT = 0x00018007
VIDEO_ENCODER_INIT_DONE = 0x00010008
VIDEO_ENCODER_START = 0x00018009
VIDEO_ENCODER_PAUSE = 0x0001800A    # Protocol.kt, Msg.VIDEO_ENCODER_PAUSE; тело пустое, как у START
STATISTIC_INFO = 0x00018027
MODULE_CONTROL = 0x00018028
HU_AUTHEN_REQUEST = 0x00018048
MD_AUTHEN_RESPONSE = 0x00010049
HU_AUTHEN_RESULT = 0x0001804A
HU_UNKNOWN_1804C = 0x0001804C
HU_FEATURE_CONFIG_RESPONSE = 0x00018052
VIDEO_DATA = 0x00020001
VIDEO_HEARTBEAT = 0x00020002
TOUCH_ACTION = 0x00068001
TOUCH_CAR_HARD_KEY_CODE = 0x00068008
# action у TOUCH_ACTION как у MotionEvent (AaBridge.onCarlifeTouch); коды кнопок - HUJ/CommonParams
TOUCH_DOWN, TOUCH_UP, TOUCH_MOVE = 0, 1, 2
HARD_KEY_HOME, HARD_KEY_BACK = 0x01, 0x0E
DEFAULT_SIZE = (1920, 720)

NAMES = {
    0x00010002: "PROTOCOL_VERSION_MATCH_STATUS", MD_INFO: "MD_INFO", 0x00010006: "MD_BT_OOB_INFO",
    VIDEO_ENCODER_INIT_DONE: "VIDEO_ENCODER_INIT_DONE", 0x0001000D: "VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE",
    0x00010018: "SCREEN_ON", 0x0001001B: "FOREGROUND", 0x0001001C: "BACKGROUND",
    0x0001801D: "LAUNCH_MODE_NORMAL", 0x00010021: "GO_TO_DESKTOP", 0x00010026: "MODULE_STATUS",
    STATISTIC_INFO: "STATISTIC_INFO", 0x00018028: "MODULE_CONTROL", 0x00010029: "GEAR_INFO",
    0x00010030: "NAVI_NEXT_TURN_INFO", 0x00010031: "CAR_DATA_SUBSCRIBE", 0x00010035: "MEDIA_INFO",
    0x00010036: "MEDIA_PROGRESS_BAR", 0x00010037: "CONNECT_EXCEPTION", 0x00018042: "BT_HFP_CONNECTION",
    0x00018043: "CARLIFE_DATA_SUBSCRIBE", 0x00010044: "CARLIFE_DATA_SUBSCRIBE_DONE",
    0x00018045: "CARLIFE_DATA_SUBSCRIBE_START", 0x00018046: "CARLIFE_DATA_SUBSCRIBE_STOP",
    MD_AUTHEN_RESPONSE: "MD_AUTHEN_RESPONSE", 0x0001004B: "MD_AUTHEN_RESULT",
    0x00010051: "MD_FEATURE_CONFIG_REQUEST", 0x00018055: "ERROR_CODE", 0x00010059: "MD_EXIT",
    0x00018078: "CHANGE_MD_THEME", 0x00018079: "REQUEST_MD_THEME_STATE", 0x00010080: "RESPONSE_MD_THEME_STATE",
    VIDEO_DATA: "VIDEO_DATA", VIDEO_HEARTBEAT: "VIDEO_HEARTBEAT",
    0x00030001: "MEDIA_INIT", 0x00030006: "MEDIA_DATA", 0x00040001: "TTS_INIT", 0x00040003: "TTS_DATA",
    0x00050002: "VR_INIT", 0x00050003: "VR_DATA",
    HU_PROTOCOL_VERSION: "HU_PROTOCOL_VERSION", HU_INFO: "HU_INFO", VIDEO_ENCODER_INIT: "VIDEO_ENCODER_INIT",
    VIDEO_ENCODER_START: "VIDEO_ENCODER_START", VIDEO_ENCODER_PAUSE: "VIDEO_ENCODER_PAUSE", HU_AUTHEN_REQUEST: "HU_AUTHEN_REQUEST",
    HU_AUTHEN_RESULT: "HU_AUTHEN_RESULT", HU_UNKNOWN_1804C: "HU_1804C",
    HU_FEATURE_CONFIG_RESPONSE: "HU_FEATURE_CONFIG_RESPONSE", TOUCH_ACTION: "TOUCH_ACTION",
    TOUCH_CAR_HARD_KEY_CODE: "TOUCH_CAR_HARD_KEY_CODE",
}


def msg_name(t: int) -> str:
    return NAMES.get(t, "UNKNOWN")


# ---- protobuf руками (varint и length-delimited) ----

def varint(v: int) -> bytes:
    v &= 0xFFFFFFFFFFFFFFFF          # отрицательный int32 = 10 байт, как в protoc
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_int(field: int, v: int) -> bytes:
    return varint(field << 3) + varint(v)


def pb_bytes(field: int, b: bytes) -> bytes:
    return varint(field << 3 | 2) + varint(len(b)) + b


def pb_str(field: int, s: str) -> bytes:
    return pb_bytes(field, s.encode("utf-8"))


def pb_parse(b: bytes) -> list[tuple[int, int, object]] | None:
    """[(поле, wire, значение)]; None, если байты не похожи на protobuf. Строки/вложенные: bytes."""
    res = []
    p = 0

    def rd_varint():
        nonlocal p
        r = 0
        shift = 0
        while p < len(b) and shift < 70:
            x = b[p]
            p += 1
            r |= (x & 0x7F) << shift
            if not x & 0x80:
                return r
            shift += 7
        return None

    while p < len(b):
        t = rd_varint()
        if t is None:
            return None
        wire, field = t & 7, t >> 3
        if field == 0:
            return None
        if wire == 0:
            v = rd_varint()
            if v is None:
                return None
            res.append((field, 0, v))
        elif wire == 2:
            n = rd_varint()
            if n is None or p + n > len(b):
                return None
            res.append((field, 2, b[p:p + n]))
            p += n
        elif wire == 1 and p + 8 <= len(b):
            res.append((field, 1, b[p:p + 8]))
            p += 8
        elif wire == 5 and p + 4 <= len(b):
            res.append((field, 5, b[p:p + 4]))
            p += 4
        else:
            return None
    return res


def pb_get_str(fields, number: int) -> str | None:
    for f, w, v in fields or []:
        if f == number and w == 2:
            return bytes(v).decode("utf-8", "replace")
    return None


def pb_get_int(fields, number: int) -> int | None:
    for f, w, v in fields or []:
        if f == number and w == 0:
            return int(v)
    return None


def pb_render(body: bytes, limit: int = 200) -> str:
    """Короткий текст protobuf-тела для лога: поле=значение."""
    if not body:
        return "{}"
    fields = pb_parse(body)
    if fields is None:
        return "<not protobuf: " + body[:32].hex(" ") + ">"
    parts = []
    for f, w, v in fields:
        if w == 2:
            try:
                s = bytes(v).decode("utf-8")
                ok = all(c.isprintable() for c in s)
            except UnicodeDecodeError:
                ok = False
            parts.append(f"f{f}={s!r}" if ok else f"f{f}=<{len(v)}B {bytes(v)[:16].hex()}>")
        else:
            parts.append(f"f{f}={v if w == 0 else bytes(v).hex()}")
    s = " ".join(parts)
    return "{" + (s if len(s) <= limit else s[:limit] + "...") + "}"


# ---- звук в динамики ПК (--view) ----

MEDIA_INIT, MEDIA_DATA, TTS_INIT, TTS_DATA = 0x00030001, 0x00030006, 0x00040001, 0x00040003


class _PcmOut:
    """Один поток вывода. Нить приёма только кладёт PCM в буфер: в режиме stream один сокет на всё, блокирующий
    write задержал бы видео. Буфер больше 1 с режем с головы - лучше пропуск, чем растущая задержка."""

    def __init__(self, sd, rate: int, ch: int):
        self.buf = bytearray()
        self.lock = threading.Lock()
        self.cap = rate * ch * 2               # 1 с PCM 16 бит
        self.stream = sd.RawOutputStream(samplerate=rate, channels=ch, dtype="int16", callback=self._cb)
        self.stream.start()

    def _cb(self, out, frames, _time, _status):
        n = len(out)
        with self.lock:
            k = min(n, len(self.buf))
            out[:k] = self.buf[:k]
            del self.buf[:k]
        if k < n:
            out[k:] = b"\x00" * (n - k)

    def write(self, pcm: bytes):
        with self.lock:
            self.buf += pcm
            if len(self.buf) > self.cap:
                del self.buf[:len(self.buf) - self.cap]

    def close(self):
        self.stream.close()


class Speakers:
    """--view: PCM каналов MEDIA и TTS (голос навигатора) в динамики ПК, как у настоящей ГУ.
    Модуль sounddevice необязателен: нет его - одна строка в лог и тишина."""

    def __init__(self, log):
        self.log = log
        self.outs: dict[int, _PcmOut] = {}
        try:
            import sounddevice
            self.sd = sounddevice
        except Exception as e:  # noqa: BLE001 - любой сбой импорта = без звука
            self.sd = None
            log(f"speakers: no sound ({e}); pip install sounddevice")

    def on(self, chan: int, mtype: int, body: bytes):
        if self.sd is None:
            return
        if mtype in (MEDIA_INIT, TTS_INIT):
            f = pb_parse(body)
            rate, ch = pb_get_int(f, 1) or 48000, pb_get_int(f, 2) or 2
            old = self.outs.pop(chan, None)
            if old:
                old.close()
            if not self.outs:
                # PortAudio запоминает устройство по умолчанию при старте: сменили его в Windows - перечитать
                try:
                    self.sd._terminate()
                    self.sd._initialize()
                except Exception as e:  # noqa: BLE001
                    self.log(f"speakers: PortAudio reinit failed: {e}")
            try:
                self.outs[chan] = _PcmOut(self.sd, rate, ch)
                self.log(f"speakers: {CH_NAMES[chan]} {rate} Hz {ch} ch -> {self.sd.query_devices(kind='output')['name']}")
            except Exception as e:  # noqa: BLE001
                self.log(f"speakers: {CH_NAMES[chan]} open failed: {e}")
        elif mtype in (MEDIA_DATA, TTS_DATA):
            out = self.outs.get(chan)
            if out:
                out.write(body)

    def close(self):
        for out in self.outs.values():
            out.close()
        self.outs.clear()


# ---- кадры ----

def outer(chan: int, inner: bytes) -> bytes:
    """Внешний кадр: chan u32 BE, len u32 BE, затем inner."""
    return struct.pack(">II", chan, len(inner)) + inner


def cmd_inner(mtype: int, body: bytes = b"") -> bytes:
    """Inner для CMD/TOUCH: len u16 BE, reserved u16 = 0, type u32 BE."""
    if len(body) > 0xFFFF:
        raise ValueError(f"CMD body too long: {len(body)}")
    return struct.pack(">HHI", len(body), 0, mtype) + body


def data_inner(mtype: int, body: bytes = b"", time_: int = 0) -> bytes:
    """Inner для VIDEO/MEDIA/TTS/VR: len u32 BE, time u32 BE, type u32 BE."""
    return struct.pack(">III", len(body), time_, mtype) + body


def nal_types(data: bytes) -> list[int]:
    """Типы NAL-единиц Annex-B буфера (старт-коды 00 00 01 / 00 00 00 01): 5 = IDR, 7/8 = SPS/PPS."""
    types = []
    p = data.find(b"\x00\x00\x01")
    while p >= 0 and p + 3 < len(data):
        types.append(data[p + 3] & 0x1F)
        p = data.find(b"\x00\x00\x01", p + 3)
    return types


def is_cmd_style(chan: int) -> bool:
    return chan in (CH_CMD, CH_TOUCH)


def parse_inner(chan: int, inner: bytes):
    """-> (type, body, truncated) или None, если заголовок неполный."""
    if is_cmd_style(chan):
        if len(inner) < 8:
            return None
        n, _resv, mtype = struct.unpack(">HHI", inner[:8])
        avail = len(inner) - 8
        return mtype, inner[8:8 + min(n, avail)], n > avail
    if len(inner) < 12:
        return None
    n, _t, mtype = struct.unpack(">III", inner[:12])
    avail = len(inner) - 12
    return mtype, inner[12:12 + min(n, avail)], n > avail


class FrameParser:
    """Накопительный разбор внешних кадров из потока TCP."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        """-> список (chan, inner) по всем целым кадрам. ValueError на битом заголовке."""
        self.buf += data
        out = []
        while len(self.buf) >= 8:
            chan, n = struct.unpack(">II", self.buf[:8])
            if chan > 0xFF or n > MAX_OUTER_LEN:
                raise ValueError(f"bad outer header: chan={chan} len={n} {bytes(self.buf[:16]).hex(' ')}")
            if len(self.buf) < 8 + n:
                break
            out.append((chan, bytes(self.buf[8:8 + n])))
            del self.buf[:8 + n]
        return out


class InnerStreamParser:
    """Накопительный разбор inner-сообщений одного канала (режим tcp6: свой сокет на канал, без внешнего заголовка)."""

    def __init__(self, chan: int):
        self.chan = chan
        self.cmd_style = is_cmd_style(chan)
        self.hdr = 8 if self.cmd_style else 12
        self.max_len = MAX_CMD_LEN if self.cmd_style else MAX_DATA_LEN
        self.buf = bytearray()

    def feed(self, data: bytes):
        """-> список целых inner (заголовок + тело). ValueError на битом заголовке."""
        self.buf += data
        out = []
        while len(self.buf) >= self.hdr:
            if self.cmd_style:
                n, _resv, mtype = struct.unpack(">HHI", self.buf[:8])
            else:
                n, _t, mtype = struct.unpack(">III", self.buf[:12])
            if n > self.max_len or mtype >> 16 != self.chan:
                raise ValueError(f"bad inner header on {CH_NAMES.get(self.chan, self.chan)}: len={n} "
                                 f"type=0x{mtype:08X} {bytes(self.buf[:16]).hex(' ')}")
            total = self.hdr + n
            if len(self.buf) < total:
                break
            out.append(bytes(self.buf[:total]))
            del self.buf[:total]
        return out


# ---- сообщения ГУ ----

def build_protocol_version() -> bytes:
    return cmd_inner(HU_PROTOCOL_VERSION, pb_int(1, 4) + pb_int(2, 2))


# Профили ГУ (--hu): channel в STATISTIC_INFO, поля 13/14 HU_INFO (производитель/модель), экран по умолчанию.
# mazda - Desay G6S из Mazda CX-5: мост узнаёт его по каналу 20622116 и паре «Desay SV Automotive»/«G6S» (huDesayG6S).
HU_PROFILES = {
    "honda": {"channel": "21580005", "manufacturer": "SEMIDRIVE", "model": "x9 for arm64", "size": (1920, 720)},
    "mazda": {"channel": "20622116", "manufacturer": "Desay SV Automotive", "model": "G6S", "size": (1728, 648)},
}


def build_statistic_info(channel: str = "21580005") -> bytes:
    body = (pb_str(1, "304AC406514A") + pb_str(2, "v2.3.4") + pb_int(3, 2055) + pb_str(4, channel)
            + pb_int(5, 0) + pb_int(6, 0) + pb_int(7, 0))
    return cmd_inner(STATISTIC_INFO, body)


HU_INFO_FIELDS = [
    (1, "Android"), (2, "honda_3ya"), (3, "unknown"), (4, "X9"), (5, "arm64-v8a"), (6, ""),
    (7, "honda_3ya"), (8, "01.603.002"),
    (9, "X9/honda_3ya/honda_3ya:12/01.603.002_CXIC_20251205_D/gerritad.20251205.174324:user/dev-keys"),
    (10, "honda_3ya"), (11, "d1ec549ff954"), (12, "01.603.002_CXIC_20251205_D"), (13, "SEMIDRIVE"),
    (14, "x9 for arm64"), (15, "honda_3ya"), (16, "unknown"), (17, "REL"), (18, "gerritad.20251205.174324"),
    (19, "12"), (20, "31"),
]


def build_hu_info(profile: str = "honda") -> bytes:
    p = HU_PROFILES[profile]
    fields = dict(HU_INFO_FIELDS) | {13: p["manufacturer"], 14: p["model"]}
    body = b"".join(pb_str(f, v) for f, v in fields.items()) + pb_int(21, 31) + pb_str(23, "yyy-zzz-xxx") + pb_str(24, "v2.3.4")
    return cmd_inner(HU_INFO, body)


def build_auth_request(random_value: str) -> bytes:
    return cmd_inner(HU_AUTHEN_REQUEST, pb_str(1, random_value))


def make_random_value(now: datetime | None = None) -> str:
    return "2.0;" + (now or datetime.now()).strftime("%Y-%m-%d-%H-%M-%S")


def build_video_encoder_init(w: int = 1920, h: int = 720, fps: int = 30) -> bytes:
    # поле 4 в Schemas.videoEncoderInfo нет, ГУ шлёт 2
    return cmd_inner(VIDEO_ENCODER_INIT, pb_int(1, w) + pb_int(2, h) + pb_int(3, fps) + pb_int(4, 2))


FEATURES = [("I_FRAME_INTERVAL", 300), ("USB_MTU", 16384), ("MUSIC_HUD", 1), ("BLUETOOTH_AUTO_CONNECT", 0),
            ("FOCUS_UI", 0), ("VOICE_WAKEUP", 1), ("VOICE_MIC", 0), ("CONNECT_TYPE", 2),
            ("BLUETOOTH_INTERNAL_UI", 0)]


def build_feature_config() -> bytes:
    body = pb_int(1, len(FEATURES))
    for k, v in FEATURES:
        body += pb_bytes(2, pb_str(1, k) + pb_int(2, v))
    return cmd_inner(HU_FEATURE_CONFIG_RESPONSE, body)


def build_auth_result(ok: bool) -> bytes:
    return cmd_inner(HU_AUTHEN_RESULT, pb_int(1, 1 if ok else 0))


def build_touch(action: int, x: int, y: int) -> bytes:
    return cmd_inner(TOUCH_ACTION, pb_int(1, action) + pb_int(2, x) + pb_int(3, y))


def build_hard_key(code: int) -> bytes:
    return cmd_inner(TOUCH_CAR_HARD_KEY_CODE, pb_int(1, code))


def build_heartbeat() -> bytes:
    return data_inner(VIDEO_HEARTBEAT)


def auth_expected(brand: str, model: str, sdk: str, rnd: str) -> str:
    """md5hex("CARLIFE" + brand + model + sdk + rnd), строчный hex (HuAuth.verifyCode)."""
    return hashlib.md5(("CARLIFE" + brand + model + sdk + rnd).encode("utf-8")).hexdigest()


# ---- сессия ----

class Options:
    def __init__(self, host="127.0.0.1", port=7240, duration=120.0, no_auth=False, touch_test=False,
                 out: Path | None = None, quiet=False, transport="stream", ports: dict[int, int] | None = None,
                 kill_chan: str | None = None, kill_after=10.0, extra_data_socket=False,
                 discover=False, beacon_port=BEACON_PORT, bind_ip="0.0.0.0", discover_timeout=60.0,
                 probe_8999=False, probe_port=PROBE_PORT,
                 video_kbps=0, dip_kbps=0, dip_at=20.0, dip_for=5.0,
                 pause_cycles=0, pause_every=10.0, pause_for=3.0,
                 size: tuple[int, int] = DEFAULT_SIZE, hu: str = "honda"):
        self.host, self.port, self.duration = host, port, duration
        self.hu = hu                                        # профиль ГУ: HU_PROFILES (канал, производитель, кнопки)
        self.size = size                                    # какой экран ГУ объявляем в VIDEO_ENCODER_INIT
        # циклы паузы: pause_every с от первого кадра (потом от каждого START) шлём PAUSE, через pause_for с - START
        self.pause_cycles, self.pause_every, self.pause_for = pause_cycles, pause_every, pause_for
        # шейпер видео (tcp6): ГУ читает video-сокет не быстрее video_kbps (0 = без ограничения), а с dip_at по
        # dip_at+dip_for секунд от первого кадра - не быстрее dip_kbps (0 = без провала). Обратное давление настоящее, TCP.
        self.video_kbps, self.dip_kbps, self.dip_at, self.dip_for = video_kbps, dip_kbps, dip_at, dip_for
        self.no_auth, self.touch_test = no_auth, touch_test
        self.out = out or HERE / "out"
        self.quiet = quiet
        self.transport = transport                          # "stream" (один сокет, внешний заголовок) или "tcp6"
        self.ports = dict(ports or DEFAULT_PORTS)           # tcp6: канал -> порт на телефоне
        self.kill_chan = kill_chan.upper() if kill_chan else None
        self.kill_after = kill_after
        self.extra_data_socket = extra_data_socket
        # ветка B (телефон раздаёт хотспот): host телефона берём из адреса отправителя UDP-маяка
        self.discover = discover
        self.beacon_port = beacon_port                      # порт приёма маяка; 0 = эфемерный (тесты)
        self.bind_ip = bind_ip                              # на какой карте слушать маяк
        self.discover_timeout = discover_timeout
        self.probe_8999 = probe_8999
        self.probe_port = probe_port
        if self.transport not in ("stream", "tcp6"):
            raise ValueError(f"unknown transport {self.transport!r}")
        if self.discover and self.transport != "tcp6":
            raise ValueError("discover works only with transport tcp6")
        if self.probe_8999 and not self.discover:
            raise ValueError("probe_8999 needs discover")
        if (self.video_kbps or self.dip_kbps) and self.transport != "tcp6":
            raise ValueError("video shaping works only with transport tcp6")
        if self.kill_chan and self.kill_chan not in CH_NAMES.values():
            raise ValueError(f"unknown channel {self.kill_chan!r}")
        if self.pause_cycles < 0 or self.pause_every <= 0 or self.pause_for < 0:
            raise ValueError("pause_cycles >= 0, pause_every > 0, pause_for >= 0 expected")


class HeadUnit:
    AUTH_TIMEOUT = 10.0
    TOUCH_DELAY = 15.0
    CONNECT_TIMEOUT = 5.0
    SHAPE_RCVBUF = 32 * 1024
    SHAPE_CHUNK = 4096
    KILL_WAIT = 10.0                # tcp6: сколько ждать, пока мост сам закроет остальные сокеты
    PROBE_WAIT = 2.0                # --probe-8999: сколько ждать ответ

    def __init__(self, opts: Options):
        self.o = opts
        self.tcp6 = opts.transport == "tcp6"
        self.srv: socket.socket | None = None
        self.conn: socket.socket | None = None
        self.port = opts.port
        self.wlock = threading.Lock()
        self.loglock = threading.Lock()
        # tcp6: сокет и замок записи на канал; extra — Data-канал (только держим открытым)
        self.socks: dict[int, socket.socket] = {}
        self.locks: dict[int, threading.Lock] = {}
        self.extra: socket.socket | None = None
        self.threads: list[threading.Thread] = []
        self.kill_num = next((c for c, n in CH_NAMES.items() if n == opts.kill_chan), None)
        self.killed = False
        self.kill_at: float | None = None
        self.closed_others_after: float | None = None
        self.logf = None
        self.stop = threading.Event()
        self.stop_reason = ""
        self.counts: Counter = Counter()
        self.brand = self.model = self.sdk = ""
        self.got_md_info = False
        self.auth_random: str | None = None
        self.auth_sent_at: float | None = None
        self.auth_status = "NOT SENT" if opts.no_auth else "NO REQUEST YET"
        self.frames = 0
        self.vbytes = 0
        self.shape_t = 0.0
        self.shape_kbps = 0
        self.first_frame_at: float | None = None
        self.last_frame_at: float | None = None
        self.vid_size = opts.size                   # до INIT_DONE моста - то, что просили мы; потом - выбор моста
        # живое окно (--view): sink-и зовутся с нитей приёма и не должны блокировать; session_up - можно слать касания
        self.video_sink = None                      # callable(bytes): тело VIDEO_DATA
        self.size_sink = None                       # callable(w, h): размер из INIT_DONE моста
        self.speakers: Speakers | None = None       # --view: MEDIA/TTS в динамики ПК (ставит main)
        self.session_up = threading.Event()
        self.vfile = None
        self.t0 = 0.0
        self.host = opts.host                       # tcp6: при --discover подменяется адресом отправителя маяка
        self.bsock: socket.socket | None = None     # UDP-сокет приёма маяка
        self.beacon_port = opts.beacon_port         # настоящий порт (beacon_port=0 даёт свободный)
        self.beacons = 0
        self.discovered_host: str | None = None
        self.probe_reply: bool | None = None
        # циклы паузы: фаза "wait" (идёт видео, ждём следующий PAUSE) или "paused"; замки: on_video зовётся из потоков чтения
        self.plock = threading.Lock()
        self.pause_phase = "wait"
        self.pause_k = 0                            # сколько PAUSE уже послано
        self.pause_next_at: float | None = None
        self.pause_cur: dict | None = None          # текущий цикл; после START в нём же копятся замеры
        self.pause_results: list[dict] = []         # циклы, у которых послан START

    def log(self, msg: str):
        line = f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}"
        with self.loglock:
            if not self.o.quiet:
                print(line, flush=True)
            if self.logf:
                self.logf.write(line + "\n")
                self.logf.flush()

    def listen(self):
        """Открыть порт заранее (port=0 даёт свободный; self.port хранит настоящий)."""
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind((self.o.host, self.o.port))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]

    def send(self, chan: int, inner: bytes, label: str = ""):
        """stream: outer(chan, inner) в единственный сокет; tcp6: голый inner в сокет канала."""
        if self.tcp6:
            if self.killed and chan == self.kill_num:
                return                                      # сокет закрыт нами, писать некуда
            try:
                with self.locks[chan]:
                    self.socks[chan].sendall(inner)
            except OSError as e:
                if not self.killed:
                    raise
                # после kill мост мог уже закрыть остальные сокеты: это не ошибка записи
                self.peer_gone(chan, f"write error: {e}")
                return
        else:
            assert self.conn
            with self.wlock:
                self.conn.sendall(outer(chan, inner))
        if label:
            self.log(f"HU -> {CH_NAMES.get(chan, chan)} {label}")

    def send_cmd(self, mtype: int, inner: bytes, extra: str = ""):
        body = inner[8:]
        self.send(CH_CMD, inner, f"{msg_name(mtype)} 0x{mtype:08X} len={len(body)} {extra or pb_render(body)}")

    # --- приём от моста ---

    def on_message(self, chan: int, inner: bytes):
        p = parse_inner(chan, inner)
        if p is None:
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} short inner len={len(inner)} {inner.hex(' ')}")
            self.counts[(chan, -1)] += 1
            return
        mtype, body, trunc = p
        if chan == CH_VIDEO and mtype == VIDEO_DATA:
            self.on_video(body)
            return
        if self.speakers and chan in (CH_MEDIA, CH_TTS):
            self.speakers.on(chan, mtype, body)
        self.counts[(chan, mtype)] += 1
        extra = " TRUNCATED" if trunc else ""
        name = msg_name(mtype)
        if chan in (CH_CMD, CH_TOUCH):
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} {name} 0x{mtype:08X} len={len(body)}{extra} {pb_render(body)}")
        else:
            self.log(f"MD -> {CH_NAMES.get(chan, chan)} {name} 0x{mtype:08X} len={len(body)}{extra}")
        if chan != CH_CMD:
            return
        if mtype == MD_INFO:
            self.on_md_info(body)
        elif mtype == MD_AUTHEN_RESPONSE:
            self.on_auth_response(body)
        elif mtype == VIDEO_ENCODER_INIT_DONE:
            f = pb_parse(body)
            w, h = pb_get_int(f, 1), pb_get_int(f, 2)
            if w and h:
                self.vid_size = (w, h)
                note = "same as asked" if (w, h) == tuple(self.o.size) else f"the HU asked {self.o.size[0]}x{self.o.size[1]}"
                self.log(f"video size from INIT_DONE: {w}x{h} ({note})")
                if self.size_sink:
                    self.size_sink(w, h)

    def on_md_info(self, body: bytes):
        f = pb_parse(body)
        self.brand = pb_get_str(f, 4) or ""
        self.model = pb_get_str(f, 14) or ""
        self.sdk = pb_get_str(f, 20) or ""
        self.log(f"MD_INFO: brand={self.brand!r} model={self.model!r} sdk={self.sdk!r}")
        if self.got_md_info:
            return
        self.got_md_info = True
        self.send_init_burst()

    def send_init_burst(self):
        self.send_cmd(STATISTIC_INFO, build_statistic_info(HU_PROFILES[self.o.hu]["channel"]))
        self.send_cmd(HU_INFO, build_hu_info(self.o.hu))
        if not self.o.no_auth:
            self.auth_random = make_random_value()
            self.send_cmd(HU_AUTHEN_REQUEST, build_auth_request(self.auth_random))
            self.auth_sent_at = time.monotonic()
            self.auth_status = "PENDING"
        self.send_cmd(HU_UNKNOWN_1804C, cmd_inner(HU_UNKNOWN_1804C))
        self.send_cmd(VIDEO_ENCODER_INIT, build_video_encoder_init(*self.o.size))
        self.send_cmd(HU_FEATURE_CONFIG_RESPONSE, build_feature_config())
        self.send_cmd(VIDEO_ENCODER_START, cmd_inner(VIDEO_ENCODER_START))

    def on_auth_response(self, body: bytes):
        got = pb_get_str(pb_parse(body), 1) or ""
        if self.auth_random is None:
            self.log("MD_AUTHEN_RESPONSE without our request, ignored")
            return
        rnd = self.auth_random.split(";", 1)[1] if ";" in self.auth_random else self.auth_random
        expected = auth_expected(self.brand, self.model, self.sdk, rnd)
        ok = got == expected
        if ok:
            self.log("AUTH OK")
        else:
            self.log(f"AUTH FAIL expected={expected} got={got}")
        self.auth_status = "AUTH OK" if ok else "AUTH FAIL"
        self.auth_random = None
        self.auth_sent_at = None
        self.send_cmd(HU_AUTHEN_RESULT, build_auth_result(ok))

    def on_video(self, body: bytes):
        now = time.monotonic()
        if self.first_frame_at is None:
            self.first_frame_at = now
            self.log(f"first video frame, {len(body)} B, head {body[:16].hex(' ')}")
        self.last_frame_at = now
        self.frames += 1
        self.vbytes += len(body)
        if self.vfile:
            self.vfile.write(body)
        if self.video_sink:
            self.video_sink(body)
        if self.o.pause_cycles:
            self.pause_on_video(now, body)

    # --- циклы паузы ---

    def pause_on_video(self, now: float, body: bytes):
        """Кадр в фазе паузы - нарушение (мост на PAUSE слать не должен); после START - замер первого кадра и IDR."""
        msg = None
        with self.plock:
            c = self.pause_cur
            if c is None:
                return
            if self.pause_phase == "paused":
                c["paused_frames"] += 1
                if c["paused_frames"] == 1:
                    msg = (f"pause #{c['k']}: video frame DURING pause, {(now - c['pause_t']) * 1000:.0f} ms after PAUSE, "
                           f"{len(body)} B")
            elif c["start_t"] is not None:
                ms = (now - c["start_t"]) * 1000
                if c["first_ms"] is None:
                    c["first_ms"] = ms
                if c["idr_ms"] is None and 5 in nal_types(body):
                    c["idr_ms"] = ms
                    msg = f"pause #{c['k']}: IDR {ms:.0f} ms after START"
        if msg:
            self.log(msg)

    def pause_tick(self, now: float):
        """Из loop(): отсчёт от первого кадра, PAUSE -> ждать pause_for -> START -> снова ждать pause_every."""
        if self.pause_phase == "wait":
            if self.pause_k >= self.o.pause_cycles:
                return
            if self.pause_next_at is None:
                if self.first_frame_at is None:
                    return
                self.pause_next_at = self.first_frame_at + self.o.pause_every
            if now < self.pause_next_at:
                return
            self.pause_k += 1
            with self.plock:
                self.pause_cur = {"k": self.pause_k, "paused_frames": 0, "pause_t": time.monotonic(), "start_t": None,
                                  "first_ms": None, "idr_ms": None}
                self.pause_phase = "paused"
            self.send_cmd(VIDEO_ENCODER_PAUSE, cmd_inner(VIDEO_ENCODER_PAUSE))
        else:
            c = self.pause_cur
            if now - c["pause_t"] < self.o.pause_for:
                return
            with self.plock:
                c["start_t"] = time.monotonic()
                self.pause_phase = "wait"
                self.pause_results.append(c)
                self.pause_next_at = c["start_t"] + self.o.pause_every
            self.send_cmd(VIDEO_ENCODER_START, cmd_inner(VIDEO_ENCODER_START))

    def pause_summary(self) -> dict:
        """Строки итога по циклам паузы -> dict для результата; пишет в лог."""
        res = self.pause_results
        total = sum(c["paused_frames"] for c in res)
        c = self.pause_cur
        if self.pause_phase == "paused" and c is not None:       # сессия кончилась посреди паузы
            total += c["paused_frames"]
            self.log(f"pause #{c['k']}: session ended during pause, paused_frames={c['paused_frames']}")

        def fmt(ms):
            return "none" if ms is None else f"{ms:.0f} ms"

        for c in res:
            self.log(f"pause #{c['k']}: paused_frames={c['paused_frames']}, start->first_frame={fmt(c['first_ms'])}, "
                     f"start->idr={fmt(c['idr_ms'])}")
        idrs = [c["idr_ms"] for c in res if c["idr_ms"] is not None]
        no_idr = len(res) - len(idrs)
        line = f"pause cycles: {len(res)}, worst start->idr {fmt(max(idrs) if idrs else None)}"
        if no_idr:
            line += f" (no IDR in {no_idr} cycles)"
        self.log(line + f", frames during pause total {total}")
        return {"cycles": [{k: c[k] for k in ("k", "paused_frames", "first_ms", "idr_ms")} for c in res],
                "worst_idr_ms": max(idrs) if idrs else None, "no_idr": no_idr, "paused_frames_total": total}

    # --- потоки ---

    def reader(self):
        parser = FrameParser()
        conn = self.conn
        try:
            while not self.stop.is_set():
                r, _, _ = select.select([conn], [], [], 0.2)
                if not r:
                    continue
                data = conn.recv(65536)
                if not data:
                    self.finish("bridge closed the connection")
                    return
                for chan, inner in parser.feed(data):
                    self.on_message(chan, inner)
        except (OSError, ValueError) as e:
            if not self.stop.is_set():
                self.finish(f"read error: {e}")

    def reader_tcp6(self, chan: int, sock: socket.socket, parser: InnerStreamParser | None):
        """Поток чтения одного сокета tcp6 (parser=None у Data-канала: только ждём EOF)."""
        name = CH_NAMES.get(chan, "DATA")
        try:
            while not self.stop.is_set():
                if self.killed and chan == self.kill_num:
                    return
                r, _, _ = select.select([sock], [], [], 0.2)
                if not r:
                    continue
                shaped = chan == CH_VIDEO and self.shaping
                data = sock.recv(self.SHAPE_CHUNK if shaped else 65536)
                if shaped and data:
                    self.shape_wait(len(data))
                if not data:
                    if self.killed and chan == self.kill_num:
                        return
                    self.log(f"{name} socket closed by the bridge")
                    self.peer_gone(chan, "bridge closed the connection")
                    return
                if parser:
                    for inner in parser.feed(data):
                        self.on_message(chan, inner)
        except (OSError, ValueError) as e:
            if self.stop.is_set() or (self.killed and chan == self.kill_num):
                return
            self.peer_gone(chan, f"read error on {name}: {e}")

    @property
    def shaping(self) -> bool:
        return bool(self.o.video_kbps or self.o.dip_kbps)

    def video_limit_kbps(self, now: float) -> int:
        """Текущий предел чтения видео, кбит/с (0 = без ограничения)."""
        if self.o.dip_kbps and self.first_frame_at is not None:
            t = now - self.first_frame_at
            if self.o.dip_at <= t < self.o.dip_at + self.o.dip_for:
                return self.o.dip_kbps
        return self.o.video_kbps

    def shape_wait(self, n: int):
        """Шейпер: после n прочитанных байт спать, чтобы средняя скорость чтения не превышала предел."""
        now = time.monotonic()
        kbps = self.video_limit_kbps(now)
        if kbps != self.shape_kbps:
            self.log(f"shaper: video read limit {self.shape_kbps or 'none'} -> {kbps or 'none'} kbit/s")
            self.shape_kbps = kbps
        if not kbps:
            self.shape_t = now
            return
        self.shape_t = max(self.shape_t, now) + n * 8 / (kbps * 1000)
        delay = self.shape_t - now
        if delay > 0:
            time.sleep(delay)

    def peer_gone(self, chan: int, why: str):
        """Мост закрыл (или порвал) сокет канала. После kill это ожидаемый ответ моста на потерю канала."""
        if self.killed:
            if self.closed_others_after is None and self.kill_at is not None:
                self.closed_others_after = time.monotonic() - self.kill_at
            self.finish(f"bridge closed the connection (after kill {self.o.kill_chan})")
        else:
            self.finish(why)

    def finish(self, reason: str):
        if not self.stop.is_set():
            self.stop_reason = reason
            self.stop.set()

    def open_stream(self) -> bool:
        """stream: слушать порт и принять одно соединение."""
        if self.srv is None:
            self.listen()
        self.log(f"listening on {self.o.host}:{self.port}")
        self.srv.settimeout(0.5)                    # короткие паузы: Ctrl+C и закрытие окна не ждут мост вечно
        while True:
            if self.stop.is_set():
                return False
            try:
                self.conn, addr = self.srv.accept()
                break
            except socket.timeout:
                continue
        self.conn.settimeout(None)
        self.conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.log(f"bridge connected from {addr[0]}:{addr[1]}")
        return True

    def beacon_listen(self):
        """Открыть UDP-порт маяка заранее (beacon_port=0 даёт свободный; self.beacon_port хранит настоящий)."""
        self.bsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.bsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.bsock.bind((self.o.bind_ip, self.o.beacon_port))
        self.beacon_port = self.bsock.getsockname()[1]

    def recv_beacon(self, timeout: float) -> tuple[str, int] | None:
        """Одна датаграмма с маяком за timeout секунд: логирует и считает, возвращает адрес отправителя."""
        self.bsock.settimeout(max(timeout, 0.001))
        try:
            data, addr = self.bsock.recvfrom(2048)
        except (socket.timeout, BlockingIOError):
            return None
        self.beacons += 1
        kind = "CarlifeHost" if BEACON_PAYLOAD in data else "other"
        self.log(f"beacon from {addr[0]}:{addr[1]} len={len(data)} payload={kind}")
        return addr[0], addr[1]

    def wait_beacon(self, timeout: float) -> tuple[str, int] | None:
        """Ждать маяк до timeout секунд, вернуть адрес отправителя первой датаграммы или None."""
        if self.bsock is None:
            self.beacon_listen()
        self.log(f"waiting for beacon on {self.o.bind_ip}:{self.beacon_port} (timeout {timeout:g} s)")
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            try:
                addr = self.recv_beacon(min(left, 0.5))     # короткие паузы: Ctrl+C проходит и на Windows
            except ConnectionResetError:
                continue                                    # Windows: ICMP от чужой отправки, не ошибка
            if addr:
                return addr

    def probe(self, ip: str):
        """Одна UDP-датаграмма на <ip>:probe_port и до PROBE_WAIT секунд ожидания ответа (необязателен)."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.settimeout(self.PROBE_WAIT)
            s.sendto(PROBE_REQUEST, (ip, self.o.probe_port))
            data, _ = s.recvfrom(4096)
            self.probe_reply = True
            self.log(f"probe {self.o.probe_port}: reply {data[:64]!r}")
        except OSError:                                     # timeout и ConnectionReset (порт закрыт) = ответа нет
            self.probe_reply = False
            self.log(f"probe {self.o.probe_port}: no reply")
        finally:
            s.close()

    def discover(self) -> bool:
        """Ветка B: ждать UDP-маяк, host = адрес отправителя; по желанию проба 8999. False, если маяка нет."""
        try:
            addr = self.wait_beacon(self.o.discover_timeout)
        except KeyboardInterrupt:
            self.finish("Ctrl+C")
            return False
        if addr is None:
            self.finish(f"no beacon within {self.o.discover_timeout:g} s")
            self.log(self.stop_reason)
            return False
        self.discovered_host = self.host = addr[0]
        self.log(f"discovered phone at {self.host}")
        if self.o.probe_8999:
            self.probe(self.host)
        try:
            while self.recv_beacon(0.001):                  # уже накопившиеся маяки: только посчитать
                pass
        except OSError:
            pass
        return True

    def open_tcp6(self) -> bool:
        """tcp6: подключиться к шести портам телефона по порядку каналов (плюс Data по желанию)."""
        self.log(f"connecting to {self.host} (tcp6)")
        targets = [(c, CH_NAMES[c], self.o.ports[c]) for c in sorted(CH_NAMES)]
        if self.o.extra_data_socket:
            targets.append((0, "DATA", DATA_PORT))
        for chan, name, port in targets:
            try:
                if chan == CH_VIDEO and self.shaping:
                    # малое окно приёма до connect: иначе шейпер копит секунды видео в буфере ПК, давление до телефона не доходит
                    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, self.SHAPE_RCVBUF)
                    s.settimeout(self.CONNECT_TIMEOUT)
                    s.connect((self.host, port))
                else:
                    s = socket.create_connection((self.host, port), timeout=self.CONNECT_TIMEOUT)
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.settimeout(None)
            except OSError as e:
                self.finish(f"connect {name} :{port} failed: {e}")
                self.log(f"connect {name} :{port} failed: {e}")
                return False
            if chan:
                self.socks[chan] = s
                self.locks[chan] = threading.Lock()
            else:
                self.extra = s
            self.log(f"connected {name} :{port}")
        return True

    def start_readers(self):
        if self.tcp6:
            for chan, sock in self.socks.items():
                self.threads.append(threading.Thread(target=self.reader_tcp6, args=(chan, sock, InnerStreamParser(chan)),
                                                     daemon=True))
            if self.extra:
                self.threads.append(threading.Thread(target=self.reader_tcp6, args=(0, self.extra, None), daemon=True))
        else:
            self.threads.append(threading.Thread(target=self.reader, daemon=True))
        for t in self.threads:
            t.start()

    def all_socks(self) -> list[socket.socket]:
        socks = ([self.conn] if self.conn else []) + list(self.socks.values()) + ([self.extra] if self.extra else [])
        return socks

    def close_all(self):
        for s in self.all_socks():
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()

    def kill_socket(self):
        """Fault injection (tcp6): закрыть один сокет, остальные оставить открытыми."""
        sock = self.socks[self.kill_num]
        self.log(f"fault injection: closing {self.o.kill_chan} socket, the others stay open")
        self.kill_at = time.monotonic()
        self.killed = True
        with self.locks[self.kill_num]:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()

    def run(self) -> dict:
        """Принять соединение (stream) или подключиться к шести портам (tcp6), отработать сессию, вернуть итог (dict)."""
        self.o.out.mkdir(parents=True, exist_ok=True)
        self.logf = open(self.o.out / "carlife_hu.log", "w", encoding="utf-8")
        self.vfile = open(self.o.out / "carlife_video.h264", "wb")
        try:
            found = self.discover() if self.o.discover else True
            if self.bsock:
                self.bsock.close()
                self.bsock = None
            if found and (self.open_tcp6() if self.tcp6 else self.open_stream()):
                self.t0 = time.monotonic()
                self.send_cmd(HU_PROTOCOL_VERSION, build_protocol_version())
                self.session_up.set()
                self.start_readers()
                try:
                    self.loop()
                except KeyboardInterrupt:
                    self.finish("Ctrl+C")
                except OSError as e:
                    self.finish(f"write error: {e}")
                self.stop.set()
                self.close_all()
                for t in self.threads:
                    t.join(2)
        finally:
            self.close_all()
            self.vfile.close()
            if self.srv:
                self.srv.close()
            if self.bsock:
                self.bsock.close()
        res = self.summary()
        self.logf.close()
        return res

    def loop(self):
        next_hb = time.monotonic()
        next_fps = time.monotonic() + 5
        fps_frames = 0
        fps_bytes = 0
        touched = False
        while not self.stop.is_set():
            now = time.monotonic()
            if now - self.t0 >= self.o.duration:
                self.finish("duration elapsed")
                break
            if now >= next_hb:
                self.send(CH_VIDEO, build_heartbeat())
                next_hb += 1.0
            if self.auth_sent_at is not None and now - self.auth_sent_at >= self.AUTH_TIMEOUT:
                self.auth_status = "AUTH NO RESPONSE"
                self.auth_sent_at = None
                self.log("AUTH NO RESPONSE")
            if now >= next_fps:
                n = self.frames - fps_frames
                kbps = (self.vbytes - fps_bytes) * 8 / 5 / 1000
                fps_frames = self.frames
                fps_bytes = self.vbytes
                next_fps += 5
                self.log(f"video: {self.frames} frames, {self.vbytes} B total, {n / 5:.1f} fps, {kbps:.0f} kbit/s (last 5 s)")
            if self.o.pause_cycles:
                self.pause_tick(now)
            if (self.o.touch_test and not touched and self.first_frame_at is not None
                    and now - self.first_frame_at >= self.TOUCH_DELAY):
                touched = True
                self.send_tap()
            if self.kill_num is not None:
                if (not self.killed and self.first_frame_at is not None
                        and now - self.first_frame_at >= self.o.kill_after):
                    self.kill_socket()
                elif (self.killed and self.closed_others_after is None
                        and now - self.kill_at >= self.KILL_WAIT):
                    self.finish(f"bridge did NOT close the other sockets within {self.KILL_WAIT:g} s after kill "
                                f"{self.o.kill_chan}")
            self.stop.wait(0.05)

    def send_tap(self):
        x, y = self.vid_size[0] // 2, self.vid_size[1] // 2
        self.log(f"touch test: tap at {x},{y} ({self.vid_size[0]}x{self.vid_size[1]})")
        self.send(CH_TOUCH, build_touch(0, x, y), f"TOUCH_ACTION DOWN {x},{y}")
        time.sleep(0.1)
        self.send(CH_TOUCH, build_touch(1, x, y), f"TOUCH_ACTION UP {x},{y}")

    def send_touch_event(self, action: int, x: int, y: int):
        """Касание ГУ в пикселях видео (с нити окна). MOVE без записи в лог: их десятки в секунду."""
        if not self.session_up.is_set() or self.stop.is_set():
            return
        name = {TOUCH_DOWN: "DOWN", TOUCH_UP: "UP", TOUCH_MOVE: "MOVE"}.get(action, str(action))
        try:
            self.send(CH_TOUCH, build_touch(action, x, y), "" if action == TOUCH_MOVE else f"TOUCH_ACTION {name} {x},{y}")
        except OSError as e:
            self.finish(f"write error: {e}")

    def send_voice_key(self):
        """Кнопка голоса как у Vezel: MODULE_CONTROL moduleID=4 (VR) statusID=1, не hard key."""
        if not self.session_up.is_set() or self.stop.is_set():
            return
        try:
            self.send_cmd(MODULE_CONTROL, cmd_inner(MODULE_CONTROL, pb_int(1, 4) + pb_int(2, 1)), "VR=1 (кнопка голоса)")
        except OSError as e:
            self.finish(f"write error: {e}")

    def send_hard_key(self, code: int):
        """Хард-кнопка ГУ (TOUCH_CAR_HARD_KEY_CODE, одно сообщение на нажатие)."""
        if not self.session_up.is_set() or self.stop.is_set():
            return
        try:
            self.send(CH_TOUCH, build_hard_key(code), f"TOUCH_CAR_HARD_KEY_CODE 0x{code:02X}")
        except OSError as e:
            self.finish(f"write error: {e}")

    def summary(self) -> dict:
        dur = time.monotonic() - self.t0 if self.t0 else 0.0
        span = (self.last_frame_at - self.first_frame_at) if self.first_frame_at is not None else 0.0
        avg = self.frames / span if span > 0 else 0.0
        if self.auth_status == "PENDING":
            self.auth_status = "AUTH NO RESPONSE"
        self.log("==== SUMMARY ====")
        self.log(f"stop reason: {self.stop_reason}")
        self.log(f"duration: {dur:.1f} s")
        self.log(f"auth: {self.auth_status}")
        self.log("received messages:")
        for (chan, mtype), n in sorted(self.counts.items()):
            tag = f"0x{mtype:08X} {msg_name(mtype)}" if mtype >= 0 else "short"
            self.log(f"  {CH_NAMES.get(chan, chan)} {tag} x{n}")
        self.log(f"video: {self.frames} frames, {self.vbytes} B, avg {avg:.1f} fps")
        connected = len(self.socks) + (1 if self.extra else 0) if self.tcp6 else 1 if self.conn else 0
        self.log(f"transport: {self.o.transport}, connected sockets: {connected}")
        if self.o.discover:
            self.log(f"discover: host {self.discovered_host}, beacons {self.beacons}, probe reply {self.probe_reply}")
        if self.o.kill_chan and self.tcp6:
            if not self.killed:
                self.log(f"kill: {self.o.kill_chan} not triggered (no video frame within the session)")
            elif self.closed_others_after is not None:
                self.log(f"kill: {self.o.kill_chan} at {self.kill_at - self.t0:.1f} s, "
                         f"bridge closed others after {self.closed_others_after:.1f} s")
            else:
                self.log(f"kill: {self.o.kill_chan} at {self.kill_at - self.t0:.1f} s, "
                         f"bridge did NOT close others within {self.KILL_WAIT:g} s")
        pause = self.pause_summary() if self.o.pause_cycles else None
        return {"duration": dur, "auth": self.auth_status, "pause": pause, "counts": dict(self.counts), "frames": self.frames,
                "video_bytes": self.vbytes, "avg_fps": avg, "stop_reason": self.stop_reason,
                "transport": self.o.transport, "connected": connected,
                "kill_chan": self.o.kill_chan if self.killed else None,
                "kill_at": self.kill_at - self.t0 if self.killed else None,
                "closed_others_after": self.closed_others_after,
                "discovered_host": self.discovered_host, "beacons": self.beacons, "probe_reply": self.probe_reply}


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=7240, help="stream: port to listen on (default 7240)")
    p.add_argument("--transport", choices=("stream", "tcp6"), default="stream",
                   help="stream: listen, one connection with outer headers (adb reverse); "
                        "tcp6: connect to the phone's 6 ports (wireless format, adb forward), no outer header")
    p.add_argument("--kill-chan", choices=tuple(CH_NAMES.values()), metavar="NAME",
                   help="tcp6 fault injection: close this channel's socket (CMD/VIDEO/MEDIA/TTS/VR/TOUCH), "
                        "keep the others and wait for the bridge to close them")
    p.add_argument("--kill-after", type=float, default=10.0, metavar="N",
                   help="tcp6: close the --kill-chan socket N seconds after the first video frame (default 10)")
    p.add_argument("--extra-data-socket", action="store_true",
                   help="tcp6: also connect to the Data channel port 9440 (the phone ignores it)")
    p.add_argument("--discover", action="store_true",
                   help="tcp6, branch B (phone hotspot): wait for the phone's UDP beacon and use the sender "
                        "address as --host")
    p.add_argument("--beacon-port", type=int, default=BEACON_PORT, metavar="N",
                   help=f"--discover: UDP port to listen on (default {BEACON_PORT})")
    p.add_argument("--bind-ip", default="0.0.0.0", metavar="IP",
                   help="--discover: local address to receive the beacon on, to pick the hotspot NIC (default 0.0.0.0)")
    p.add_argument("--discover-timeout", type=float, default=60.0, metavar="N",
                   help="--discover: give up after N seconds without a beacon (default 60)")
    p.add_argument("--probe-8999", action="store_true",
                   help=f'--discover: after the beacon send {PROBE_REQUEST.decode()} to the phone, UDP {PROBE_PORT}, '
                        "and log the reply (not required to continue)")
    p.add_argument("--video-kbps", type=int, default=0, metavar="N",
                   help="tcp6 shaper: read the VIDEO socket no faster than N kbit/s (default 0 = unlimited)")
    p.add_argument("--dip-kbps", type=int, default=0, metavar="N",
                   help="tcp6 shaper: drop the VIDEO read limit to N kbit/s for --dip-for s, --dip-at s after "
                        "the first frame (e.g. --video-kbps 6000 --dip-kbps 1000 for the 6->1 Mbit/s scenario)")
    p.add_argument("--dip-at", type=float, default=20.0, metavar="N", help="shaper: dip start, s after the first frame (default 20)")
    p.add_argument("--dip-for", type=float, default=5.0, metavar="N", help="shaper: dip length, s (default 5)")
    p.add_argument("--pause-cycles", type=int, default=0, metavar="N",
                   help="run N cycles VIDEO_ENCODER_PAUSE -> wait -> VIDEO_ENCODER_START and measure start->first frame, "
                        "start->IDR and frames received during the pause (default 0 = off)")
    p.add_argument("--pause-every", type=float, default=10.0, metavar="S",
                   help="--pause-cycles: seconds from the first video frame (then from each START) to the next PAUSE (default 10)")
    p.add_argument("--pause-for", type=float, default=3.0, metavar="S",
                   help="--pause-cycles: seconds between PAUSE and START (default 3)")
    p.add_argument("--duration", type=float, default=None, metavar="N",
                   help="stop N seconds after the bridge connects (default 120; unlimited with --view)")
    p.add_argument("--view", action="store_true",
                   help="live window: video from the bridge (needs ffmpeg in PATH), mouse = touches, right click/Esc/BackSpace = "
                        "BACK, middle click/Home = HOME; closing the window ends the session")
    p.add_argument("--mute", action="store_true",
                   help="--view: do not play MEDIA/TTS on the PC speakers (playback needs the sounddevice module)")
    p.add_argument("--view-scale", type=float, default=None, metavar="K",
                   help="--view: window scale (default: fit to the PC screen, at most 1.0); click coordinates are mapped back")
    p.add_argument("--hu", choices=tuple(HU_PROFILES), default="honda",
                   help="HU profile: channel in STATISTIC_INFO, manufacturer/model in HU_INFO, default --size and the wheel "
                        "buttons. mazda = Desay G6S (Mazda CX-5, 1728x648) with the Commander knob: buttons under the window, "
                        "mouse wheel = knob turn, arrows = tilts, Enter = OK")
    p.add_argument("--size", default=None, metavar="WxH",
                   help="screen size the HU asks for in VIDEO_ENCODER_INIT (default by --hu: 1920x720, mazda 1728x648); the bridge answers with its own "
                        "choice in INIT_DONE, the log shows both")
    p.add_argument("--no-auth", action="store_true", help="do not send HU_AUTHEN_REQUEST")
    p.add_argument("--touch-test", action="store_true",
                   help="15 s after the first video frame send a tap (DOWN+UP) at the centre")
    p.add_argument("--out", type=Path, default=HERE / "out", help="output directory (default pc-headunit/out)")
    a = p.parse_args(argv)
    try:
        a.size = parse_size(a.size) if a.size else HU_PROFILES[a.hu]["size"]
    except ValueError as e:
        p.error(f"--size: {e}")
    if a.view_scale is not None and not a.view:
        p.error("--view-scale needs --view")
    if a.view_scale is not None and not 0.1 <= a.view_scale <= 4:
        p.error("--view-scale must be between 0.1 and 4")
    if a.discover and a.transport != "tcp6":
        p.error("--discover works only with --transport tcp6")
    if a.probe_8999 and not a.discover:
        p.error("--probe-8999 needs --discover")
    if (a.video_kbps or a.dip_kbps) and a.transport != "tcp6":
        p.error("--video-kbps/--dip-kbps work only with --transport tcp6")
    if a.pause_cycles < 0 or a.pause_every <= 0 or a.pause_for < 0:
        p.error("--pause-cycles must be >= 0, --pause-every > 0, --pause-for >= 0")
    return a


# Клавиатура и колесо мыши окна -> шайба Mazda (carlife_view.Viewer knob=): событие tk -> hard key.
MAZDA_KNOB_KEYS = {"wheel_up": 0x07, "wheel_down": 0x06, "<Left>": 0x15, "<Right>": 0x16, "<Up>": 0x17,
                   "<Down>": 0x18, "<Return>": 0x14}


def wheel_buttons(hu: HeadUnit) -> list[tuple[str, object]]:
    """Ряд кнопок руля под окном. Vezel по CarLife шлёт только голос (MODULE_CONTROL VR=1) и BACK (0x0E);
    треки и трубка у него идут по BT, здесь они hard key CarLife, как у других ГУ (HARD_KEY_TO_ANDROID моста)."""
    k = hu.send_hard_key
    if hu.o.hu == "mazda":
        # Mazda Commander (лог h737/04.11): поворот 6/7, наклоны 0x15..0x18, нажатие OK 0x14, Домой = MAIN 0x1E,
        # NAVI 0x0B, MEDIA 0x09, BACK 0x0E. «Избранное» не берём: его код неизвестен.
        return [
            ("⟲", lambda: k(0x07)),
            ("⟳", lambda: k(0x06)),
            ("←", lambda: k(0x15)),
            ("→", lambda: k(0x16)),
            ("↑", lambda: k(0x17)),
            ("↓", lambda: k(0x18)),
            ("OK", lambda: k(0x14)),
            ("Назад", lambda: k(HARD_KEY_BACK)),
            ("Домой", lambda: k(0x1E)),
            ("Нави", lambda: k(0x0B)),
            ("Медиа", lambda: k(0x09)),
            ("Голос", hu.send_voice_key),
            ("|<<", lambda: k(0x0F)),
            (">>|", lambda: k(0x10)),
        ]
    return [
        ("Голос", hu.send_voice_key),
        ("VR_START", lambda: k(0x21)),
        ("|<<", lambda: k(0x0F)),
        (">>|", lambda: k(0x10)),
        ("Play", lambda: k(0x1F)),
        ("Pause", lambda: k(0x20)),
        ("Трубка", lambda: k(0x02)),
        ("Сброс", lambda: k(0x03)),
        ("Назад", lambda: k(HARD_KEY_BACK)),
        ("Домой", lambda: k(HARD_KEY_HOME)),
    ]


def run_view(hu: HeadUnit, scale: float | None) -> int:
    """--view: сессия в рабочей нити, окно tkinter в главной (tkinter другого не терпит)."""
    import signal
    import traceback
    import carlife_view as V
    ffmpeg = V.find_ffmpeg()
    if not ffmpeg:
        print("--view needs ffmpeg in PATH (winget install Gyan.FFmpeg)", file=sys.stderr)
        return 2
    if not hu.tcp6:
        hu.listen()                                         # порт занят - узнаём сразу, до окна
    viewer = V.Viewer(ffmpeg, hu.o.size, scale, hu.send_touch_event, hu.send_hard_key, lambda: hu.finish("window closed"),
                      lambda: worker.is_alive(), hu.log, HARD_KEY_BACK, HARD_KEY_HOME, wheel=wheel_buttons(hu),
                      knob=MAZDA_KNOB_KEYS if hu.o.hu == "mazda" else None)
    hu.video_sink, hu.size_sink = viewer.pipe.on_video, viewer.pipe.set_video_size

    def work():
        try:
            hu.run()
        except BaseException:
            traceback.print_exc()
            hu.finish("error")

    worker = threading.Thread(target=work, name="hu-session")
    signal.signal(signal.SIGINT, lambda *_: hu.finish("Ctrl+C"))
    worker.start()
    viewer.run()
    hu.finish("window closed")
    worker.join()
    return 0


def main(argv=None) -> int:
    a = parse_args(argv)
    duration = a.duration if a.duration is not None else (float("inf") if a.view else 120.0)
    hu = HeadUnit(Options(a.host, a.port, duration, a.no_auth, a.touch_test, a.out, transport=a.transport,
                          kill_chan=a.kill_chan, kill_after=a.kill_after, extra_data_socket=a.extra_data_socket,
                          discover=a.discover, beacon_port=a.beacon_port, bind_ip=a.bind_ip,
                          discover_timeout=a.discover_timeout, probe_8999=a.probe_8999,
                          video_kbps=a.video_kbps, dip_kbps=a.dip_kbps, dip_at=a.dip_at, dip_for=a.dip_for,
                          pause_cycles=a.pause_cycles, pause_every=a.pause_every, pause_for=a.pause_for,
                          size=a.size, hu=a.hu))
    if a.view:
        if not a.mute:
            hu.speakers = Speakers(hu.log)
        return run_view(hu, a.view_scale)
    hu.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
