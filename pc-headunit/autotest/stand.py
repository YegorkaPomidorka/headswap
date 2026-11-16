"""Стенд для автотестов: эмулятор ГУ CarLife в этом процессе + мост Headswap на телефоне по USB.

Стенд тот же, что у stand.ps1, только без окна: кадры декодирует ffmpeg в numpy (размер видео 1:1, координаты касаний =
пиксели кадра), а касания и кнопки шлёт тест. Подготовку телефона (сборка smoke, установка, bridgeMode, активация,
adb reverse) делает stand.ps1 -PrepareOnly, чтобы она жила в одном месте.

    with Stand(out) as s:
        s.tap_text("Настройки")
        s.check("settings")             # сравнение с эталоном refs/<hu>-<W>x<H>/settings.png

Порядок запуска как у run_view в carlife_hu.py: listen() -> run() в нити -> BridgeService по adb.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
PC_HU = HERE.parent
if str(PC_HU) not in sys.path:
    sys.path.insert(0, str(PC_HU))

import carlife_hu as H          # noqa: E402
import carlife_view as V        # noqa: E402

from . import compare as C      # noqa: E402
from .ocr import Rect, WinOcr, Word, find_in, lines_text, truncated   # noqa: E402
from .strings import Strings    # noqa: E402

PKG = "com.yegorkapomidorka.headswap.smoke"
REFS = HERE / "refs"
DECODER_ARGS = ("-threads", "1")    # без многопоточного декодера ffmpeg не держит у себя последние кадры


class StandError(AssertionError):
    """Сценарий не прошёл: не нашёлся текст, кадр не совпал с эталоном, сессия упала."""


class Stand:
    def __init__(self, out: Path, size: tuple[int, int] = (1280, 720), hu: str = "honda", mode: str = "desktop",
                 prepare: bool = True, update_refs: bool = False, refs: Path = REFS, echo: bool = False,
                 animations: bool = False, quiet: float = 0.3, change_wait: float = 0.6, lang: str = "ru"):
        """animations=False: на время прогона масштаб анимаций Android 0 (как в Espresso), после stop() прежний.
        quiet - сколько секунд экран не меняется, чтобы считаться готовым; change_wait - сколько после тапа ждать
        первый изменившийся кадр (отклик ~0,1 с; тап, который экран не меняет, стоит столько).
        lang - язык приложения (en ru ar fr zh-CN): ставится через per-app locale, строки сценария - self.R."""
        self.lang = lang
        self.R = Strings(lang)
        self.out = Path(out)
        self.animations, self.quiet, self.change_wait = animations, quiet, change_wait
        self._calm: tuple[int, np.ndarray] | None = None
        self._anim_saved: dict[str, str] = {}
        self.size, self.hu_profile, self.mode = size, hu, mode
        self.prepare, self.update_refs, self.refs_root = prepare, update_refs, Path(refs)
        self.echo = echo
        self.hu: H.HeadUnit | None = None
        self.pipe: V.ViewPipeline | None = None
        self.worker: threading.Thread | None = None
        self.ocrengine: WinOcr | None = None
        self.step = 0
        self.created: list[str] = []        # эталоны, созданные в этом прогоне (тест с ними не считается пройденным)
        self.alog = None

    # ---- запуск ----

    def __enter__(self) -> "Stand":
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    def start(self, session_timeout: float = 60.0, frame_timeout: float = 30.0):
        try:
            self._start(session_timeout, frame_timeout)
        except BaseException:
            self.stop()                     # вернуть анимации телефона, закрыть ffmpeg
            raise

    def _start(self, session_timeout: float, frame_timeout: float):
        self.out.mkdir(parents=True, exist_ok=True)
        self.alog = open(self.out / "actions.log", "a", encoding="utf-8")
        ffmpeg = V.find_ffmpeg()
        if not ffmpeg:
            raise StandError("нет ffmpeg в PATH (winget install Gyan.FFmpeg)")
        self.ocr                            # нет языка OCR - падаем до подготовки телефона
        # Язык надо поставить до старта процесса: от него зависят не только строки, но и набор навигаторов и
        # раскладка, которые LauncherModel считает один раз. Поэтому смена языка = подготовка заново (force-stop +
        # активация), даже с --no-prepare.
        installed = "package:" in self._adb_out("shell", "pm", "path", PKG)
        if not installed:
            self._prepare()
        prepare = self.prepare or not installed
        if self._get_locale() != self._locale_tag():
            self._set_locale(self._locale_tag())
            prepare = True
        if prepare:
            self._prepare()
        else:
            self._adb("reverse", "tcp:7240", "tcp:7240")
        if not self.animations:
            self._animations_off()
        self.hu = H.HeadUnit(H.Options(duration=float("inf"), out=self.out, size=self.size, hu=self.hu_profile,
                                       quiet=not self.echo))
        self.pipe = V.ViewPipeline(ffmpeg, 1.0, (100000, 100000), self.hu.log, DECODER_ARGS)
        self.hu.video_sink, self.hu.size_sink = self.pipe.on_video, self.pipe.set_video_size
        self.hu.listen()
        self.worker = threading.Thread(target=self._work, name="hu-session", daemon=True)
        self.worker.start()
        # играющая музыка меняет главный экран и порядок плееров в настройках; autoplay выключает stand.ps1
        subprocess.run(["adb", "shell", "cmd", "media_session", "dispatch", "pause"], capture_output=True, timeout=15)
        time.sleep(0.5)
        self._adb("shell", "am", "start-foreground-service", "-n", f"{PKG}/com.yegorkapomidorka.headswap.BridgeService",
                  "--ei", "debug_tcp", "7240")
        self.act("bridge started")
        if not self.hu.session_up.wait(session_timeout):
            raise StandError(f"мост не подключился за {session_timeout:g} с (лог: {self.out / 'carlife_hu.log'})")
        end = time.monotonic() + frame_timeout
        while self.pipe.latest.get(0) is None:
            self._alive()
            if time.monotonic() > end:
                raise StandError(f"нет кадров {frame_timeout:g} с после подключения моста")
            time.sleep(0.1)
        w, h = self.video_size
        self.act(f"first frame {w}x{h}")
        self.wait_ready()

    # Пока мост показывает заглушку («Запускаю рабочий стол…»), касания он не принимает: в логе телефона их нет вовсе.
    # Тексты заглушки (DelegatedDexConnector, AaBridgeCore): ищем начало фразы, «готов» идёт с размером экрана.
    PLACEHOLDER_KEYS = ("desk_ph_starting", "desk_ph_wait_usb", "desk_connecting", "desk_waiting_frame")

    def wait_ready(self, timeout: float = 30.0):
        """Дождаться настоящего экрана вместо заглушки моста и дать ему успокоиться."""
        end = time.monotonic() + timeout
        w, h = self.size
        marks = [self.R(k).rstrip("…. ") for k in self.PLACEHOLDER_KEYS] + [self.R("desk_ph_ready", w, h).split(",")[0]]
        while True:
            f = self.settle(quiet=1.0)
            words = self.ocr.read(f)
            if not any(find_in(words, p, partial=True, fuzz=self.fuzz) for p in marks):
                self.act("screen ready: " + " | ".join(lines_text(words))[:200])
                return
            if time.monotonic() > end:
                self.shot("not-ready", f)
                raise StandError(f"за {timeout:g} с мост не сменил заглушку на экран: {' | '.join(lines_text(words))}")
            time.sleep(0.3)

    def _work(self):
        try:
            self.hu.run()
        except BaseException as e:      # noqa: BLE001 - в тест уйдёт через _alive()
            self.hu.log(f"autotest: session error {e!r}")
            self.hu.finish("error")

    def _prepare(self):
        ps = shutil.which("pwsh") or shutil.which("powershell")
        cmd = [ps, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PC_HU / "stand.ps1"), "-PrepareOnly",
               "-Mode", self.mode, "-Out", str(self.out)]
        self.act("stand.ps1 -PrepareOnly")
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        (self.out / "prepare.log").write_text(r.stdout + r.stderr, encoding="utf-8")
        if r.returncode != 0:
            raise StandError(f"stand.ps1 -PrepareOnly упал ({r.returncode}), см. {self.out / 'prepare.log'}")

    def stop(self):
        if self.hu:
            self.hu.finish("autotest done")
        if self.worker:
            self.worker.join(10)
        if self.pipe:
            self.pipe.close()
        self._animations_restore()
        self._pull_phone_log()
        if self.alog:
            self.alog.close()
            self.alog = None

    def _locale_tag(self) -> str:
        return "ar-u-nu-latn" if self.lang == "ar" else self.lang     # как AppLocale.applyTag

    def _get_locale(self) -> str:
        """Per-app язык smoke: «Locales for <pkg> for user 0 are [ru]» -> «ru», пусто = системный."""
        out = self._adb_out("shell", "cmd", "locale", "get-app-locales", PKG)
        m = re.search(r"\[(.*)\]", out)
        return m.group(1).strip() if m else ""

    def _set_locale(self, tag: str):
        self._adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", tag)
        self.act(f"app locale {tag or 'system'}")

    def _adb_out(self, *args: str) -> str:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=30)
        return r.stdout

    ANIM_KEYS = ("animator_duration_scale", "transition_animation_scale", "window_animation_scale")

    def _animations_off(self):
        """Compose и View берут длительность анимаций из animator_duration_scale: с 0 экран готов с первого кадра."""
        for k in self.ANIM_KEYS:
            old = self._adb("shell", "settings", "get", "global", k).strip()
            try:
                self._adb("shell", "settings", "put", "global", k, "0")
            except StandError as e:
                # Xiaomi: нужен пункт «Отладка по USB (настройки безопасности)», иначе WRITE_SECURE_SETTINGS нет
                why = next((ln for ln in str(e).splitlines() if "denial" in ln), str(e).splitlines()[0])
                self.act(f"animations stay on: {why.strip()[:160]}")
                return
            self._anim_saved[k] = old
        self.act("animations off")

    def _animations_restore(self):
        for k, v in self._anim_saved.items():
            try:
                if v in ("", "null"):
                    self._adb("shell", "settings", "delete", "global", k)
                else:
                    self._adb("shell", "settings", "put", "global", k, v)
            except (StandError, OSError, subprocess.SubprocessError):
                pass
        self._anim_saved.clear()

    def _pull_phone_log(self):
        try:
            r = subprocess.run(["adb", "shell", "ls", "-t", f"/sdcard/Android/data/{PKG}/files/"],
                               capture_output=True, text=True, timeout=10)
            name = (r.stdout.split() or [""])[0].strip()
            if name:
                subprocess.run(["adb", "pull", f"/sdcard/Android/data/{PKG}/files/{name}", str(self.out / name)],
                               capture_output=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            pass

    def _adb(self, *args: str):
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            raise StandError(f"adb {' '.join(args)}: {r.returncode} {r.stderr.strip()}")
        return r.stdout

    def _alive(self):
        if self.hu is None or self.hu.stop.is_set():
            raise StandError(f"сессия ГУ закончилась (лог: {self.out / 'carlife_hu.log'})")

    def act(self, msg: str):
        t = time.time()
        line = f"{time.strftime('%H:%M:%S', time.localtime(t))}.{int(t * 1000) % 1000:03d} {msg}"
        if self.alog:
            self.alog.write(line + "\n")
            self.alog.flush()
        if self.hu:
            self.hu.log(f"autotest: {msg}")

    # ---- кадры ----

    @property
    def video_size(self) -> tuple[int, int]:
        got = self.pipe.latest.get(0) if self.pipe else None
        return got[2] if got else self.size

    def _latest(self, after: int = 0):
        got = self.pipe.latest.get(after)
        if got is None:
            return None
        seq, data, (w, h) = got
        return seq, np.frombuffer(data, np.uint8).reshape(h, w, 3)

    def frame(self) -> np.ndarray:
        """Последний декодированный кадр (H, W, 3) RGB, без ожидания."""
        self._alive()
        got = self._latest()
        if got is None:
            raise StandError("кадров ещё нет")
        return got[1]

    def settle(self, quiet: float | None = None, timeout: float = 10.0, change: float = 0.0,
               before: np.ndarray | None = None) -> np.ndarray:
        """Дождаться, пока картинка не меняется quiet секунд (новых кадров нет или они такие же), и вернуть кадр.
        Энкодер телефона на статичном экране может вообще не слать кадры - это тоже «успокоился».

        change > 0 (после тапа): сначала до change секунд ждать кадр, отличный от before, - иначе тишина до прихода
        отклика экрана (~0,1 с) сошла бы за «успокоился». Повторный settle без новых кадров возвращается сразу."""
        quiet = self.quiet if quiet is None else quiet
        start = time.monotonic()
        end = start + timeout
        got = self._latest()
        if got is None:
            last, seq = None, 0
        else:
            seq, last = got
        if change > 0 and before is not None:
            while last is None or C.same_frame(last, before):
                self._alive()
                if time.monotonic() - start >= change:
                    break                   # тап экран не поменял: дальше обычная тишина
                time.sleep(0.01)
                got = self._latest(seq)
                if got:
                    seq, last = got
            calm_since = time.monotonic()
        elif self._calm is not None and last is not None and (
                seq == self._calm[0] or C.same_frame(last, self._calm[1])):
            self._calm = (seq, last)        # с прошлого settle экран не менялся
            return last
        else:
            calm_since = start
        while True:
            self._alive()
            got = self._latest(seq)
            now = time.monotonic()
            if got:
                seq, f = got
                if last is None or not C.same_frame(f, last):
                    last, calm_since = f, now
            if last is not None and now - calm_since >= quiet:
                self._calm = (seq, last)
                return last
            if now > end:
                if last is None:
                    raise StandError("нет кадров")
                self.act(f"settle: screen still moving after {timeout:g} s, taking the last frame")
                self._calm = None
                return last
            time.sleep(0.02)

    def _after_input(self, before: np.ndarray | None):
        self._calm = None
        self.settle(change=self.change_wait, before=before)

    def _before_input(self) -> np.ndarray | None:
        got = self._latest()
        return got[1] if got else None

    def shot(self, name: str, frame: np.ndarray | None = None) -> np.ndarray:
        """Сохранить кадр шагом отчёта: out/steps/NN-name.png."""
        f = self.settle() if frame is None else frame
        self.step += 1
        C.save(self.out / "steps" / f"{self.step:02d}-{name}.png", f)
        return f

    # ---- ввод ----

    def _touch(self, action: int, x: int, y: int):
        self._alive()
        if not self.hu.session_up.is_set():
            raise StandError("касание до подключения моста")
        w, h = self.video_size
        if not (0 <= x < w and 0 <= y < h):
            raise StandError(f"касание {x},{y} вне кадра {w}x{h}")
        self.hu.send_touch_event(action, int(x), int(y))

    def tap(self, x: int, y: int, hold: float = 0.06, settle: bool = True):
        self.act(f"tap {x},{y}")
        before = self._before_input()
        self._touch(V.TOUCH_DOWN, x, y)
        time.sleep(hold)
        self._touch(V.TOUCH_UP, x, y)
        if settle:
            self._after_input(before)

    def long_press(self, x: int, y: int, hold: float = 1.0):
        self.act(f"long press {x},{y} {hold:g} s")
        self.tap(x, y, hold=hold)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration: float = 0.35, settle: bool = True):
        self.act(f"swipe {x1},{y1} -> {x2},{y2} {duration:g} s")
        steps = max(2, int(duration / V.MOVE_INTERVAL))
        before = self._before_input()
        self._touch(V.TOUCH_DOWN, x1, y1)
        for i in range(1, steps + 1):
            time.sleep(duration / steps)
            self._touch(V.TOUCH_MOVE, round(x1 + (x2 - x1) * i / steps), round(y1 + (y2 - y1) * i / steps))
        self._touch(V.TOUCH_UP, x2, y2)
        if settle:
            self._after_input(before)

    def key(self, code: int, settle: bool = True):
        self._alive()
        self.act(f"hard key 0x{code:02X}")
        before = self._before_input()
        self.hu.send_hard_key(code)
        if settle:
            self._after_input(before)

    def back(self):
        self.key(H.HARD_KEY_BACK)

    def home(self):
        self.key(H.HARD_KEY_HOME)

    def voice(self):
        self._alive()
        self.act("voice key")
        self.hu.send_voice_key()

    # ---- текст ----

    @property
    def ocr(self) -> WinOcr:
        if self.ocrengine is None:
            self.ocrengine = WinOcr(self.lang)
        return self.ocrengine

    # ---- RTL ----

    @property
    def rtl(self) -> bool:
        """Арабский: лаунчер зеркалится (рейка и часы справа)."""
        return self.lang == "ar"

    def at(self, x: int, y: int) -> tuple[int, int]:
        """Точка сценария, записанная для LTR, на текущем экране: в RTL отражена по горизонтали."""
        return (self.video_size[0] - 1 - x, y) if self.rtl else (x, y)

    def area(self, r: Rect) -> Rect:
        """Рамка (x, y, w, h) сценария для LTR на текущем экране (маски, region)."""
        x, y, w, h = r
        return (self.video_size[0] - x - w, y, w, h) if self.rtl else r

    @property
    def fuzz(self) -> int:
        """Сколько букв OCR может ошибиться в фразе: 0 со своим движком, 1 с запасным (французский английским)."""
        return 0 if self.ocr.native else 1

    def words(self, frame: np.ndarray | None = None, region: Rect | None = None) -> list[Word]:
        return self.ocr.read(self.settle() if frame is None else frame, region)

    def text(self, frame: np.ndarray | None = None, region: Rect | None = None) -> list[str]:
        """Текст экрана по строкам."""
        return lines_text(self.words(frame, region))

    def find_text(self, text: str, timeout: float = 8.0, region: Rect | None = None, partial: bool = False,
                  index: int = 0) -> Rect:
        """Рамка фразы на экране; ждёт до timeout. index - какое по счёту вхождение (сверху вниз, слева направо)."""
        end = time.monotonic() + timeout
        while True:
            f = self.settle()
            words = self.ocr.read(f, region)
            hits = find_in(words, text, partial, self.fuzz)
            if len(hits) > index:
                return hits[index]
            if time.monotonic() > end:
                self.shot(f"no-text-{_slug(text)}", f)
                cut = truncated(words, text)
                if cut:
                    raise StandError(f"текст {text!r} обрезан многоточием: на экране {cut!r}")
                seen = " | ".join(lines_text(words)) or "(пусто)"
                raise StandError(f"нет текста {text!r} за {timeout:g} с. На экране: {seen}")
            time.sleep(0.1)

    def has_text(self, text: str, region: Rect | None = None, partial: bool = False) -> bool:
        return bool(find_in(self.words(None, region), text, partial, self.fuzz))

    def wait_text(self, text: str, timeout: float = 8.0, region: Rect | None = None, partial: bool = False):
        self.find_text(text, timeout, region, partial)

    def wait_no_text(self, text: str, timeout: float = 8.0, region: Rect | None = None, partial: bool = False):
        end = time.monotonic() + timeout
        while self.has_text(text, region, partial):
            if time.monotonic() > end:
                self.shot(f"still-text-{_slug(text)}")
                raise StandError(f"текст {text!r} не пропал за {timeout:g} с")
            time.sleep(0.1)

    def tap_text(self, text: str, timeout: float = 8.0, region: Rect | None = None, partial: bool = False,
                 index: int = 0, dx: int = 0, dy: int = 0):
        """Тапнуть в центр надписи (dx, dy - сдвиг, например на иконку над подписью)."""
        x, y, w, h = self.find_text(text, timeout, region, partial, index)
        self.act(f"tap text {text!r}")
        self.tap(x + w // 2 + dx, y + h // 2 + dy)

    # ---- эталоны ----

    @property
    def refs_dir(self) -> Path:
        """Эталоны экранов: refs/<hu>-<W>x<H>/<язык>/; иконки общие для языков: refs/<hu>-<W>x<H>/icons/."""
        return self.refs_size_dir / self.lang

    @property
    def refs_size_dir(self) -> Path:
        w, h = self.video_size
        return self.refs_root / f"{self.hu_profile}-{w}x{h}"

    def check(self, name: str, masks: tuple[Rect, ...] | list[Rect] = (), max_ratio: float = 0.01, tol: int = 24,
              timeout: float = 8.0):
        """Экран совпадает с эталоном refs/<hu>-<W>x<H>/<name>.png. Ждёт совпадения до timeout (анимации, догрузка).
        Эталона нет или --update-refs: кадр сохраняется эталоном, тест помечается «эталон создан», не «прошёл»."""
        ref_path = self.refs_dir / f"{name}.png"
        if self.update_refs or not ref_path.exists():
            f = self.settle()
            ref = f.copy()
            for x, y, w, h in masks:        # маски при сравнении не смотрятся; закрашиваем, чтобы личное не ушло в репо
                ref[max(0, y):y + h, max(0, x):x + w] = 0
            C.save(ref_path, ref)
            self.shot(name, f)
            self.created.append(str(ref_path))
            self.act(f"ref saved {ref_path}")
            return
        ref = C.load(ref_path)
        end = time.monotonic() + timeout
        while True:
            f = self.settle()
            d = C.diff(f, ref, masks, tol)
            if d.ratio <= max_ratio:
                self.shot(name, f)
                self.act(f"check {name}: ok, {d.ratio:.4f}")
                return
            if time.monotonic() > end:
                fail = self.out / "fail"
                C.save(fail / f"{name}-actual.png", f)
                C.save(fail / f"{name}-diff.png", C.overlay(f, d, masks))
                why = "размер кадра другой" if not d.same_size else f"отличается {d.ratio:.2%} пикселей (порог {max_ratio:.2%})"
                self.act(f"check {name}: FAIL {why}")
                raise StandError(f"экран не совпал с эталоном {name}: {why}; см. {fail}")
            time.sleep(0.1)

    def find_image(self, name: str, threshold: float = 0.9, timeout: float = 8.0,
                   region: Rect | None = None) -> Rect:
        """Иконка refs/<hu>-<W>x<H>/icons/<name>.png на экране (вырезать из эталона любым редактором)."""
        tpl = C.load(self.refs_size_dir / "icons" / f"{name}.png")
        end = time.monotonic() + timeout
        while True:
            f = self.settle()
            hit = C.find_image(f, tpl, threshold, region)
            if hit:
                return hit[0]
            if time.monotonic() > end:
                self.shot(f"no-icon-{name}", f)
                raise StandError(f"нет иконки {name!r} за {timeout:g} с")
            time.sleep(0.1)

    def tap_image(self, name: str, threshold: float = 0.9, timeout: float = 8.0, region: Rect | None = None):
        x, y, w, h = self.find_image(name, threshold, timeout, region)
        self.act(f"tap icon {name!r}")
        self.tap(x + w // 2, y + h // 2)


def _slug(text: str) -> str:
    return "".join(ch if ch.isalnum() else "_" for ch in text)[:40]
