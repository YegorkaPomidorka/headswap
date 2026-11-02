"""Offline tests for the CarLife HU emulator, tcp6 mode (6 sockets, no outer header): local loopback only.

    python -m pytest pc-headunit/tests/test_carlife_tcp6.py
"""
from __future__ import annotations

import socket
import sys
import tempfile
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import carlife_hu as C                                           # noqa: E402


# ---- InnerStreamParser ----

def test_inner_parser_cmd_byte_by_byte():
    inner = C.cmd_inner(C.MD_INFO, b"\x08\x01")
    p = C.InnerStreamParser(C.CH_CMD)
    got = []
    for b in inner + inner:
        got += p.feed(bytes([b]))
    assert got == [inner, inner]


def test_inner_parser_two_messages_in_one_chunk_and_remainder():
    a = C.cmd_inner(C.TOUCH_ACTION, b"abc")
    b = C.cmd_inner(C.TOUCH_ACTION)
    p = C.InnerStreamParser(C.CH_TOUCH)
    assert p.feed(a + b + a[:5]) == [a, b]
    assert p.feed(a[5:]) == [a]


def test_inner_parser_data_header():
    v = C.data_inner(C.VIDEO_DATA, b"x" * 100, 5)
    h = C.build_heartbeat()
    p = C.InnerStreamParser(C.CH_VIDEO)
    got = []
    for b in v + h:
        got += p.feed(bytes([b]))
    assert got == [v, h]
    assert C.InnerStreamParser(C.CH_VIDEO).feed(v + h) == [v, h]
    # медиа-канал: тип 0x0003xxxx
    m = C.data_inner(0x00030006, b"pcm")
    assert C.InnerStreamParser(C.CH_MEDIA).feed(m) == [m]


def test_inner_parser_bad_type():
    with pytest.raises(ValueError):
        C.InnerStreamParser(C.CH_CMD).feed(C.data_inner(C.VIDEO_HEARTBEAT))     # 0x0002xxxx на CMD
    with pytest.raises(ValueError):
        C.InnerStreamParser(C.CH_VIDEO).feed(C.cmd_inner(C.MD_INFO) + b"\x00" * 4)
    with pytest.raises(ValueError):
        C.InnerStreamParser(C.CH_TOUCH).feed(C.cmd_inner(C.MD_INFO))


def test_inner_parser_huge_length():
    with pytest.raises(ValueError):
        C.InnerStreamParser(C.CH_VIDEO).feed((65 * 1024 * 1024).to_bytes(4, "big") + b"\x00" * 4 + C.VIDEO_DATA.to_bytes(4, "big"))
    with pytest.raises(ValueError):
        C.InnerStreamParser(C.CH_MEDIA).feed(b"\xff\xff\xff\xff" + b"\x00" * 4 + (0x00030006).to_bytes(4, "big"))
    # CMD-заголовок: u16, максимум 65535 < 1 МиБ, поэтому на CMD ошибку даёт только тип
    assert C.InnerStreamParser(C.CH_CMD).feed(C.cmd_inner(C.MD_INFO, b"z" * 65535))[0][:2] == b"\xff\xff"


# ---- Options ----

def test_options_defaults_and_validation():
    o = C.Options()
    assert o.transport == "stream" and o.ports == C.DEFAULT_PORTS
    assert C.DEFAULT_PORTS == {1: 7240, 2: 8240, 3: 9240, 4: 9241, 5: 9242, 6: 9340}
    with pytest.raises(ValueError):
        C.Options(transport="udp")
    with pytest.raises(ValueError):
        C.Options(transport="tcp6", kill_chan="NOPE")
    with pytest.raises(ValueError):
        C.Options(video_kbps=1000)                      # шейпер только для tcp6


def test_shaper_limit_and_pacing():
    hu = C.HeadUnit(C.Options(transport="tcp6", quiet=True, out=Path(tempfile.mkdtemp()),
                              video_kbps=6000, dip_kbps=1000, dip_at=20.0, dip_for=5.0))
    assert hu.shaping
    assert hu.video_limit_kbps(0.0) == 6000             # до первого кадра - базовый предел
    hu.first_frame_at = 100.0
    assert hu.video_limit_kbps(119.9) == 6000
    assert hu.video_limit_kbps(120.0) == 1000
    assert hu.video_limit_kbps(124.9) == 1000
    assert hu.video_limit_kbps(125.0) == 6000
    hu.logf = open(Path(tempfile.mkdtemp()) / "log.txt", "w", encoding="utf-8")
    hu.first_frame_at = None
    t = C.time.monotonic()
    for _ in range(10):
        hu.shape_wait(7500)                             # 75 000 Б при 6000 кбит/с = 0,1 с
    took = C.time.monotonic() - t
    hu.logf.close()
    assert 0.08 <= took <= 0.3, took


