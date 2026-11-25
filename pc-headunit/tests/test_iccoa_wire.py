"""Tests for iccoa_wire.py: frame, envelopes, car auth, RTSP sink, RFC 4571, TS demux, UIBC. Bytes only, no sockets.

    python -X utf8 -m unittest discover -s tests -p "test_iccoa*.py" -v

The phone side (PhoneAuth, TS muxer) is written here by the formulas of FT (app/ft/iccoa/IccoaAuth.kt, IccoaTsMuxer.kt).
"""
from __future__ import annotations

import io
import json
import os
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import iccoa_wire as W                                           # noqa: E402
from carlife_hu import pb_bytes, pb_int, pb_str                  # noqa: E402

PIN = "012345"


# ---- сторона телефона (FT IccoaAuth) ----

class PhoneAuth:
    """pin - вход с PIN; car_pub - быстрый вход по запомненному ключу машины."""

    def __init__(self, pin: str | None = None, car_pub: bytes | None = None, client_id: str = "test-phone", identity=None):
        self.pin, self.car_pub, self.client_id = pin, car_pub, client_id
        self.identity = identity or W.new_key()
        self.agree = W.new_key()
        self.nonce = os.urandom(32)

    def request(self) -> bytes:
        ident, agree = W.pub_bytes(self.identity), W.pub_bytes(self.agree)
        req = pb_int(1, 1793)
        if self.pin:
            req += pb_bytes(2, ident) + pb_bytes(3, W.pin_mac(self.pin, ident, self.nonce))
        req += (pb_bytes(4, agree) + pb_bytes(5, W.sign(self.identity, agree + self.nonce)) + pb_bytes(6, self.nonce)
                + pb_str(7, self.client_id) + pb_str(8, "test-model"))
        if self.pin:
            req += pb_int(9, 1)
        return req + pb_int(10, 769) + pb_str(11, "test-model")

    def accept(self, response: bytes) -> W.Cipher:
        """Проверки FT: result, HMAC от PIN, подпись машины. AssertionError при провале."""
        f = W.fields(response)
        assert f.get(7, 0) == 0, f"result={f[7]}"
        car = self.car_pub or f[2]
        if self.car_pub and 2 in f:
            assert f[2] == self.car_pub, "car identity changed"
        if self.pin:
            assert f[3] == W.pin_mac(self.pin, car + W.pub_bytes(self.identity), f[6] + self.nonce), "PIN MAC"
        assert W.verify(car, f[5], f[4] + W.pub_bytes(self.agree) + f[6] + self.nonce), "car signature"
        self.car_pub = car
        return W.Cipher(W.session_key(self.agree, f[4], self.nonce, f[6]))

    @staticmethod
    def confirm(cipher: W.Cipher, addr: str = "192.168.50.20") -> bytes:
        return pb_bytes(1, cipher.encrypt(f"{addr}:0".encode()))


# ---- сторона телефона: TS-мукс (FT IccoaTsMuxer, без PCR) ----

def ts_pes(pid: int, stream_id: int, es: bytes, cc: int = 0) -> bytes:
    """Один PES в TS-пакетах; хвост добивается adaptation field."""
    pes = bytes([0, 0, 1, stream_id, 0, 0, 0x80, 0x80, 5, 0x21, 0, 1, 0, 1]) + es
    out = b""
    for i in range(0, len(pes), 184):
        chunk = pes[i:i + 184]
        head = bytes([0x47, pid >> 8 | (0x40 if i == 0 else 0), pid & 255])
        pad = 184 - len(chunk)
        if pad:
            adapt = bytes([pad - 1]) + (b"\x00" + b"\xff" * (pad - 2) if pad > 1 else b"")
            out += head + bytes([0x30 | cc & 15]) + adapt + chunk
        else:
            out += head + bytes([0x10 | cc & 15]) + chunk
        cc += 1
    return out


def ts_psi() -> bytes:
    """PAT (PID 0) и PMT (PID 4096) с теми же секциями, что у FT; CRC демуксу не нужен."""
    pat = bytes([0, 0xB0, 13, 0, 1, 0xC1, 0, 0, 0, 1, 0xF0, 0]) + b"\0\0\0\0"
    pmt = bytes([2, 0xB0, 23, 0, 1, 0xC1, 0, 0, 0xE1, 0, 0xF0, 0, 27, 0xE1, 0, 0xF0, 0, 15, 0xE1, 1, 0xF0, 0]) + b"\0\0\0\0"
    return b"".join((bytes([0x47, pid >> 8 | 0x40, pid & 255, 0x10, 0]) + sec).ljust(188, b"\xff")
                    for pid, sec in ((0, pat), (4096, pmt)))


