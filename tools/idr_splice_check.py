"""Проверка своего IDR (android/.../aa/IdrSplice.kt) на дампе H.264 от AA, без телефона.

python -X utf8 tools/idr_splice_check.py <дамп.h264> [--every 60] [--pcm-only]

Тот же алгоритм, что в приложении: каждые N кадров после опорного кадра AA вставляется IDR из его декодированной картинки
(I_PCM / I16x16 без остатка), frame_num/POC следующих P-кадров сдвигаются. ffmpeg декодирует исходный и склеенный поток,
кадры сравниваются по md5, плюс «поздний вход» (ГУ потеряла всё до нашего IDR). Печатает параметры SPS/PPS, размеры IDR
(лимит 560 000 Б - буфер C++-ГУ) и итог. Нужны numpy и ffmpeg в PATH.
"""
import argparse
import hashlib
import os
import subprocess
import sys
import tempfile
import numpy as np


# ---------- Annex-B ----------
def split_nals(data: bytes):
    out, i, n = [], 0, len(data)
    starts = []
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        starts.append(j + 3)
        i = j + 3
    for k, s in enumerate(starts):
        e = starts[k + 1] - 3 if k + 1 < len(starts) else n
        nal = data[s:e]
        while nal.endswith(b"\x00"):
            nal = nal[:-1]
        out.append(nal)
    return out


def unescape(nal: bytes) -> bytearray:
    out, z = bytearray(), 0
    for v in nal:
        if z >= 2 and v == 3:
            z = 0
            continue
        out.append(v)
        z = z + 1 if v == 0 else 0
    return out


def escape(rbsp: bytes) -> bytes:
    out, z = bytearray(), 0
    for v in rbsp:
        if z >= 2 and v <= 3:
            out.append(3)
            z = 0
        out.append(v)
        z = z + 1 if v == 0 else 0
    return bytes(out)