# ---- end-to-end против фейкового телефона ----

CHANS = [C.CH_CMD, C.CH_VIDEO, C.CH_MEDIA, C.CH_TTS, C.CH_VR, C.CH_TOUCH]
MD_INFO_BODY = C.pb_str(1, "Android") + C.pb_str(4, "HUAWEI") + C.pb_str(14, "ADY-LX9") + C.pb_str(20, "31") + C.pb_int(21, 31)


class FakePhone:
    """Телефон: 6 серверных сокетов на эфемерных портах; эмулятор подключается к ним как клиент."""

    def __init__(self):
        self.servers: dict[int, socket.socket] = {}
        for c in CHANS:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(("127.0.0.1", 0))
            s.listen(1)
            self.servers[c] = s
        self.ports = {c: s.getsockname()[1] for c, s in self.servers.items()}
        self.conns: dict[int, socket.socket] = {}
        self.parsers = {c: C.InnerStreamParser(c) for c in CHANS}
        self.msgs: dict[int, list[tuple[int, bytes]]] = {c: [] for c in CHANS}     # chan -> [(type, body)]

    def accept_all(self):
        for c in CHANS:                                          # порядок подключения = порядок каналов
            self.servers[c].settimeout(5)
            self.conns[c], _ = self.servers[c].accept()

    def send(self, chan: int, inner: bytes):
        self.conns[chan].sendall(inner)                          # без внешнего заголовка

    def pump(self, chan: int, until, timeout: float = 5.0):
        s = self.conns[chan]
        s.settimeout(timeout)
        while not until(self.msgs[chan]):
            data = s.recv(65536)
            if not data:
                return False
            for inner in self.parsers[chan].feed(data):
                mtype, body, _ = C.parse_inner(chan, inner)
                self.msgs[chan].append((mtype, body))
        return True

    def types(self, chan: int):
        return [t for t, _ in self.msgs[chan]]

    def close(self):
        for s in list(self.conns.values()) + list(self.servers.values()):
            try:
                s.close()
            except OSError:
                pass


def start_hu(phone: FakePhone, duration: float, **kw):
    out = Path(tempfile.mkdtemp(prefix="carlife_hu_tcp6_test_"))
    hu = C.HeadUnit(C.Options(duration=duration, out=out, quiet=True, transport="tcp6", ports=phone.ports, **kw))
    res: dict = {}
    t = threading.Thread(target=lambda: res.update(hu.run()), daemon=True)
    t.start()
    return hu, t, res, out