def rtp_tcp(ts: bytes, seq: int = 0) -> bytes:
    """TS -> RTP PT 33 по 7 пакетов, маркер на последнем, рамка RFC 4571."""
    out = b""
    for i in range(0, len(ts), 7 * 188):
        last = i + 7 * 188 >= len(ts)
        pkt = struct.pack(">BBHII", 0x80, 33 | (0x80 if last else 0), seq & 0xFFFF, 0, 0x1234) + ts[i:i + 7 * 188]
        out += struct.pack(">H", len(pkt)) + pkt
        seq += 1
    return out


H264 = b"\0\0\0\1\x67" + b"S" * 20 + b"\0\0\0\1\x68" + b"P" * 6 + b"\0\0\0\1\x65" + bytes(range(256)) * 9
ADTS = b"\xff\xf1\x4c\x80\x04\x1f\xfc" + b"A" * 25


class FrameTest(unittest.TestCase):
    def test_crc_vector_and_header_bytes(self):
        self.assertEqual(W.crc16(b"123456789"), 0xBB3D)             # CRC-16/ARC, IccoaControlTest
        packet = W.frame(3, 1, 1, bytes([7, 8, 9]), 42, t=123)
        self.assertEqual(packet[:18], bytes([0, 0, 0, 23, 0, 0, 0, 42, 0, 0, 0, 123, 11, 3, 0, 1, 0, 0]))
        self.assertEqual(struct.unpack(">H", packet[18:20])[0], W.crc16(packet[:18]))
        self.assertEqual(W.read_frame(io.BytesIO(packet)), W.Frame(42, 3, 1, 1, 1, bytes([7, 8, 9])))

    def test_xa60_header_with_low_bit_clear(self):
        # заголовок ответа XA60 из IccoaControlTest: флаги 0x12, CRC 0x2c5a
        body = bytes(i & 255 for i in range(296))
        packet = bytes([0, 0, 1, 60, 0, 0, 0, 42, 0, 0, 0, 123, 0x12, 3, 0, 2, 0, 0, 0x2C, 0x5A]) + body
        f = io.BytesIO(packet + W.frame(1, 4, 0, b"\x09", 43))
        self.assertEqual(W.read_frame(f), W.Frame(42, 3, 2, 2, 1, body))
        self.assertEqual(W.read_frame(f).seq, 43)
        self.assertIsNone(W.read_frame(f))

    def test_corrupt_oversized_truncated(self):
        packet = bytearray(W.frame(1, 1, 0, b"\x01\x02", 1))
        self.assertRaises(EOFError, W.read_frame, io.BytesIO(packet[:-1]))
        self.assertRaises(EOFError, W.read_frame, io.BytesIO(packet[:7]))
        packet[4] ^= 1
        self.assertRaises(ValueError, W.read_frame, io.BytesIO(packet))
        big = bytearray(W.frame(1, 1, 0, b"", 1))
        big[0:4] = b"\x7f\xff\xff\xff"
        self.assertRaises(ValueError, W.read_frame, io.BytesIO(big))


class CipherTest(unittest.TestCase):
    def test_envelopes(self):
        c = W.Cipher(bytes(range(16)))
        first, second = c.encrypt(b"first"), c.encrypt(b"second")
        self.assertTrue(W.is_encrypted(first))
        self.assertEqual(first[:4], b"\0\0\0\x0c")
        self.assertEqual(struct.unpack(">I", first[16:20])[0], 5 + 16)
        self.assertEqual(c.decrypt(first + second), b"firstsecond")
        self.assertRaises(ValueError, c.decrypt, first[:-1] + bytes([first[-1] ^ 1]))
        self.assertRaises(ValueError, c.decrypt, first + b"\0")
        self.assertRaises(ValueError, W.Cipher(bytes(16)).decrypt, first)
        self.assertFalse(W.is_encrypted(b"\x08\x01"))


