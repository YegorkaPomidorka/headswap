"""Tests for the live window of the CarLife HU emulator (--view): coordinates, touch packing, size negotiation, ffmpeg pipeline.

    python -m pytest pc-headunit/tests/test_carlife_view.py

The window itself is not opened (needs a display); everything under it is tested.
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import carlife_hu as C                                           # noqa: E402
import carlife_view as V                                         # noqa: E402
from test_carlife_hu import MD_INFO_BODY, FakeBridge, start_hu   # noqa: E402


def test_parse_size():
    assert V.parse_size("1920x720") == (1920, 720)
    assert V.parse_size(" 1280X480 ") == (1280, 480)
    for bad in ("", "1920", "1920x", "axb", "8x8", "100000x720", "-1x5"):
        with pytest.raises(ValueError):
            V.parse_size(bad)
    assert C.parse_args(["--size", "1280x480"]).size == (1280, 480)
    assert C.parse_args([]).size == (1920, 720)


def test_window_to_video_scaled():
    # окно 1440x540 для видео 1920x720 (scale 0.75): клик пересчитывается обратно
    assert V.display_size(1920, 720, 0.75) == (1440, 540)
    assert V.window_to_video(0, 0, 1440, 540, 1920, 720) == (0, 0)
    assert V.window_to_video(720, 270, 1440, 540, 1920, 720) == (960, 360)
    assert V.window_to_video(300, 150, 1440, 540, 1920, 720) == (400, 200)
    # scale 1.0 - как есть
    assert V.window_to_video(123, 45, 1920, 720, 1920, 720) == (123, 45)
    # мышь за границей окна при перетаскивании: обрезка по кадру
    assert V.window_to_video(-50, 9999, 1440, 540, 1920, 720) == (0, 719)
    assert V.window_to_video(1440, 540, 1440, 540, 1920, 720) == (1919, 719)


def test_auto_scale_fits_screen():
    assert V.auto_scale(1920, 720, 3840, 2160) == 1.0                  # большой экран: не увеличиваем
    s = V.auto_scale(1920, 720, 1366, 768)
    w, h = V.display_size(1920, 720, s)
    assert w <= 1366 and h <= 768 and s < 1.0
    s = V.auto_scale(1920, 1080, 1920, 1080)
    assert V.display_size(1920, 1080, s)[1] <= 1080 * 0.85 + 1


def test_move_throttle():
    t = V.MoveThrottle(0.1)
    t.reset(10.0)
    assert not t.allow(10.05)
    assert t.allow(10.11)
    assert not t.allow(10.15)
    assert t.allow(10.25)


def test_touch_and_key_packing():
    assert C.build_touch(C.TOUCH_DOWN, 960, 360) == C.cmd_inner(0x00068001, C.pb_int(1, 0) + C.pb_int(2, 960) + C.pb_int(3, 360))
    assert C.build_touch(C.TOUCH_MOVE, 5, 7)[8:] == bytes.fromhex("0802 1005 1807")
    assert C.build_touch(C.TOUCH_UP, 0, 0)[8:] == bytes.fromhex("0801 1000 1800")
    assert C.build_hard_key(C.HARD_KEY_BACK) == bytes.fromhex("0002 0000 00068008 080e".replace(" ", ""))
    assert C.build_hard_key(C.HARD_KEY_HOME)[8:] == bytes.fromhex("0801")
    assert C.msg_name(C.TOUCH_CAR_HARD_KEY_CODE) == "TOUCH_CAR_HARD_KEY_CODE"


def test_loopback_size_negotiation_and_input():
    hu, t, res, out = start_hu(6.0, size=(1280, 480), no_auth=True)
    sizes, chunks = [], []
    hu.size_sink = lambda w, h: sizes.append((w, h))
    hu.video_sink = chunks.append
    b = FakeBridge(hu.port)
    b.pump(lambda m: len(m) >= 1)
    b.send_cmd(C.MD_INFO, MD_INFO_BODY)
    b.pump(lambda m: C.VIDEO_ENCODER_START in [x[1] for x in m])
    init = [x[2] for x in b.msgs if x[1] == C.VIDEO_ENCODER_INIT][0]
    f = C.pb_parse(init)
    assert (C.pb_get_int(f, 1), C.pb_get_int(f, 2)) == (1280, 480)       # --size ушёл в INIT
    # мост отвечает своим размером
    b.send_cmd(C.VIDEO_ENCODER_INIT_DONE, C.pb_int(1, 1024) + C.pb_int(2, 384) + C.pb_int(3, 30))
    b.s.sendall(C.outer(C.CH_VIDEO, C.data_inner(C.VIDEO_DATA, b"\x00\x00\x00\x01\x67" + b"x" * 10)))
    deadline = time.monotonic() + 5
    while (not sizes or not chunks) and time.monotonic() < deadline:
        time.sleep(0.02)
    assert sizes == [(1024, 384)] and hu.vid_size == (1024, 384)
    assert chunks == [b"\x00\x00\x00\x01\x67" + b"x" * 10]
    # касания и кнопка: DOWN/MOVE/UP + BACK доходят по каналу TOUCH
    assert hu.session_up.wait(2)
    hu.send_touch_event(C.TOUCH_DOWN, 100, 200)
    hu.send_touch_event(C.TOUCH_MOVE, 110, 210)
    hu.send_touch_event(C.TOUCH_UP, 110, 210)
    hu.send_hard_key(C.HARD_KEY_BACK)
    b.pump(lambda m: len(b.types(C.CH_TOUCH)) >= 4)
    assert b.types(C.CH_TOUCH) == [C.TOUCH_ACTION] * 3 + [C.TOUCH_CAR_HARD_KEY_CODE]
    bodies = [x[2] for x in b.msgs if x[0] == C.CH_TOUCH]
    assert bodies[0] == C.pb_int(1, 0) + C.pb_int(2, 100) + C.pb_int(3, 200)
    assert bodies[1][:2] == bytes.fromhex("0802") and bodies[2][:2] == bytes.fromhex("0801")
    assert bodies[3] == C.pb_int(1, 0x0E)
    hu.finish("test")
    t.join(10)
    b.s.close()
    assert not t.is_alive()
    log = (out / "carlife_hu.log").read_text(encoding="utf-8")
    assert "video size from INIT_DONE: 1024x384 (the HU asked 1280x480)" in log
    assert "TOUCH_ACTION DOWN 100,200" in log and "TOUCH_CAR_HARD_KEY_CODE 0x0E" in log


def test_send_touch_before_session_is_ignored():
    hu = C.HeadUnit(C.Options(port=0, quiet=True, out=Path(__import__("tempfile").mkdtemp())))
    hu.send_touch_event(C.TOUCH_DOWN, 1, 1)         # соединения нет: тихо ничего
    hu.send_hard_key(C.HARD_KEY_BACK)


@pytest.mark.skipif(V.find_ffmpeg() is None, reason="ffmpeg not in PATH")
def test_ffmpeg_pipeline_gives_frames_of_display_size(tmp_path):
    src = tmp_path / "t.h264"
    subprocess.run([V.find_ffmpeg(), "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x240:rate=30", "-t", "2",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
                    "-bsf:v", "h264_mp4toannexb", "-f", "h264", str(src)], check=True, timeout=60)
    pipe = V.ViewPipeline(V.find_ffmpeg(), 0.5, (1920, 1080))
    try:
        pipe.set_video_size(640, 240)
        assert pipe.disp == (320, 120)
        data = src.read_bytes()
        for i in range(0, len(data), 4096):
            pipe.on_video(data[i:i + 4096])
        deadline = time.monotonic() + 10
        got = None
        while got is None and time.monotonic() < deadline:
            got = pipe.latest.get(0)
            time.sleep(0.02)
        assert got is not None
        seq, rgb, size = got
        assert size == (320, 120) and len(rgb) == 320 * 120 * 3
        assert V.ppm(rgb, 320, 120).startswith(b"P6\n320 120\n255\n")
        assert len(set(rgb[:3000])) > 3                 # не чёрный кадр: в testsrc есть цветные полосы
        # смена размера моста перезапускает декодер
        pipe.set_video_size(320, 120)
        assert pipe.disp == (160, 60)
    finally:
        pipe.close()