def test_tcp6_session():
    phone = FakePhone()
    hu, t, res, out = start_hu(phone, 20.0, touch_test=True)
    hu.TOUCH_DELAY = 0.3
    try:
        phone.accept_all()
        # CMD: голый HU_PROTOCOL_VERSION, без внешнего заголовка
        assert phone.pump(C.CH_CMD, lambda m: len(m) >= 1)
        assert phone.types(C.CH_CMD) == [C.HU_PROTOCOL_VERSION]
        assert phone.parsers[C.CH_CMD].buf == b""
        # MD_INFO -> init burst
        phone.send(C.CH_CMD, C.cmd_inner(C.MD_INFO, MD_INFO_BODY))
        assert phone.pump(C.CH_CMD, lambda m: len(m) >= 8)
        assert phone.types(C.CH_CMD) == [C.HU_PROTOCOL_VERSION, C.STATISTIC_INFO, C.HU_INFO, C.HU_AUTHEN_REQUEST,
                                         C.HU_UNKNOWN_1804C, C.VIDEO_ENCODER_INIT, C.HU_FEATURE_CONFIG_RESPONSE,
                                         C.VIDEO_ENCODER_START]
        # auth
        rnd = C.pb_get_str(C.pb_parse(dict(phone.msgs[C.CH_CMD])[C.HU_AUTHEN_REQUEST]), 1)
        phone.send(C.CH_CMD, C.cmd_inner(C.MD_AUTHEN_RESPONSE,
                                         C.pb_str(1, C.auth_expected("HUAWEI", "ADY-LX9", "31", rnd.split(";", 1)[1]))))
        assert phone.pump(C.CH_CMD, lambda m: len(m) >= 9)
        assert phone.msgs[C.CH_CMD][-1] == (C.HU_AUTHEN_RESULT, C.pb_int(1, 1))
        # видео по VIDEO-сокету
        phone.send(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x67" + b"x" * 10))
        phone.send(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x41" + b"y" * 5))
        # heartbeat идёт по VIDEO, тач — по TOUCH
        assert phone.pump(C.CH_VIDEO, lambda m: C.VIDEO_HEARTBEAT in [x[0] for x in m])
        assert phone.pump(C.CH_TOUCH, lambda m: len(m) >= 2)
        assert phone.types(C.CH_TOUCH) == [C.TOUCH_ACTION, C.TOUCH_ACTION]
        assert phone.msgs[C.CH_TOUCH][0][1] == C.pb_int(1, 0) + C.pb_int(2, 960) + C.pb_int(3, 360)    # центр 1920x720: INIT_DONE не было, берём размер из нашего INIT
        for c in (C.CH_MEDIA, C.CH_TTS, C.CH_VR):
            assert phone.types(c) == []
        # телефон закрывает всё
        for s in phone.conns.values():
            s.close()
        t.join(10)
        assert not t.is_alive()
    finally:
        phone.close()
    assert res["transport"] == "tcp6" and res["connected"] == 6
    assert res["stop_reason"].startswith("bridge closed the connection")
    assert res["auth"] == "AUTH OK"
    assert res["frames"] == 2 and res["video_bytes"] == 15 + 10
    assert res["counts"][(C.CH_CMD, C.MD_INFO)] == 1
    assert (out / "carlife_video.h264").read_bytes().startswith(b"\x00\x00\x00\x01\x67")
    log = (out / "carlife_hu.log").read_text(encoding="utf-8")
    assert f"connected CMD :{phone.ports[C.CH_CMD]}" in log and f"connected TOUCH :{phone.ports[C.CH_TOUCH]}" in log


def test_tcp6_connect_failure():
    phone = FakePhone()
    phone.servers[C.CH_TTS].close()                              # порт TTS никто не слушает
    hu, t, res, _ = start_hu(phone, 5.0)
    try:
        t.join(10)
    finally:
        phone.close()
    assert not t.is_alive()
    assert res["connected"] == 3 and "connect TTS" in res["stop_reason"]


def test_tcp6_kill_chan_bridge_closes_others():
    phone = FakePhone()
    hu, t, res, _ = start_hu(phone, 20.0, kill_chan="touch", kill_after=0.3)
    try:
        phone.accept_all()
        phone.send(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x67" + b"x" * 10))
        # эмулятор закрыл TOUCH: у телефона EOF
        phone.conns[C.CH_TOUCH].settimeout(5)
        assert phone.conns[C.CH_TOUCH].recv(10) == b""
        # остальные ещё открыты: heartbeat продолжается
        assert phone.pump(C.CH_VIDEO, lambda m: C.VIDEO_HEARTBEAT in [x[0] for x in m])
        assert t.is_alive()
        for c in CHANS:
            if c != C.CH_TOUCH:
                phone.conns[c].close()
        t.join(10)
        assert not t.is_alive()
    finally:
        phone.close()
    assert res["stop_reason"] == "bridge closed the connection (after kill TOUCH)"
    assert res["kill_chan"] == "TOUCH" and res["closed_others_after"] is not None
    assert res["connected"] == 6


def test_tcp6_kill_chan_bridge_does_not_close_others():
    phone = FakePhone()
    hu, t, res, out = start_hu(phone, 20.0, kill_chan="VIDEO", kill_after=0.2)
    hu.KILL_WAIT = 1.0
    try:
        phone.accept_all()
        phone.send(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x67" + b"x" * 10))
        t.join(10)
        assert not t.is_alive()
    finally:
        phone.close()
    assert res["closed_others_after"] is None and res["kill_chan"] == "VIDEO"
    assert "did NOT close" in res["stop_reason"]
    assert "bridge did NOT close others within 1 s" in (out / "carlife_hu.log").read_text(encoding="utf-8")