class AuthTest(unittest.TestCase):
    def setUp(self):
        self.trust = W.Trust()
        self.car = W.CarAuth(PIN, self.trust)

    def sign_in(self, phone: PhoneAuth):
        resp, sess = self.car.respond(phone.request())
        cipher = phone.accept(resp)
        self.assertEqual(self.car.confirm(phone.confirm(cipher), sess), "192.168.50.20:0")
        return sess

    def test_pin_round_trip(self):
        phone = PhoneAuth(PIN)
        resp, sess = self.car.respond(phone.request())
        f = W.fields(resp)
        self.assertEqual((f[1], f[7], f[8], len(f[2]), len(f[4]), len(f[6])), (1793, 0, 769, 64, 64, 32))
        cipher = phone.accept(resp)                                 # HMAC и подпись машины сошлись у телефона
        self.assertEqual(sess.cipher.decrypt(cipher.encrypt(b"same key")), b"same key")
        self.assertFalse(sess.quick)
        self.assertEqual(self.trust.clients, {})                    # до Confirm клиент не доверенный
        self.assertEqual(self.car.confirm(phone.confirm(cipher), sess), "192.168.50.20:0")
        self.assertEqual(self.trust.clients, {"test-phone": W.pub_bytes(phone.identity).hex()})

    def test_wrong_pin_and_bad_signature_rejected(self):
        resp, sess = self.car.respond(PhoneAuth("999999").request())
        self.assertIsNone(sess)
        self.assertEqual(W.fields(resp), {1: 1793, 7: W.RESULT_NEED_PIN})
        req = W.fields(PhoneAuth(PIN).request())
        req[5] = W.sign(W.new_key(), req[4] + req[6])               # подпись чужим ключом при верном HMAC
        body = b"".join(pb_int(n, v) if isinstance(v, int) else pb_bytes(n, v) for n, v in req.items())
        resp, sess = self.car.respond(body)
        self.assertIsNone(sess)
        self.assertEqual(W.fields(resp)[7], W.RESULT_INTERNAL)
        self.assertEqual(W.fields(self.car.respond(b"\x08")[0])[7], W.RESULT_INTERNAL)   # мусор вместо protobuf

    def test_quick_sign_in(self):
        first = PhoneAuth(PIN)
        self.assertEqual(W.fields(self.car.respond(PhoneAuth(car_pub=bytes(64), identity=first.identity).request())[0])[7],
                         W.RESULT_NEED_PIN)                         # ещё не знакомы
        self.sign_in(first)
        quick = PhoneAuth(car_pub=first.car_pub, identity=first.identity)
        self.assertNotIn(2, W.fields(quick.request()))
        self.assertNotIn(3, W.fields(quick.request()))
        resp, _ = self.car.respond(quick.request())
        self.assertNotIn(3, W.fields(resp))
        self.assertTrue(self.sign_in(quick).quick)
        # тот же clientId с чужим ключом - снова нужен PIN
        stranger = PhoneAuth(car_pub=first.car_pub)
        self.assertEqual(W.fields(self.car.respond(stranger.request())[0])[7], W.RESULT_NEED_PIN)

    def test_force_result(self):
        for code in (1, 2, 3, 4):
            resp, sess = W.CarAuth(PIN, self.trust, force_result=code).respond(PhoneAuth(PIN).request())
            self.assertIsNone(sess)
            self.assertEqual(W.fields(resp)[7], code)

    def test_trust_file_survives_restart(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "trust.json"
            self.car = W.CarAuth(PIN, W.Trust(path))
            first = PhoneAuth(PIN)
            self.sign_in(first)
            self.car = W.CarAuth(PIN, W.Trust(path))                # «перезапуск» эмулятора
            self.assertTrue(self.sign_in(PhoneAuth(car_pub=first.car_pub, identity=first.identity)).quick)
            self.assertNotIn(PIN, path.read_text("utf-8"))


class ControlBodiesTest(unittest.TestCase):
    def test_config_key_uibc(self):
        self.assertEqual(W.fields(W.car_config(160)), {18: 769, 19: 0, 20: 0, 21: 1, 22: 1, 23: 0, 24: 160})
        self.assertEqual(W.key_body(85, True), bytes([0x08, 1, 0x10, 85]))
        self.assertEqual(json.loads(W.uibc_touch(2, 10, 20, 2422, 1272)),
                         {"type": 1, "action": 2, "count": 1, "x0": 10, "y0": 20, "trackID0": 0, "width": 2422, "height": 1272})
        self.assertEqual(json.loads(W.uibc_key(4, False)), {"type": 2, "action": 0, "keycode": 4})
        self.assertEqual(W.shape(pb_str(2, "secret-data") + pb_int(7, 5)), "f2=bytes(11) f7=5")


# диалог источника FT (IccoaRtsp.kt): что телефон шлёт sink-у
M3_BODY = ("wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_content_protection\r\nwfd_display_edid\r\n"
           "wfd_uibc_capability\r\nwfd_coupled_sink\r\nwfd_standby_resume_capability\r\nwfd_connector_type\r\n"
           "wfd_car_display_mode\r\niccoa_support_hevc\r\n")
M4_BODY = ("wfd_video_formats: 28 00 01 01 ffffffff ffffffff ffffffff 00 0000 0000 00 none none\r\n"
           "wfd_audio_codecs: AAC 00000001 00\r\nwfd_client_rtp_ports: RTP/AVP/TCP;unicast 15550 0 mode=play\r\n"
           "wfd_presentation_URL: rtsp://192.168.50.20/wfd1.0/streamid=0 none\r\n"
           "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;"
           "hidc_cap_list=none;port=4321;uibc_encrypted=true\r\n")


class RtspTest(unittest.TestCase):
    def test_parser_fragmented(self):
        data = W.rtsp_msg("OPTIONS * RTSP/1.0", 1, extra="Require: org.wfa.wfd1.0\r\n") + W.rtsp_msg("X y RTSP/1.0", 2, "a: b\r\n")
        p, got = W.RtspParser(), []
        for b in data:
            got += p.feed(bytes([b]))
        self.assertEqual([m.first for m in got], ["OPTIONS * RTSP/1.0", "X y RTSP/1.0"])
        self.assertEqual(got[0].headers, {"cseq": "1", "require": "org.wfa.wfd1.0"})
        self.assertEqual(got[1].body, "a: b\r\n")

    def test_sink_dialog_m1_to_m7(self):
        sink, parser = W.RtspSink(2422, 1272, 160, 60, 15550, "192.168.50.20"), W.RtspParser()

        def phone(data: bytes) -> list[W.RtspMsg]:
            return [r for m in parser.feed(data) for b in sink.feed(m) for r in W.RtspParser().feed(b)]

        # M1 -> 200 и наш M2
        m1, m2 = phone(W.rtsp_msg("OPTIONS * RTSP/1.0", 1, extra="Require: org.wfa.wfd1.0\r\n"))
        self.assertEqual((m1.first, m1.headers["cseq"]), ("RTSP/1.0 200 OK", "1"))
        self.assertIn("GET_PARAMETER", m1.headers["public"])
        self.assertEqual((m2.first, m2.headers["require"]), ("OPTIONS * RTSP/1.0", "org.wfa.wfd1.0"))
        self.assertEqual(phone(W.rtsp_msg("RTSP/1.0 200 OK", m2.headers["cseq"], extra="Public: org.wfa.wfd1.0\r\n")), [])
        # M3: те же регэкспы, которыми FT достаёт экран и порт (IccoaRtsp.capabilities)
        (m3,) = phone(W.rtsp_msg("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 2, M3_BODY))
        self.assertEqual(m3.headers["cseq"], "2")
        self.assertEqual(re.search(r"display=(\d+):(\d+);dpi=(\d+);fps=(\d+)", m3.body).groups(), ("2422", "1272", "160", "60"))
        self.assertEqual(re.search(r"RTP/AVP/TCP;[^\r\n]*?\s(\d+)\s+\d+\s+mode=play", m3.body, re.I).group(1), "15550")
        self.assertEqual(len(m3.body.splitlines()), 11)             # ответ на каждое спрошенное имя
        self.assertIn("wfd_content_protection: none", m3.body)
        # M4, M5 -> 200 и наш M6 SETUP
        (m4,) = phone(W.rtsp_msg("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 3, M4_BODY))
        self.assertEqual(m4.first, "RTSP/1.0 200 OK")
        m5, m6 = phone(W.rtsp_msg("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 4, "wfd_trigger_method: SETUP\r\n"))
        self.assertEqual(m5.first, "RTSP/1.0 200 OK")
        self.assertEqual(m6.first, "SETUP rtsp://192.168.50.20/wfd1.0/streamid=0 RTSP/1.0")
        self.assertEqual(re.search(r"(?:client_port=|port=)(\d+)", m6.headers["transport"]).group(1), "15550")
        self.assertIn("RTP/AVP/TCP", m6.headers["transport"])
        # ответ на M6 -> M7 PLAY с сессией; ответ на M7 -> playing
        (m7,) = phone(W.rtsp_msg("RTSP/1.0 200 OK", m6.headers["cseq"],
                                 extra=f"Transport: {m6.headers['transport']}\r\nSession: 1;timeout=30\r\n"))
        self.assertEqual((m7.first.split()[0], m7.headers["session"]), ("PLAY", "1"))
        self.assertFalse(sink.playing)
        self.assertEqual(phone(W.rtsp_msg("RTSP/1.0 200 OK", m7.headers["cseq"], extra="Session: 1\r\n")), [])
        self.assertTrue(sink.playing)
        # M16 keep-alive и наш запрос IDR
        (m16,) = phone(W.rtsp_msg("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 5, extra="Session: 1\r\n"))
        self.assertEqual((m16.first, m16.headers["cseq"], m16.body), ("RTSP/1.0 200 OK", "5", ""))
        (idr,) = W.RtspParser().feed(sink.idr_request())
        self.assertTrue(idr.first.startswith("SET_PARAMETER"))
        self.assertIn("wfd_idr_request", idr.body)
        self.assertEqual(phone(W.rtsp_msg("TEARDOWN rtsp://x RTSP/1.0", 6))[0].first, "RTSP/1.0 405 Method Not Allowed")


class MediaTest(unittest.TestCase):
    def test_rfc4571_fragmented(self):
        packets = [b"\x80\x21" + bytes(10) + bytes([i]) * (i * 50) for i in range(4)]
        stream = b"".join(struct.pack(">H", len(p)) + p for p in packets)
        r, got = W.Rfc4571(), []
        for i in range(0, len(stream), 7):
            got += r.feed(stream[i:i + 7])
        self.assertEqual(got, packets)
        self.assertEqual(r.buf, b"")

    def test_parse_rtp(self):
        self.assertEqual(W.parse_rtp(bytes([0x80, 0xA1]) + bytes(10) + b"TS"), (33, True, b"TS"))
        self.assertEqual(W.parse_rtp(bytes([0x81, 33]) + bytes(14) + b"TS"), (33, False, b"TS"))    # один CSRC
        self.assertRaises(ValueError, W.parse_rtp, b"\x00" * 12)
        self.assertRaises(ValueError, W.parse_rtp, b"\x80\x21")

    def test_ts_demux_video_and_audio(self):
        ts = ts_psi() + ts_pes(256, 0xE0, H264) + ts_pes(257, 0xC0, ADTS) + ts_pes(256, 0xE0, b"\0\0\0\1\x41second")
        self.assertEqual(len(ts) % 188, 0)
        d = W.TsDemux()
        # без маркера RTP единица выходит на старте следующего PES того же PID, остаток - на end
        self.assertEqual(d.feed(ts, end=True), [(256, H264), (256, b"\0\0\0\1\x41second"), (257, ADTS)])
        self.assertEqual(d.bad, 0)

    def test_ts_demux_flushes_on_next_pes_without_marker(self):
        d = W.TsDemux()
        first = ts_pes(256, 0xE0, H264)
        self.assertEqual(d.feed(first[:188 * 3]), [])
        self.assertEqual(d.feed(first[188 * 3:]), [])
        self.assertEqual(d.feed(ts_pes(256, 0xE0, b"next")), [(256, H264)])
        self.assertEqual(d.feed(b"", end=True), [(256, b"next")])

    def test_ts_demux_skips_until_pes_start_and_bad_sync(self):
        d = W.TsDemux()
        pes = ts_pes(256, 0xE0, H264)
        self.assertEqual(d.feed(pes[188:], end=True), [])           # подключились посреди PES
        self.assertEqual(d.feed(b"\x00" * 188 + pes, end=True), [(256, H264)])
        self.assertEqual(d.bad, 1)

    def test_rtp_to_h264_end_to_end(self):
        ts = ts_psi() + ts_pes(256, 0xE0, H264)
        rfc, d, got = W.Rfc4571(), W.TsDemux(), []
        for pkt in rfc.feed(rtp_tcp(ts)):
            pt, marker, payload = W.parse_rtp(pkt)
            self.assertEqual(pt, 33)
            got += d.feed(payload, marker)
        self.assertEqual(got, [(256, H264)])


if __name__ == "__main__":
    unittest.main()
