"""OCR кадра ГУ встроенным движком Windows (Windows.Media.Ocr): русский и английский, ~10 мс на кадр 1280x720.

rapidocr (модели ch/en) кириллицу не читает («Настройки» -> «HacTpoMK»), поэтому Windows OCR.
Нужны пакеты pywinrt: pip install winrt-runtime winrt-Windows.Media.Ocr winrt-Windows.Graphics.Imaging
winrt-Windows.Storage.Streams winrt-Windows.Foundation winrt-Windows.Foundation.Collections winrt-Windows.Globalization
и язык распознавания ru в Windows (Параметры > Язык; проверка: OcrEngine.AvailableRecognizerLanguages).
"""
from __future__ import annotations

import asyncio
import re
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


def norm(text: str) -> str:
    """Для сравнения: регистр, ё/е, двойники кириллица/латиница, лишние пробелы. OCR путает регистр первой буквы
    («домой») и буквы-двойники."""
    t = text.casefold().replace("ё", "е").translate(_HOMOGLYPHS)
    return re.sub(r"\s+", " ", t).strip()


def union(rects: list[Rect]) -> Rect:
    x0 = min(r[0] for r in rects)
    y0 = min(r[1] for r in rects)
    x1 = max(r[0] + r[2] for r in rects)
    y1 = max(r[1] + r[3] for r in rects)
    return x0, y0, x1 - x0, y1 - y0


def find_in(words: list[Word], text: str, partial: bool = False) -> list[Rect]:
    """Где на кадре фраза text: подряд идущие слова одной строки, склеенные пробелом, равны фразе
    (partial=True: фраза - подстрока склейки). Чистая функция, без OCR."""
    want = norm(text)
    if not want:
        return []
    lines: dict[int, list[Word]] = {}
    for w in words:
        lines.setdefault(w.line, []).append(w)
    hits: list[Rect] = []
    for ws in lines.values():
        n = len(ws)
        for i in range(n):
            for j in range(i, n):
                got = norm(" ".join(w.text for w in ws[i:j + 1]))
                if got == want or (partial and want in got):
                    hits.append(union([w.rect for w in ws[i:j + 1]]))
                    break                   # самая короткая склейка от слова i
                if len(got) > len(want) + 2 and not partial:
                    break
    # partial: от разных i могли найтись вложенные рамки - оставляем самые узкие
    hits.sort(key=lambda r: r[2])
    out: list[Rect] = []
    for r in hits:
        if not any(_inside(o, r) for o in out):
            out.append(r)
    out.sort(key=lambda r: (r[1], r[0]))
    return out


def _inside(a: Rect, b: Rect) -> bool:
    """a целиком внутри b."""
    return a[0] >= b[0] and a[1] >= b[1] and a[0] + a[2] <= b[0] + b[2] and a[1] + a[3] <= b[1] + b[3]


class WinOcr:
    def __init__(self, lang: str = "ru"):
        from winrt.windows.globalization import Language
        from winrt.windows.media.ocr import OcrEngine
        self.engine = OcrEngine.try_create_from_language(Language(lang))
        if self.engine is None:
            raise RuntimeError(f"Windows OCR: нет языка {lang!r} (Параметры > Время и язык > Язык, добавить русский)")
        self.max_dim = int(OcrEngine.max_image_dimension)

    def read(self, rgb: np.ndarray, region: Rect | None = None) -> list[Word]:
        """Слова на кадре (H, W, 3) RGB; region - искать только в этой рамке, координаты всё равно кадровые."""
        ox = oy = 0
        if region:
            x, y, w, h = region
            rgb = rgb[y:y + h, x:x + w]
            ox, oy = x, y
        h, w = rgb.shape[:2]
        scale = 1.0
        if max(w, h) > self.max_dim:            # движок не берёт кадры больше max_image_dimension
            import cv2
            scale = self.max_dim / max(w, h)
            rgb = cv2.resize(rgb, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
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
