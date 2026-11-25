"""ICCOA CarLink wire primitives for iccoa_hu.py (the CAR side): frame, AES-GCM envelopes, car auth, WFD sink (RTSP),
RFC 4571 / RTP / MPEG-TS demux, UIBC and control bodies. No sockets here: everything is testable on bytes.

Formulas and field numbers follow FT (github.com/OneAboveAll1964/ft-app, Apache-2.0, app/ft/iccoa) and
docs/plans/iccoa-carlink.md. Needs the `cryptography` package (P-256, HKDF, AES-GCM).
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import struct
import time
from pathlib import Path
from typing import NamedTuple

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from carlife_hu import pb_bytes, pb_int, pb_parse

HEADER_SIZE = 20
MAX_FRAME = 4_000_000
CAT_CONTROL, CAT_SENSOR, CAT_AUTH, CAT_PCM, CAT_ACK = 1, 2, 3, 4, 7
T_NOTIFY, T_REQUEST, T_RESPONSE, T_NEED_ACK = 0, 1, 2, 3
# результат auth (поле 7 ответа)
RESULT_OK, RESULT_NEED_PIN, RESULT_CONFIRM_ON_CAR, RESULT_INTERNAL, RESULT_WORK_MODE = 0, 1, 2, 3, 4
WORK_MODES = 769                    # normal cast + mirror window + launcher window, как у XA60 в тестах FT
VIDEO_PID, AUDIO_PID = 256, 257
KEY_HOME, KEY_BACK = 3, 4           # Android keycode в UIBC


# ---- кадр: заголовок 20 байт BE, CRC-16/ARC по байтам 0..17 ----

class Frame(NamedTuple):
    seq: int
    cat: int
    method: int
    type: int
    fmt: int
    body: bytes


def crc16(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def frame(cat: int, method: int, type_: int, body: bytes = b"", seq: int = 0, fmt: int = 1, t: int | None = None) -> bytes:
    h = struct.pack(">IIIBBHH", HEADER_SIZE + len(body), seq & 0xFFFFFFFF, int(time.time()) if t is None else t,
                    type_ << 3 | fmt << 1 | 1, cat, method, 0)
    return h + struct.pack(">H", crc16(h)) + body


def read_frame(f) -> Frame | None:
    """f.read(n) блокирующий (socket.makefile / BytesIO). None - чистый EOF между кадрами; обрыв внутри кадра - EOFError,
    битый CRC или длина - ValueError. Младший бит флагов не проверяем: XA60 шлёт 0x12."""
    h = f.read(HEADER_SIZE)
    if not h:
        return None
    if len(h) < HEADER_SIZE:
        raise EOFError("truncated ICCOA header")
    length, seq, _, flags, cat, method = struct.unpack(">IIIBBH", h[:16])
    if not HEADER_SIZE <= length <= MAX_FRAME:
        raise ValueError(f"ICCOA frame length {length}")
    if crc16(h[:18]) != struct.unpack(">H", h[18:])[0]:
        raise ValueError("ICCOA header CRC")
    body = f.read(length - HEADER_SIZE)
    if len(body) < length - HEADER_SIZE:
        raise EOFError("truncated ICCOA body")
    return Frame(seq, cat, method, flags >> 3, flags >> 1 & 3, body)


# ---- AES-128-GCM конверты [int32 12][iv][int32 len][ct+tag], подряд может идти несколько ----

class Cipher:
    def __init__(self, key: bytes):
        if len(key) != 16:
            raise ValueError("session key must be 16 bytes")
        self.aes = AESGCM(key)

    def encrypt(self, plain: bytes) -> bytes:
        iv = os.urandom(12)
        ct = self.aes.encrypt(iv, plain, None)
        return struct.pack(">I", 12) + iv + struct.pack(">I", len(ct)) + ct

    def decrypt(self, env: bytes) -> bytes:
        out, p = b"", 0
        while p < len(env):
            if len(env) - p < 36 or env[p:p + 4] != b"\x00\x00\x00\x0c":
                raise ValueError("bad ICCOA GCM envelope")
            iv = env[p + 4:p + 16]
            n = struct.unpack(">I", env[p + 16:p + 20])[0]
            if not 16 <= n <= len(env) - p - 20:
                raise ValueError("bad ICCOA GCM length")
            try:
                out += self.aes.decrypt(iv, env[p + 20:p + 20 + n], None)
            except InvalidTag:
                raise ValueError("ICCOA GCM tag mismatch") from None
            p += 20 + n
        return out


def is_encrypted(body: bytes) -> bool:
    return body[:4] == b"\x00\x00\x00\x0c"


# ---- protobuf-минимум (писатели и разбор - из carlife_hu) ----

def fields(body: bytes) -> dict[int, object]:
    """{поле: int | bytes}; ValueError на мусоре."""
    parsed = pb_parse(body)
    if parsed is None:
        raise ValueError("not a protobuf")
    return {f: (v if w == 0 else bytes(v)) for f, w, v in parsed}


def shape(body: bytes) -> str:
    """Поля для лога: числа как есть, строки и блобы только длиной."""
    try:
        return " ".join(f"f{n}={v if isinstance(v, int) else f'bytes({len(v)})'}" for n, v in fields(body).items())
    except ValueError:
        return f"raw({len(body)})"


# ---- auth: роль машины (сервер 57209) ----

CURVE = ec.SECP256R1()


def new_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(CURVE)


def pub_bytes(key: ec.EllipticCurvePrivateKey) -> bytes:
    """Открытый ключ как X‖Y по 32 байта."""
    n = key.public_key().public_numbers()
    return n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")


def pub_key(b: bytes) -> ec.EllipticCurvePublicKey:
    if len(b) != 64:
        raise ValueError("public key must be 64 bytes")
    return ec.EllipticCurvePublicNumbers(int.from_bytes(b[:32], "big"), int.from_bytes(b[32:], "big"), CURVE).public_key()


def pin_mac(pin: str, message: bytes, salt: bytes) -> bytes:
    """HMAC-SHA256(key=SHA256(salt‖PIN), message)."""
    return hmac.new(hashlib.sha256(salt + pin.encode("utf-8")).digest(), message, hashlib.sha256).digest()


def sign(key: ec.EllipticCurvePrivateKey, data: bytes) -> bytes:
    return key.sign(data, ec.ECDSA(hashes.SHA256()))        # DER


def verify(pub: bytes, signature: bytes, data: bytes) -> bool:
    try:
        pub_key(pub).verify(signature, data, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


def session_key(key: ec.EllipticCurvePrivateKey, peer_pub: bytes, nonce_c: bytes, nonce_s: bytes) -> bytes:
    """ECDH -> HKDF-SHA256(salt=SHA256(nonceC‖nonceS), info="session_key", 16 байт)."""
    shared = key.exchange(ec.ECDH(), pub_key(peer_pub))
    return HKDF(hashes.SHA256(), 16, hashlib.sha256(nonce_c + nonce_s).digest(), b"session_key").derive(shared)


class Trust:
    """Постоянный ключ машины и доверенные клиенты (clientId -> authPub) в JSON; path=None - только в памяти.
    В файле лежит закрытый ключ фейковой машины: держать вне гита (по умолчанию pc-headunit/out/)."""

    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else None
        data = json.loads(self.path.read_text("utf-8")) if self.path and self.path.exists() else {}
        self.clients: dict[str, str] = data.get("clients", {})
        if "car_key" in data:
            self.key = ec.derive_private_key(int(data["car_key"], 16), CURVE)
        else:
            self.key = new_key()
            self.save()

    def save(self):
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"car_key": f"{self.key.private_numbers().private_value:x}",
                                             "clients": self.clients}, indent=1), "utf-8")


class AuthSession(NamedTuple):
    cipher: Cipher
    client_id: str
    client_pub: bytes
    quick: bool


class CarAuth:
    """Машина: проверяет вход телефона (cat 3 m1), отвечает (m2) и принимает Confirm (m3)."""

    def __init__(self, pin: str, trust: Trust, force_result: int = 0):
        self.pin, self.trust, self.force_result = pin, trust, force_result

    def respond(self, body: bytes) -> tuple[bytes, AuthSession | None]:
        """-> (тело ответа, сессия или None при отказе). Вход с PIN: f2/f3 есть; быстрый: их нет, ключ клиента из Trust."""
        def reject(code: int):
            return pb_int(1, 1793) + pb_int(7, code), None
        if self.force_result:
            return reject(self.force_result)
        try:
            f = fields(body)
            agree_c, sig_c, nonce_c = f[4], f[5], f[6]
            client_id = f.get(7, b"").decode("utf-8", "replace")
            quick = 2 not in f
            if quick:
                known = self.trust.clients.get(client_id)
                if not known:
                    return reject(RESULT_NEED_PIN)
                auth_c = bytes.fromhex(known)
            else:
                auth_c = f[2]
                if not hmac.compare_digest(f[3], pin_mac(self.pin, auth_c, nonce_c)):
                    return reject(RESULT_NEED_PIN)
            if not verify(auth_c, sig_c, agree_c + nonce_c):
                return reject(RESULT_NEED_PIN if quick else RESULT_INTERNAL)
            agree, nonce_s, car_pub = new_key(), os.urandom(32), pub_bytes(self.trust.key)
            agree_s = pub_bytes(agree)
            resp = pb_int(1, 1793) + pb_bytes(2, car_pub)
            if not quick:
                resp += pb_bytes(3, pin_mac(self.pin, car_pub + auth_c, nonce_s + nonce_c))
            resp += (pb_bytes(4, agree_s) + pb_bytes(5, sign(self.trust.key, agree_s + agree_c + nonce_s + nonce_c))
                     + pb_bytes(6, nonce_s) + pb_int(7, RESULT_OK) + pb_int(8, WORK_MODES))
            return resp, AuthSession(Cipher(session_key(agree, agree_c, nonce_c, nonce_s)), client_id, auth_c, quick)
        except (KeyError, ValueError, TypeError, AttributeError):
            return reject(RESULT_INTERNAL)

    def confirm(self, body: bytes, s: AuthSession) -> str:
        """Confirm: f1 = encrypt("<ip телефона>:0"). Расшифровалось - ключ общий, клиента запоминаем. -> адрес."""
        addr = s.cipher.decrypt(fields(body)[1]).decode("utf-8")
        self.trust.clients[s.client_id] = s.client_pub.hex()
        self.trust.save()
        return addr


# ---- control (cat 1) и UIBC ----

def heartbeat_body() -> bytes:
    return pb_int(1, int(time.time() * 1000))


def car_config(dpi: int, hevc: bool = False) -> bytes:
    """m25: f18 workModes, f19 POI, f20 поворот, f21 Home, f22 Back, f23 HEVC, f24 DPI."""
    return (pb_int(18, WORK_MODES) + pb_int(19, 0) + pb_int(20, 0) + pb_int(21, 1) + pb_int(22, 1)
            + pb_int(23, int(hevc)) + pb_int(24, dpi))


def key_body(keycode: int, up: bool) -> bytes:
    """m12: f1 (1 = отпущена), f2 Android keycode."""
    return pb_int(1, int(up)) + pb_int(2, keycode)


def uibc_touch(action: int, x: int, y: int, w: int, h: int) -> bytes:
    """Один палец; action 0 down, 1 up, 2 move; координаты в пикселях экрана машины w x h."""
    return json.dumps({"type": 1, "action": action, "count": 1, "x0": x, "y0": y, "trackID0": 0,
                       "width": w, "height": h}, separators=(",", ":")).encode()


def uibc_key(keycode: int, up: bool) -> bytes:
    return json.dumps({"type": 2, "action": int(up), "keycode": keycode}, separators=(",", ":")).encode()


# ---- RTSP: машина = WFD-sink, клиент TCP на 7236; первым говорит телефон (M1) ----

class RtspMsg(NamedTuple):
    first: str
    headers: dict[str, str]
    body: str


class RtspParser:
    def __init__(self):
        self.buf = b""

    def feed(self, data: bytes) -> list[RtspMsg]:
        self.buf += data
        out = []
        while True:
            end = self.buf.find(b"\r\n\r\n")
            if end < 0:
                return out
            lines = self.buf[:end].decode("latin-1").split("\r\n")
            headers = {k.strip().lower(): v.strip() for k, _, v in (line.partition(":") for line in lines[1:])}
            n = int(headers.get("content-length", 0))
            if len(self.buf) < end + 4 + n:
                return out
            out.append(RtspMsg(lines[0], headers, self.buf[end + 4:end + 4 + n].decode("latin-1")))
            self.buf = self.buf[end + 4 + n:]


def rtsp_msg(first: str, cseq: int | str, body: str = "", extra: str = "") -> bytes:
    data = body.encode("latin-1")
    head = f"{first}\r\nCSeq: {cseq}\r\n{extra}"
    if data:
        head += f"Content-Type: text/parameters\r\nContent-Length: {len(data)}\r\n"
    return (head + "\r\n").encode("latin-1") + data


class RtspSink:
    """Автомат sink без сокетов: feed(сообщение телефона) -> байты к отправке.
    M1 OPTIONS -> 200 + наш M2 OPTIONS; M3 GET_PARAMETER -> параметры экрана; M4 -> 200; M5 trigger SETUP -> 200 +
    наш M6 SETUP; ответ на M6 -> M7 PLAY; ответ на M7 -> playing. GET_PARAMETER без тела (M16) -> 200."""

    def __init__(self, width: int, height: int, dpi: int, fps: int, rtp_port: int, host: str = "localhost"):
        self.url = f"rtsp://{host}/wfd1.0/streamid=0"
        self.rtp_port = rtp_port
        # shortcut: wfd_video_formats и iccoa_support_hevc - догадка (настоящего ответа машины нет), FT их не читает
        self.params = {
            "wfd_video_formats": "00 00 02 10 0001ffff 3fffffff 00000fff 00 0000 0000 00 none none",
            "wfd_audio_codecs": "AAC 00000001 00",
            "wfd_client_rtp_ports": f"RTP/AVP/TCP;unicast {rtp_port} 0 mode=play",
            "wfd_uibc_capability": "input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;"
                                   "hidc_cap_list=none;port=none",
            "wfd_car_display_mode": f"display={width}:{height};dpi={dpi};fps={fps}",
            "iccoa_support_hevc": "0",
        }
        self.cseq = 1
        self.pending: dict[str, str] = {}
        self.session = "1"
        self.sent_options = False
        self.playing = False

    def _request(self, kind: str, first: str, body: str = "", extra: str = "") -> bytes:
        cseq, self.cseq = self.cseq, self.cseq + 1
        self.pending[str(cseq)] = kind
        return rtsp_msg(first, cseq, body, extra)

    def feed(self, m: RtspMsg) -> list[bytes]:
        cseq = m.headers.get("cseq", "0")
        if m.first.startswith("RTSP/"):
            kind = self.pending.pop(cseq, None)
            if kind is None or m.first.split()[1:2] != ["200"]:
                return []
            if kind == "SETUP":
                self.session = m.headers.get("session", "1").split(";")[0]
                return [self._request("PLAY", f"PLAY {self.url} RTSP/1.0", extra=f"Session: {self.session}\r\n")]
            if kind == "PLAY":
                self.playing = True
            return []
        method = m.first.split(" ")[0]
        if method == "OPTIONS":
            out = [rtsp_msg("RTSP/1.0 200 OK", cseq, extra="Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")]
            if not self.sent_options:
                self.sent_options = True
                out.append(self._request("OPTIONS", "OPTIONS * RTSP/1.0", extra="Require: org.wfa.wfd1.0\r\n"))
            return out
        if method == "GET_PARAMETER":
            names = [line.strip().rstrip(":") for line in m.body.splitlines() if line.strip()]
            return [rtsp_msg("RTSP/1.0 200 OK", cseq, "".join(f"{n}: {self.params.get(n, 'none')}\r\n" for n in names))]
        if method == "SET_PARAMETER":
            out = [rtsp_msg("RTSP/1.0 200 OK", cseq)]
            if "wfd_trigger_method: SETUP" in m.body:
                out.append(self._request("SETUP", f"SETUP {self.url} RTSP/1.0",
                                         extra=f"Transport: RTP/AVP/TCP;unicast;client_port={self.rtp_port}\r\n"))
            return out
        return [rtsp_msg("RTSP/1.0 405 Method Not Allowed", cseq)]

    def idr_request(self) -> bytes:
        return self._request("IDR", "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "wfd_idr_request\r\n",
                             f"Session: {self.session}\r\n")


# ---- медиа: RFC 4571 -> RTP PT 33 -> MPEG-TS -> элементарные потоки ----

class Rfc4571:
    """TCP-поток -> пакеты RTP (2 байта длины BE перед каждым)."""

    def __init__(self):
        self.buf = b""

    def feed(self, data: bytes) -> list[bytes]:
        self.buf += data
        out, p = [], 0
        while len(self.buf) - p >= 2:
            n = struct.unpack(">H", self.buf[p:p + 2])[0]
            if len(self.buf) - p - 2 < n:
                break
            out.append(self.buf[p + 2:p + 2 + n])
            p += 2 + n
        self.buf = self.buf[p:]
        return out


def parse_rtp(pkt: bytes) -> tuple[int, bool, bytes]:
    """-> (payload type, marker, payload). shortcut: padding (бит P) не срезаем - FT его не ставит."""
    if len(pkt) < 12 or pkt[0] >> 6 != 2:
        raise ValueError("not an RTP packet")
    off = 12 + 4 * (pkt[0] & 15)
    if pkt[0] & 0x10:
        off += 4 + 4 * struct.unpack(">H", pkt[off + 2:off + 4])[0]
    return pkt[1] & 0x7F, bool(pkt[1] & 0x80), pkt[off:]


class TsDemux:
    """TS-пакеты -> [(pid, access unit)] для PID видео и аудио; PAT/PMT не читаем - PID фиксированы спецификацией.
    Единица отдаётся на старте следующего PES того же PID или при end=True (маркер RTP = конец access unit у FT)."""

    def __init__(self, pids=(VIDEO_PID, AUDIO_PID)):
        self.buf: dict[int, bytearray | None] = {pid: None for pid in pids}    # None - ждём начало PES
        self.bad = 0

    def feed(self, data: bytes, end: bool = False) -> list[tuple[int, bytes]]:
        out = []
        for p in range(0, len(data) - 187, 188):
            pkt = data[p:p + 188]
            pid = (pkt[1] & 0x1F) << 8 | pkt[2]
            if pkt[0] != 0x47:
                self.bad += 1
                continue
            if pid not in self.buf or not pkt[3] & 0x10:
                continue
            off = 5 + pkt[4] if pkt[3] & 0x20 else 4            # adaptation field
            payload = pkt[off:]
            if pkt[1] & 0x40:                                   # начало PES: 00 00 01 id len(2) flags(2) hdrlen
                self._flush(pid, out)
                if payload[:3] != b"\x00\x00\x01" or len(payload) < 9:
                    self.bad += 1
                    continue
                self.buf[pid] = bytearray()
                payload = payload[9 + payload[8]:]
            if self.buf[pid] is not None:
                self.buf[pid] += payload
        if end:
            for pid in self.buf:
                self._flush(pid, out)
        return out

    def _flush(self, pid: int, out: list):
        if self.buf[pid]:
            out.append((pid, bytes(self.buf[pid])))
            self.buf[pid] = bytearray()
