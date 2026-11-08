"""Live window for carlife_hu.py (--view): H.264 from the bridge -> ffmpeg -> raw RGB frames -> tkinter, mouse -> touches.

Standard library only (+ the `ffmpeg` binary from PATH). No Pillow: frames go to tkinter as PPM bytes.

Threads: the HU network thread calls ViewPipeline.on_video() (non-blocking queue), a writer thread feeds ffmpeg's stdin,
a reader thread reads raw frames into a "latest frame" slot, tkinter polls the slot with after(). The network loop of
the emulator never waits for the window.
"""
from __future__ import annotations

import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from typing import Callable

MOVE_INTERVAL = 1 / 60              # touch MOVE no more often than this
TOUCH_DOWN, TOUCH_UP, TOUCH_MOVE = 0, 1, 2     # action of TOUCH_ACTION (MotionEvent codes, see AaBridge.onCarlifeTouch)
WRITE_QUEUE = 256                   # H.264 chunks waiting for ffmpeg's stdin


# ---- чистые функции (тесты) ----

def parse_size(text: str) -> tuple[int, int]:
    """'1920x720' -> (1920, 720). ValueError на мусоре и на размерах вне 16..8192."""
    m = re.fullmatch(r"\s*(\d+)\s*[xX]\s*(\d+)\s*", text or "")
    if not m:
        raise ValueError(f"size must look like 1920x720, got {text!r}")
    w, h = int(m.group(1)), int(m.group(2))
    if not (16 <= w <= 8192 and 16 <= h <= 8192):
        raise ValueError(f"size {w}x{h} is out of range 16..8192")
    return w, h


def auto_scale(vw: int, vh: int, screen_w: int, screen_h: int) -> float:
    """Масштаб окна, чтобы кадр влез на экран ПК с запасом под рамку и панель задач (не больше 1.0)."""
    return max(0.1, min(1.0, screen_w * 0.95 / vw, screen_h * 0.85 / vh))


def display_size(vw: int, vh: int, scale: float) -> tuple[int, int]:
    return max(1, round(vw * scale)), max(1, round(vh * scale))


def window_to_video(wx: float, wy: float, dw: int, dh: int, vw: int, vh: int) -> tuple[int, int]:
    """Точка в окне (dw x dh) -> пиксель видео (vw x vh), с обрезкой по границам кадра."""
    x = int(wx * vw / dw)
    y = int(wy * vh / dh)
    return min(vw - 1, max(0, x)), min(vh - 1, max(0, y))


class MoveThrottle:
    """Пропускает MOVE не чаще interval; DOWN/UP вызывающий шлёт всегда, reset() на DOWN."""

    def __init__(self, interval: float = MOVE_INTERVAL):
        self.interval = interval
        self.last = -1e9

    def reset(self, now: float):
        self.last = now

    def allow(self, now: float) -> bool:
        if now - self.last >= self.interval:
            self.last = now
            return True
        return False


def find_ffmpeg() -> str | None:
    return shutil.which("ffmpeg")


def ffmpeg_command(ffmpeg: str, out_w: int, out_h: int) -> list[str]:
    """H.264 Annex-B с stdin -> rgb24 кадры out_w x out_h в stdout, минимальная задержка."""
    return [ffmpeg, "-hide_banner", "-loglevel", "error",
            "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",     # -fflags nobuffer с -f h264 даёт 0 кадров
            "-f", "h264", "-i", "pipe:0",
            "-vf", f"scale={out_w}:{out_h}:flags=fast_bilinear",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]


def ppm(rgb: bytes, w: int, h: int) -> bytes:
    return f"P6\n{w} {h}\n255\n".encode("ascii") + rgb


def has_idr(data: bytes) -> bool:
    """В Annex-B буфере есть NAL типа 5 (IDR) или 7 (SPS)."""
    p = data.find(b"\x00\x00\x01")
    while p >= 0 and p + 3 < len(data):
        if data[p + 3] & 0x1F in (5, 7):
            return True
        p = data.find(b"\x00\x00\x01", p + 3)
    return False


