"""OCR кадра ГУ встроенным движком Windows (Windows.Media.Ocr): русский и английский, ~10 мс на кадр 1280x720.

rapidocr (модели ch/en) кириллицу не читает («Настройки» -> «HacTpoMK»), поэтому Windows OCR.
Нужны пакеты pywinrt: pip install winrt-runtime winrt-Windows.Media.Ocr winrt-Windows.Graphics.Imaging
winrt-Windows.Storage.Streams winrt-Windows.Foundation winrt-Windows.Foundation.Collections winrt-Windows.Globalization
и язык распознавания ru в Windows (Параметры > Язык; проверка: OcrEngine.AvailableRecognizerLanguages).
"""
from __future__ import annotations

import asyncio
import re
import unicodedata
from dataclasses import dataclass

import numpy as np

Rect = tuple[int, int, int, int]            # x, y, w, h в пикселях видео


@dataclass
class Word:
    text: str
    rect: Rect
    line: int                               # номер строки OCR: слова одной строки идут подряд

    @property
    def center(self) -> tuple[int, int]:
        x, y, w, h = self.rect
        return x + w // 2, y + h // 2


# OCR путает одинаковые на вид кириллицу и латиницу («1280х720» с кириллической х): сравниваем по латинскому двойнику
_HOMOGLYPHS = str.maketrans("асеорхукмтвн", "aceopxykmtbh")


_CJK = "⺀-鿿豈-﫿＀-￯　-〿"
_CJK_SPACE = re.compile(rf"(?<=[{_CJK}])\s+|\s+(?=[{_CJK}])")
_RTL = re.compile(r"[֐-ࣿיִ-﷿ﹰ-﻿]")
_PUNCT_END = ".…。:：!！"


def norm(text: str) -> str:
    """Для сравнения: регистр, диакритика (ё/е, é/e, харакат), двойники кириллица/латиница, лишние пробелы, пробелы
    вокруг иероглифов, точка в конце. OCR путает регистр первой буквы («домой»), буквы-двойники, теряет акценты
    (английский движок на французском) и режет китайскую фразу на слова-иероглифы."""
    t = unicodedata.normalize("NFKD", text.casefold())
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn")
    t = t.translate(_HOMOGLYPHS)
    t = re.sub(r"\s+", " ", t).strip()
    t = _CJK_SPACE.sub("", t)
    return t.rstrip(_PUNCT_END).strip()


def is_rtl(text: str) -> bool:
    return bool(_RTL.search(text))


def union(rects: list[Rect]) -> Rect:
    x0 = min(r[0] for r in rects)
    y0 = min(r[1] for r in rects)
    x1 = max(r[0] + r[2] for r in rects)
    y1 = max(r[1] + r[3] for r in rects)
    return x0, y0, x1 - x0, y1 - y0


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _match(got: str, want: str, partial: bool, fuzz: int) -> bool:
    if got == want or (partial and want in got):
        return True
    if fuzz and len(want) >= 5 and not partial:
        return abs(len(got) - len(want)) <= fuzz and edit_distance(got, want) <= fuzz
    return False


def truncated(words: list[Word], text: str) -> str | None:
    """Строка OCR, которая похожа на обрезанную многоточием фразу text («Nothing is playi...»), или None."""
    want = norm(text)
    for line in lines_text(words):
        t = line.strip()
        m = re.match(r"^(.*?)\s*(\.{2,}|…)$", t)
        if m and len(norm(m.group(1))) >= 4 and want.startswith(norm(m.group(1))) and norm(m.group(1)) != want:
            return t
    return None


def find_in(words: list[Word], text: str, partial: bool = False, fuzz: int = 0) -> list[Rect]:
    """Где на кадре фраза text: подряд идущие слова одной строки, склеенные пробелом, равны фразе
    (partial=True: фраза - подстрока склейки; fuzz - сколько букв OCR может ошибиться, для движка не своего языка).
    Чистая функция, без OCR."""
    want = norm(text)
    if not want:
        return []
    lines: dict[int, list[Word]] = {}
    for w in words:
        lines.setdefault(w.line, []).append(w)
    hits: list[Rect] = []
    seqs = list(lines.values())
    seqs += _wrapped(lines)                 # подпись, перенесённая на вторую строку («Анимации / кнопок»)
    if is_rtl(text):                        # арабский: порядок слов в строке OCR не гарантирован - пробуем оба
        seqs += [ws[::-1] for ws in lines.values() if len(ws) > 1]
    for ws in seqs:
        n = len(ws)
        for i in range(n):
            for j in range(i, n):
                got = norm(" ".join(w.text for w in ws[i:j + 1]))
                if _match(got, want, partial, fuzz):
                    hits.append(union([w.rect for w in ws[i:j + 1]]))
                    break                   # самая короткая склейка от слова i
                if len(got) > len(want) + 2 + fuzz and not partial:
                    break
    hits = list(dict.fromkeys(hits))
    # partial: от разных i могли найтись вложенные рамки - оставляем самые узкие
    hits.sort(key=lambda r: r[2])
    out: list[Rect] = []
    for r in hits:
        if not any(_inside(o, r) for o in out):
            out.append(r)
    out.sort(key=lambda r: (r[1], r[0]))
    return out