class BR:
    def __init__(self, d):
        self.d, self.pos = d, 0

    def bit(self):
        b = (self.d[self.pos >> 3] >> (7 - (self.pos & 7))) & 1
        self.pos += 1
        return b

    def u(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | self.bit()
        return v

    def ue(self):
        z = 0
        while self.bit() == 0:
            z += 1
        return (1 << z) - 1 + self.u(z)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


class BW:
    def __init__(self):
        self.out, self.cur, self.n = bytearray(), 0, 0

    def u(self, n, v):
        for k in range(n - 1, -1, -1):
            self.cur = (self.cur << 1) | ((v >> k) & 1)
            self.n += 1
            if self.n == 8:
                self.out.append(self.cur)
                self.cur, self.n = 0, 0

    def ue(self, v):
        x = v + 1
        ln = x.bit_length()
        self.u(ln - 1, 0)
        self.u(ln, x)

    def se(self, v):
        self.ue(2 * v - 1 if v > 0 else -2 * v)

    def align0(self):
        while self.n:
            self.u(1, 0)

    def bytes_aligned(self, b):
        assert self.n == 0
        self.out += b

    def trailing(self):
        self.u(1, 1)
        self.align0()
        return bytes(self.out)


def write_bits(buf: bytearray, pos, n, v):
    for k in range(n):
        b = (v >> (n - 1 - k)) & 1
        p = pos + k
        if b:
            buf[p >> 3] |= 0x80 >> (p & 7)
        else:
            buf[p >> 3] &= ~(0x80 >> (p & 7)) & 0xFF


# ---------- SPS / PPS ----------
HIGH = {100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135}


def parse_sps(nal):
    r = BR(unescape(nal))
    r.u(8)
    s = {"profile": r.u(8)}
    r.u(16)
    s["id"] = r.ue()
    s["chroma"] = 1
    if s["profile"] in HIGH:
        s["chroma"] = r.ue()
        assert s["chroma"] == 1
        assert r.ue() == 0 and r.ue() == 0, "только 8 бит"
        r.u(1)
        assert r.u(1) == 0, "scaling matrix"
    s["log2_fn"] = r.ue() + 4
    s["poc_type"] = r.ue()
    if s["poc_type"] == 0:
        s["log2_poc"] = r.ue() + 4
    elif s["poc_type"] == 1:
        s["dpo_zero"] = r.u(1)
        r.se(); r.se()
        for _ in range(r.ue()):
            r.se()
    s["max_ref"] = r.ue()
    s["gaps"] = r.u(1)
    s["w_mbs"] = r.ue() + 1
    s["h_mbs"] = r.ue() + 1
    s["frame_mbs_only"] = r.u(1)
    assert s["frame_mbs_only"] == 1
    r.u(1)                              # direct_8x8_inference_flag
    s["crop_pos"] = r.pos
    s["crop"] = [r.ue() for _ in range(4)] if r.u(1) else [0, 0, 0, 0]
    s["after_crop"] = r.pos
    return s


def uncrop_sps(nal):
    """SPS без кропа (как SpsCrop.uncrop): декодер отдаёт кадр кодированного размера со скрытыми, но опорными строками."""
    s = parse_sps(nal)
    if s["crop"] == [0, 0, 0, 0]:
        return nal
    rb = unescape(nal)
    bits = [(rb[i >> 3] >> (7 - (i & 7))) & 1 for i in range(len(rb) * 8)]
    stop = max(i for i, b in enumerate(bits) if b)
    out = bits[:s["crop_pos"]] + [0] + bits[s["after_crop"]:stop] + [1]
    out += [0] * (-len(out) % 8)
    return escape(bytes(int("".join(map(str, out[i:i + 8])), 2) for i in range(0, len(out), 8)))


def parse_pps(nal):
    r = BR(unescape(nal))
    r.u(8)
    p = {"id": r.ue(), "sps": r.ue(), "cabac": r.u(1), "bottom_poc": r.u(1)}
    assert r.ue() == 0, "slice groups"
    r.ue(); r.ue()
    r.u(1); r.u(2)
    p["init_qp"] = 26 + r.se()
    r.se()
    p["cqp_off"] = r.se()
    p["dbk_ctrl"] = r.u(1)
    p["constrained_intra"] = r.u(1)
    p["redundant"] = r.u(1)
    return p


# ---------- заголовок слайса ----------
def slice_fields(rbsp, sps, pps_map, nal_type):
    """Позиции frame_num и pic_order_cnt_lsb в RBSP слайса."""
    r = BR(rbsp)
    r.u(8)
    first_mb = r.ue()
    r.ue()
    pps = pps_map[r.ue()]
    fn_pos = r.pos
    fn = r.u(sps["log2_fn"])
    if nal_type == 5:
        r.ue()
    poc_pos = poc = None
    if sps["poc_type"] == 0:
        poc_pos = r.pos
        poc = r.u(sps["log2_poc"])
    return first_mb, fn_pos, fn, poc_pos, poc


# ---------- предсказание intra 16x16 и chroma ----------
def clip(a):
    return np.clip(a, 0, 255)


def luma_preds(Y, mx, my):
    x0, y0 = mx * 16, my * 16
    top = Y[y0 - 1, x0:x0 + 16].astype(np.int32) if my > 0 else None
    left = Y[y0:y0 + 16, x0 - 1].astype(np.int32) if mx > 0 else None
    preds = {}
    if top is not None:
        preds[0] = np.tile(top, (16, 1))
    if left is not None:
        preds[1] = np.tile(left[:, None], (1, 16))
    if top is not None and left is not None:
        dc = (top.sum() + left.sum() + 16) >> 5
    elif left is not None:
        dc = (left.sum() + 8) >> 4
    elif top is not None:
        dc = (top.sum() + 8) >> 4
    else:
        dc = 128
    preds[2] = np.full((16, 16), dc, np.int32)
    if top is not None and left is not None:
        tl = int(Y[y0 - 1, x0 - 1])
        pt = lambda x: tl if x < 0 else int(top[x])
        pl = lambda y: tl if y < 0 else int(left[y])
        H = sum((i + 1) * (pt(8 + i) - pt(6 - i)) for i in range(8))
        V = sum((i + 1) * (pl(8 + i) - pl(6 - i)) for i in range(8))
        a = 16 * (int(left[15]) + int(top[15]))
        b = (5 * H + 32) >> 6
        c = (5 * V + 32) >> 6
        yy, xx = np.mgrid[0:16, 0:16]
        preds[3] = clip((a + b * (xx - 7) + c * (yy - 7) + 16) >> 5)
    return preds


def chroma_pred(C, mx, my, mode):
    x0, y0 = mx * 8, my * 8
    top = C[y0 - 1, x0:x0 + 8].astype(np.int32) if my > 0 else None
    left = C[y0:y0 + 8, x0 - 1].astype(np.int32) if mx > 0 else None
    if mode == 0:
        p = np.zeros((8, 8), np.int32)
        for yo in (0, 4):
            for xo in (0, 4):
                t = top[xo:xo + 4].sum() if top is not None else None
                l = left[yo:yo + 4].sum() if left is not None else None
                if (xo == 0 and yo == 0) or (xo > 0 and yo > 0):
                    v = (t + l + 4) >> 3 if t is not None and l is not None else \
                        (l + 2) >> 2 if l is not None else (t + 2) >> 2 if t is not None else 128
                elif xo > 0:
                    v = (t + 2) >> 2 if t is not None else (l + 2) >> 2 if l is not None else 128
                else:
                    v = (l + 2) >> 2 if l is not None else (t + 2) >> 2 if t is not None else 128
                p[yo:yo + 4, xo:xo + 4] = v
        return p
    if mode == 1:
        return None if left is None else np.tile(left[:, None], (1, 8))
    if mode == 2:
        return None if top is None else np.tile(top, (8, 1))
    if top is None or left is None:
        return None
    tl = int(C[y0 - 1, x0 - 1])
    pt = lambda x: tl if x < 0 else int(top[x])
    pl = lambda y: tl if y < 0 else int(left[y])
    H = sum((i + 1) * (pt(4 + i) - pt(2 - i)) for i in range(4))
    V = sum((i + 1) * (pl(4 + i) - pl(2 - i)) for i in range(4))
    a = 16 * (int(left[7]) + int(top[7]))
    b = (34 * H + 32) >> 6
    c = (34 * V + 32) >> 6
    yy, xx = np.mgrid[0:8, 0:8]
    return clip((a + b * (xx - 3) + c * (yy - 3) + 16) >> 5)


# ---------- сборка IDR ----------
def build_idr(Y, U, V, sps, pps, idr_pic_id, use_i16=True):
    w, h = sps["w_mbs"], sps["h_mbs"]
    bw = BW()
    bw.u(8, 0x65)                       # nal_ref_idc 3, IDR
    bw.ue(0)                            # first_mb_in_slice
    bw.ue(7)                            # slice_type I (все слайсы)
    bw.ue(pps["id"])
    bw.u(sps["log2_fn"], 0)             # frame_num
    bw.ue(idr_pic_id)
    if sps["poc_type"] == 0:
        bw.u(sps["log2_poc"], 0)
        if pps["bottom_poc"]:
            bw.se(0)
    elif sps["poc_type"] == 1 and not sps["dpo_zero"]:
        bw.se(0)
        if pps["bottom_poc"]:
            bw.se(0)
    if pps["redundant"]:
        bw.ue(0)
    bw.u(1, 0); bw.u(1, 0)              # no_output_of_prior_pics, long_term_reference
    bw.se(0 - pps["init_qp"])           # QP = 0: у I16x16 деблокинг не срабатывает (alpha(0) = 0)
    if pps["dbk_ctrl"]:
        bw.ue(1)                        # disable_deblocking_filter_idc
    assert not pps["cabac"], "CABAC: нужен свой кодер mb_type"
    kind = np.zeros((h, w), np.int8)    # 1 = I_PCM, 2 = I16x16 без остатка
    n_i16 = 0
    for my in range(h):
        for mx in range(w):
            blk = Y[my * 16:my * 16 + 16, mx * 16:mx * 16 + 16].astype(np.int32)
            cu = U[my * 8:my * 8 + 8, mx * 8:mx * 8 + 8].astype(np.int32)
            cv = V[my * 8:my * 8 + 8, mx * 8:mx * 8 + 8].astype(np.int32)
            lm = cm = None
            if use_i16:
                for m, p in luma_preds(Y, mx, my).items():
                    if np.array_equal(p, blk):
                        lm = m
                        break
                if lm is not None:
                    for m in range(4):
                        pu, pv = chroma_pred(U, mx, my, m), chroma_pred(V, mx, my, m)
                        if pu is not None and np.array_equal(pu, cu) and np.array_equal(pv, cv):
                            cm = m
                            break
            if lm is not None and cm is not None:
                kind[my, mx] = 2
                n_i16 += 1
                bw.ue(1 + lm)           # I_16x16_<lm>_0_0
                bw.ue(cm)               # intra_chroma_pred_mode
                bw.se(0)                # mb_qp_delta
                na = (16 if kind[my, mx - 1] == 1 else 0) if mx > 0 else None
                nb = (16 if kind[my - 1, mx] == 1 else 0) if my > 0 else None
                nc = (na + nb + 1) >> 1 if na is not None and nb is not None else \
                    na if na is not None else nb if nb is not None else 0
                if nc < 2:   bw.u(1, 1)          # coeff_token TotalCoeff 0
                elif nc < 4: bw.u(2, 3)
                elif nc < 8: bw.u(4, 15)
                else:        bw.u(6, 3)
            else:
                kind[my, mx] = 1
                bw.ue(25)               # I_PCM
                bw.align0()
                bw.bytes_aligned(blk.astype(np.uint8).tobytes() + cu.astype(np.uint8).tobytes() +
                                 cv.astype(np.uint8).tobytes())
    rbsp = bw.trailing()
    return escape(rbsp), n_i16


# ---------- склейка ----------
def read_yuv(path, w, h):
    raw = np.fromfile(path, np.uint8)
    fs = w * h * 3 // 2
    n = len(raw) // fs
    out = []
    for i in range(n):
        f = raw[i * fs:(i + 1) * fs]
        out.append((f[:w * h].reshape(h, w), f[w * h:w * h * 5 // 4].reshape(h // 2, w // 2),
                    f[w * h * 5 // 4:].reshape(h // 2, w // 2)))
    return out


def splice(data, frames, every, use_i16=True, first=None):
    nals = split_nals(data)
    sps_map, pps_map = {}, {}
    out = bytearray()
    pic = -1
    fn_off = poc_off = None             # сдвиг frame_num / POC после нашего IDR, до IDR от AA
    idr_id = 0
    splices = []
    skip_pic = False
    next_at = first if first is not None else every
    for nal in nals:
        t = nal[0] & 0x1F
        if t == 7:
            s = parse_sps(nal); sps_map[s["id"]] = s
        elif t == 8:
            p = parse_pps(nal); pps_map[p["id"]] = p
        if t in (1, 5):
            rb = unescape(nal)
            pps_id = BR(rb); pps_id.u(8); fm = pps_id.ue(); pps_id.ue(); pps_id = pps_id.ue()
            pps = pps_map[pps_id]; sps = sps_map[pps["sps"]]
            first_mb, fn_pos, fn, poc_pos, poc = slice_fields(rb, sps, pps_map, t)
            if first_mb == 0:
                pic += 1
                skip_pic = False
                if t == 5:
                    fn_off = poc_off = None
                elif pic >= next_at and (nal[0] >> 5) != 0:
                    Y, U, V = frames[pic]
                    idr, n16 = build_idr(Y, U, V, sps, pps, idr_id & 0xFFFF, use_i16)
                    idr_id += 1
                    out += b"\x00\x00\x00\x01" + idr
                    fn_off = fn
                    poc_off = poc
                    splices.append((pic, len(out) - len(idr) - 4, len(idr), n16))
                    next_at = pic + every
                    skip_pic = True
            if skip_pic:
                continue
            if fn_off is not None and t == 1:
                write_bits(rb, fn_pos, sps["log2_fn"], (fn - fn_off) % (1 << sps["log2_fn"]))
                if poc_pos is not None:
                    write_bits(rb, poc_pos, sps["log2_poc"], (poc - poc_off) % (1 << sps["log2_poc"]))
                nal = escape(rb)
        out += b"\x00\x00\x00\x01" + nal
    return bytes(out), splices, sps_map, pps_map


def _decode(path, out):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-err_detect", "explode", "-i", path,
                    "-f", "rawvideo", "-pix_fmt", "yuv420p", out], check=True)


def _md5(path):
    """md5 видимых кадров (с кропом SPS), как их покажет ГУ."""
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-err_detect", "explode", "-i", path, "-f", "framemd5", "-"],
                       check=True, capture_output=True, text=True)
    return [line.split(",")[-1].strip() for line in r.stdout.splitlines() if line and not line.startswith("#")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stream")
    ap.add_argument("--every", type=int, default=60, help="IDR каждые N кадров (60 = 2 с при 30 fps)")
    ap.add_argument("--pcm-only", action="store_true", help="только I_PCM, без I16x16")
    a = ap.parse_args()
    data = open(a.stream, "rb").read()
    nals = split_nals(data)
    sps = next((parse_sps(n) for n in nals if n[0] & 0x1F == 7), None)
    pps = next((parse_pps(n) for n in nals if n[0] & 0x1F == 8), None)
    if sps is None or pps is None:
        sys.exit("в потоке нет SPS/PPS")
    print(f"SPS: profile_idc={sps['profile']} {sps['w_mbs'] * 16}x{sps['h_mbs'] * 16} кроп={sps['crop']} poc_type={sps['poc_type']} "
          f"log2_max_frame_num={sps['log2_fn']} max_num_ref_frames={sps['max_ref']}")
    print(f"PPS: {'CABAC' if pps['cabac'] else 'CAVLC'} deblocking_control={pps['dbk_ctrl']}")
    if pps["cabac"] or sps["max_ref"] > 1:
        print("!!! поток не подходит: приложение свой IDR в такой поток не вставит")
    w, h = sps["w_mbs"] * 16, sps["h_mbs"] * 16
    with tempfile.TemporaryDirectory() as tmp:
        ref_yuv, full_h264, out_h264 = (os.path.join(tmp, n) for n in ("ref.yuv", "full.h264", "out.h264"))
        # картинки для своего IDR - кодированного размера, как у декодера телефона с SPS без кропа
        open(full_h264, "wb").write(b"".join(b"\x00\x00\x00\x01" + (uncrop_sps(n) if n[0] & 0x1F == 7 else n) for n in nals))
        _decode(full_h264, ref_yuv)
        ref = read_yuv(ref_yuv, w, h)
        spl, splices, _, _ = splice(data, ref, a.every, not a.pcm_only)
        open(out_h264, "wb").write(spl)
        sizes = [sz for _, _, sz, _ in splices]
        if not sizes:
            sys.exit("ни одного IDR не вставлено (поток короче --every?)")
        big = sum(s > 560_000 for s in sizes)
        print(f"IDR вставлено {len(sizes)}: мин {min(sizes)} Б, среднее {sum(sizes) // len(sizes)} Б, макс {max(sizes)} Б, "
              f"больше 560 000 Б: {big}; I16x16 в среднем {sum(n for *_, n in splices) // len(splices)}/{sps['w_mbs'] * sps['h_mbs']}")
        want, got = _md5(a.stream), _md5(out_h264)
        ok = sum(x == y for x, y in zip(want, got))
        print(f"склейка: кадров {len(got)}/{len(want)}, совпало {ok}")
        hdr = b"".join(b"\x00\x00\x00\x01" + n for n in nals if n[0] & 0x1F in (7, 8))
        late_ok = True
        for pic, off, _, _ in splices[:3]:
            late = os.path.join(tmp, "late.h264")
            open(late, "wb").write(hdr + spl[off:])
            tail = _md5(late)
            m = sum(x == y for x, y in zip(want[pic:], tail))
            late_ok = late_ok and m == len(want) - pic == len(tail)
            print(f"  вход с IDR вместо кадра {pic}: совпало {m} из {len(want) - pic}")
        good = ok == len(want) == len(got) and late_ok
        print("ИТОГ: " + ("бит-в-бит, подходит" if good else "!!! расхождение"))
        sys.exit(0 if good else 1)


if __name__ == "__main__":
    main()