# ---- ffmpeg ----

class LatestFrame:
    """Слот «последний кадр»: писатель перезаписывает, читатель берёт, если seq новее."""

    def __init__(self):
        self.lock = threading.Lock()
        self.seq = 0
        self.data: bytes | None = None
        self.size = (0, 0)

    def put(self, data: bytes, size: tuple[int, int]):
        with self.lock:
            self.seq += 1
            self.data, self.size = data, size

    def get(self, after_seq: int):
        """-> (seq, data, (w, h)) или None, если нового кадра нет."""
        with self.lock:
            if self.seq == after_seq or self.data is None:
                return None
            return self.seq, self.data, self.size


class FfmpegDecoder:
    """Один процесс ffmpeg на размер кадра. feed() не блокирует; готовые кадры приходят в on_frame(bytes) с нити чтения."""

    def __init__(self, ffmpeg: str, out_w: int, out_h: int, on_frame: Callable[[bytes], None],
                 log: Callable[[str], None] = lambda s: None):
        self.out_w, self.out_h = out_w, out_h
        self.on_frame = on_frame
        self.log = log
        self.frames = 0
        self.proc = subprocess.Popen(
            ffmpeg_command(ffmpeg, out_w, out_h), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            bufsize=0, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.q: queue.Queue = queue.Queue(WRITE_QUEUE)
        self.wait_idr = False               # очередь переполнилась и сброшена: ждём кадр с IDR/SPS, иначе картинка поплывёт
        self.closed = False
        for target in (self._writer, self._reader, self._stderr):
            threading.Thread(target=target, daemon=True, name=f"view-{target.__name__}").start()

    def feed(self, data: bytes):
        if self.closed:
            return
        if self.wait_idr:
            if not has_idr(data):
                return
            self.wait_idr = False
        try:
            self.q.put_nowait(data)
        except queue.Full:
            try:
                while True:
                    self.q.get_nowait()
            except queue.Empty:
                pass
            self.wait_idr = True
            self.log("view: ffmpeg is too slow, dropped queued video, waiting for the next IDR")

    def _writer(self):
        stdin = self.proc.stdin
        try:
            while True:
                data = self.q.get()
                if data is None:
                    break
                stdin.write(data)
        except (OSError, ValueError):
            pass
        finally:
            try:
                stdin.close()
            except OSError:
                pass

    def _reader(self):
        size = self.out_w * self.out_h * 3
        stdout = self.proc.stdout
        try:
            while True:
                buf = bytearray(size)
                view = memoryview(buf)
                got = 0
                while got < size:
                    n = stdout.readinto(view[got:])
                    if not n:
                        return
                    got += n
                self.frames += 1
                self.on_frame(bytes(buf))
        except (OSError, ValueError):
            pass

    def _stderr(self):
        try:
            for line in iter(self.proc.stderr.readline, b""):
                text = line.decode("utf-8", "replace").rstrip()
                if text:
                    self.log(f"ffmpeg: {text}")
        except (OSError, ValueError):
            pass

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.q.put_nowait(None)
        except queue.Full:
            pass                            # писатель выйдет сам: после kill запись в stdin падает
        try:
            self.proc.kill()
        except OSError:
            pass
        for s in (self.proc.stdout, self.proc.stderr):
            try:
                s.close()
            except OSError:
                pass


class ViewPipeline:
    """H.264 от моста -> ffmpeg -> последний кадр. Размер видео приходит из INIT_DONE (set_video_size), сменился - ffmpeg
    перезапускается. Размер на экране = размер видео * scale (scale None = по экрану ПК)."""

    def __init__(self, ffmpeg: str, scale: float | None, screen: tuple[int, int],
                 log: Callable[[str], None] = lambda s: None):
        self.ffmpeg, self.scale_opt, self.screen, self.log = ffmpeg, scale, screen, log
        self.lock = threading.Lock()
        self.video = (0, 0)
        self.disp = (0, 0)
        self.decoder: FfmpegDecoder | None = None
        self.latest = LatestFrame()
        self.frames_in = 0

    def set_video_size(self, w: int, h: int):
        with self.lock:
            if (w, h) == self.video and self.decoder is not None:
                return
            scale = self.scale_opt or auto_scale(w, h, *self.screen)
            self.video, self.disp = (w, h), display_size(w, h, scale)
            old, self.decoder = self.decoder, None
            if old:
                old.close()
            disp = self.disp
            self.log(f"view: video {w}x{h} -> window {disp[0]}x{disp[1]} (scale {disp[0] / w:.3f})")
            self.decoder = FfmpegDecoder(self.ffmpeg, disp[0], disp[1], lambda d, s=disp: self.latest.put(d, s), self.log)

    def on_video(self, body: bytes):
        """С сетевой нити эмулятора; без размера видео (INIT_DONE не было) кадр пропускается."""
        dec = self.decoder
        if dec is not None:
            self.frames_in += 1
            dec.feed(body)

    def close(self):
        with self.lock:
            if self.decoder:
                self.decoder.close()
                self.decoder = None


# ---- окно ----

class Viewer:
    """Окно tkinter. Создавать и вызывать mainloop() нужно в главной нити.

    touch(action, x, y) - касание в пикселях видео; key(code) - хард-кнопка ГУ; on_close() - окно закрыто;
    alive() - сессия ещё идёт (когда False, окно закрывается само)."""

    def __init__(self, ffmpeg: str, size: tuple[int, int], scale: float | None, touch: Callable[[int, int, int], None],
                 key: Callable[[int], None], on_close: Callable[[], None], alive: Callable[[], bool],
                 log: Callable[[str], None], back_code: int, home_code: int, title: str = "CarLife HU",
                 wheel: list[tuple[str, Callable[[], None]]] | None = None, knob: dict[str, int] | None = None):
        import tkinter as tk
        self.tk = tk
        self.root = tk.Tk()
        self.touch, self.key, self.on_close, self.alive, self.log = touch, key, on_close, alive, log
        self.back_code, self.home_code, self.title = back_code, home_code, title
        self.root.title(title)
        screen = (self.root.winfo_screenwidth(), self.root.winfo_screenheight())
        self.pipe = ViewPipeline(ffmpeg, scale, screen, log)
        self.vw, self.vh = size                      # ожидаемый размер, пока мост не ответил INIT_DONE
        dw, dh = display_size(self.vw, self.vh, scale or auto_scale(self.vw, self.vh, *screen))
        self.dw, self.dh = dw, dh
        self.canvas = tk.Canvas(self.root, width=dw, height=dh, bg="#101010", highlightthickness=0)
        self.canvas.pack()
        if wheel:                                    # кнопки руля под видео
            bar = tk.Frame(self.root, bg="#202020")
            bar.pack(fill="x")
            for label, fn in wheel:
                def press(label=label, fn=fn):
                    self.log(f"view: руль {label}")
                    fn()
                tk.Button(bar, text=label, command=press, padx=10, pady=4, bg="#303030", fg="#eeeeee",
                          activebackground="#505050", activeforeground="#ffffff", relief="flat",
                          takefocus=0).pack(side="left", padx=2, pady=3)
        self.text = self.canvas.create_text(dw // 2, dh // 2, fill="#888888", text="Waiting for the bridge...")
        self.item = None
        self.photo = None
        self.seq = 0
        self.shown = 0
        self.fps_t = time.monotonic()
        self.fps_shown = 0
        self.throttle = MoveThrottle()
        self.pressed = False
        self.closing = False
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.canvas.bind("<ButtonPress-1>", lambda e: self.mouse(TOUCH_DOWN, e))
        self.canvas.bind("<B1-Motion>", lambda e: self.mouse(TOUCH_MOVE, e))
        self.canvas.bind("<ButtonRelease-1>", lambda e: self.mouse(TOUCH_UP, e))
        self.canvas.bind("<ButtonPress-3>", lambda e: self.key(self.back_code))
        self.canvas.bind("<ButtonPress-2>", lambda e: self.key(self.home_code))
        for k in ("<Escape>", "<BackSpace>"):
            self.root.bind(k, lambda e: self.key(self.back_code))
        self.root.bind("<Home>", lambda e: self.key(self.home_code))
        if knob:                                     # шайба: колесо мыши = поворот, клавиши = наклоны/OK
            def turn(e):
                up = getattr(e, "delta", 0) > 0 or getattr(e, "num", 0) == 4
                self.key(knob["wheel_up" if up else "wheel_down"])
            self.canvas.bind("<MouseWheel>", turn)   # Windows/macOS
            self.canvas.bind("<Button-4>", turn)     # X11
            self.canvas.bind("<Button-5>", turn)
            for ev, code in knob.items():
                if ev.startswith("<"):
                    self.root.bind(ev, lambda e, code=code: self.key(code))
        self.canvas.focus_set()

    def mouse(self, action: int, e):
        now = time.monotonic()
        if action == TOUCH_DOWN:
            self.pressed = True
            self.throttle.reset(now)
        elif not self.pressed:
            return
        elif action == TOUCH_MOVE and not self.throttle.allow(now):
            return
        elif action == TOUCH_UP:
            self.pressed = False
        vw, vh = self.pipe.video if self.pipe.video[0] else (self.vw, self.vh)
        x, y = window_to_video(e.x, e.y, self.dw, self.dh, vw, vh)
        self.touch(action, x, y)

    def close(self):
        if not self.closing:
            self.closing = True
            self.log("view: window closed")
            self.on_close()

    def resize(self, dw: int, dh: int, vw: int, vh: int):
        self.dw, self.dh, self.vw, self.vh = dw, dh, vw, vh
        self.canvas.config(width=dw, height=dh)
        self.canvas.coords(self.text, dw // 2, dh // 2)

    def tick(self):
        if not self.alive():
            self.root.destroy()
            return
        disp, video = self.pipe.disp, self.pipe.video
        if disp[0] and (disp != (self.dw, self.dh) or video != (self.vw, self.vh)):
            self.resize(disp[0], disp[1], video[0], video[1])
        got = self.pipe.latest.get(self.seq)
        if got is not None and got[2] == (self.dw, self.dh):
            self.seq, rgb, (w, h) = got
            self.photo = self.tk.PhotoImage(data=ppm(rgb, w, h))
            if self.item is None:
                self.canvas.delete(self.text)
                self.item = self.canvas.create_image(0, 0, anchor="nw", image=self.photo)
            else:
                self.canvas.itemconfigure(self.item, image=self.photo)
            self.shown += 1
        now = time.monotonic()
        if now - self.fps_t >= 1.0:
            fps = (self.shown - self.fps_shown) / (now - self.fps_t)
            self.fps_t, self.fps_shown = now, self.shown
            if self.item is not None:
                self.root.title(f"{self.title}  {self.vw}x{self.vh}  {fps:.0f} fps")
        self.root.after(8, self.tick)

    def run(self):
        self.root.after(8, self.tick)
        try:
            self.root.mainloop()
        finally:
            self.pipe.close()


def main() -> int:
    """Самопроверка конвейера без телефона: python carlife_view.py FILE.h264 WxH [scale] - прогнать файл через ffmpeg."""
    if len(sys.argv) < 3:
        print(main.__doc__)
        return 2
    path, (w, h) = sys.argv[1], parse_size(sys.argv[2])
    scale = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        print("ffmpeg not found in PATH")
        return 1
    pipe = ViewPipeline(ffmpeg, scale, (99999, 99999), print)
    pipe.set_video_size(w, h)
    with open(path, "rb") as f:
        while chunk := f.read(4096):
            pipe.on_video(chunk)
    time.sleep(2)
    n = pipe.decoder.frames if pipe.decoder else 0
    print(f"frames decoded: {n}, display {pipe.disp[0]}x{pipe.disp[1]}, frame bytes {pipe.disp[0] * pipe.disp[1] * 3}")
    pipe.close()
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