def _wrapped(lines: dict[int, list[Word]]) -> list[list[Word]]:
    """Пары строк OCR, где вторая стоит прямо под первой (зазор меньше высоты строки, колонки пересекаются):
    склейка «верхняя + нижняя» как одна строка."""
    boxes = {k: union([w.rect for w in ws]) for k, ws in lines.items() if ws}
    out: list[list[Word]] = []
    for a, ra in boxes.items():
        for b, rb in boxes.items():
            if a == b:
                continue
            gap = rb[1] - (ra[1] + ra[3])
            hmax = max(ra[3], rb[3])
            overlap = min(ra[0] + ra[2], rb[0] + rb[2]) - max(ra[0], rb[0])
            if -hmax * 0.3 <= gap <= hmax * 0.8 and overlap > 0:
                out.append(lines[a] + lines[b])
    return out


def _inside(a: Rect, b: Rect) -> bool:
    """a целиком внутри b."""
    return a[0] >= b[0] and a[1] >= b[1] and a[0] + a[2] <= b[0] + b[2] and a[1] + a[3] <= b[1] + b[3]


# язык приложения -> языки OCR Windows по порядку предпочтения (французский читает и английский движок, без акцентов)
OCR_LANGS = {"ru": ("ru",), "en": ("en-US", "en"), "fr": ("fr-FR", "fr", "en-US"), "ar": ("ar-SA", "ar"),
             "zh-CN": ("zh-Hans-CN", "zh-Hans", "zh-CN")}
# пакеты OCR: Add-WindowsCapability -Online -Name <имя> (PowerShell от администратора)
OCR_CAPS = {"fr": "Language.OCR~~~fr-FR~0.0.1.0", "ar": "Language.OCR~~~ar-SA~0.0.1.0",
            "zh-CN": "Language.OCR~~~zh-CN~0.0.1.0", "en": "Language.OCR~~~en-US~0.0.1.0",
            "ru": "Language.OCR~~~ru-RU~0.0.1.0"}


def available_ocr() -> list[str]:
    from winrt.windows.media.ocr import OcrEngine
    return [lang.language_tag for lang in OcrEngine.available_recognizer_languages]


class WinOcr:
    def __init__(self, lang: str = "ru", upscale: float = 1.5):
        self.upscale = upscale
        from winrt.windows.globalization import Language
        from winrt.windows.media.ocr import OcrEngine
        self.engine = None
        for tag in OCR_LANGS.get(lang, (lang,)):
            self.engine = OcrEngine.try_create_from_language(Language(tag))
            if self.engine is not None:
                break
        if self.engine is None:
            cap = OCR_CAPS.get(lang, f"Language.OCR~~~{lang}~0.0.1.0")
            raise RuntimeError(f"Windows OCR: нет языка {lang!r} (есть {available_ocr()}); поставить в PowerShell "
                               f"от администратора: Add-WindowsCapability -Online -Name \"{cap}\"")
        self.lang = self.engine.recognizer_language.language_tag
        # движок не своего языка (французский английским) путает буквы с акцентами: «Système» -> «Systöme»
        self.native = self.lang.split("-")[0].lower() == lang.split("-")[0].lower()
        self.max_dim = int(OcrEngine.max_image_dimension)

    def read(self, rgb: np.ndarray, region: Rect | None = None) -> list[Word]:
        """Слова на кадре (H, W, 3) RGB; region - искать только в этой рамке, координаты всё равно кадровые."""
        ox = oy = 0
        if region:
            x, y, w, h = region
            rgb = rgb[y:y + h, x:x + w]
            ox, oy = x, y
        h, w = rgb.shape[:2]
        # Увеличение: на 1280x720 арабский и китайский движки теряют отдельные короткие слова («النظام», «主题»),
        # при x1,5 находят все; кадр не больше max_image_dimension.
        scale = min(self.upscale, self.max_dim / max(w, h))
        if abs(scale - 1.0) > 1e-3:
            import cv2
            rgb = cv2.resize(rgb, (int(w * scale), int(h * scale)),
                             interpolation=cv2.INTER_CUBIC if scale > 1 else cv2.INTER_AREA)
            h, w = rgb.shape[:2]
        bgra = np.empty((h, w, 4), np.uint8)
        bgra[:, :, 0] = rgb[:, :, 2]
        bgra[:, :, 1] = rgb[:, :, 1]
        bgra[:, :, 2] = rgb[:, :, 0]
        bgra[:, :, 3] = 255
        result = asyncio.run(self._recognize(bgra.tobytes(), w, h))
        words: list[Word] = []
        for li, line in enumerate(result.lines):
            for wd in line.words:
                r = wd.bounding_rect
                words.append(Word(wd.text, (ox + int(r.x / scale), oy + int(r.y / scale),
                                            int(r.width / scale) + 1, int(r.height / scale) + 1), li))
        return words

    async def _recognize(self, data: bytes, w: int, h: int):
        from winrt.windows.graphics.imaging import BitmapAlphaMode, BitmapPixelFormat, SoftwareBitmap
        from winrt.windows.storage.streams import DataWriter
        dw = DataWriter()
        dw.write_bytes(data)
        bmp = SoftwareBitmap.create_copy_from_buffer(dw.detach_buffer(), BitmapPixelFormat.BGRA8, w, h,
                                                     BitmapAlphaMode.PREMULTIPLIED)
        return await self.engine.recognize_async(bmp)


def lines_text(words: list[Word]) -> list[str]:
    """Текст кадра по строкам OCR (для отчёта и сообщений об ошибках)."""
    lines: dict[int, list[str]] = {}
    for w in words:
        lines.setdefault(w.line, []).append(w.text)
    return [" ".join(v) for _, v in sorted(lines.items())]
