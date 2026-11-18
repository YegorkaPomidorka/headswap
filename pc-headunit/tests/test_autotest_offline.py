"""Автотесты стенда без телефона: поиск фраз в словах OCR, сравнение кадров, поиск иконки, Windows OCR на кириллице."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

np = pytest.importorskip("numpy")
cv2 = pytest.importorskip("cv2")

from autotest import compare as C          # noqa: E402
from autotest.ocr import Word, find_in, norm   # noqa: E402


def words(*lines):
    out = []
    for li, line in enumerate(lines):
        x = 0
        for t in line.split():
            out.append(Word(t, (x, li * 50, len(t) * 10, 20), li))
            x += len(t) * 10 + 10
    return out


def test_norm():
    assert norm("  Ёлка   ДОМОЙ ") == norm("елка домой")
    assert norm("1280х720") == norm("1280x720")         # кириллическая х
    assert norm("Настройки") != norm("Навигатор")


def test_find_phrase_and_case():
    ws = words("Настройки Назад домой", "Яндекс Навигатор 12:45")
    assert find_in(ws, "Домой") == [(160, 0, 50, 20)]
    assert find_in(ws, "яндекс навигатор") == [(0, 50, 160, 20)]
    assert find_in(ws, "Навигатор Настройки") == []      # слова из разных строк не склеиваются
    assert find_in(ws, "Навиг") == []
    assert find_in(ws, "Навиг", partial=True) == [(70, 50, 90, 20)]


def test_norm_languages():
    assert norm("Système") == norm("Systeme")              # английский движок теряет акценты
    assert norm("正在 启动 桌面。") == norm("正在启动桌面")     # иероглифы OCR режет на слова, точка в конце
    assert find_in(words("Systöme"), "Système") == []
    assert find_in(words("Systöme"), "Système", fuzz=1)
    assert find_in(words("Sys"), "Système", fuzz=1) == []


def test_wrapped_label():
    ws = [Word("Анимации", (500, 600, 120, 24), 0), Word("кнопок", (500, 630, 90, 24), 1),
          Word("Средняя", (900, 600, 100, 24), 2)]
    assert find_in(ws, "Анимации кнопок") == [(500, 600, 120, 54)]
    assert find_in(ws, "Анимации Средняя") == []          # соседняя колонка - не перенос


def test_truncated():
    from autotest.ocr import truncated
    assert truncated(words("Nothing is playi..."), "Nothing is playing") == "Nothing is playi..."
    assert truncated(words("Nothing is playing"), "Nothing is playing") is None


def test_strings_all_langs():
    from autotest.strings import LANGS, Strings, format_android, unescape
    assert unescape(r"Démarrage de l\'écran") == "Démarrage de l'écran"
    assert unescape('"два  пробела"') == "два  пробела"
    assert unescape("два \n  пробела") == "два пробела"
    assert format_android("%1$s из %2$d, %%", "а", 3) == "а из 3, %"
    for lang in LANGS:
        R = Strings(lang)
        assert R.l_sec_look and R.l_settings and R("l_music_idle")
    assert Strings("ru").l_sec_look == "Вид" and Strings("en").l_sec_look == "Look"


def test_find_order_top_to_bottom():
    ws = words("Медиа", "Медиа")
    assert [r[1] for r in find_in(ws, "медиа")] == [0, 50]


def frame(color=(30, 30, 30)):
    f = np.zeros((120, 200, 3), np.uint8)
    f[:] = color
    return f


def test_diff_noise_and_masks():
    a = frame()
    b = a.copy()
    b[::7, ::7] = (40, 40, 40)                   # шум кодека ниже tol
    assert C.diff(a, b).ratio == 0.0
    c = a.copy()
    c[0:20, 0:50] = (250, 250, 250)              # «часы» поменялись
    assert C.diff(a, c).ratio > 0.03
    assert C.diff(a, c, [(0, 0, 60, 25)]).ratio == 0.0
    assert C.diff(a, frame()[:100]).same_size is False


def test_png_roundtrip_cyrillic_path(tmp_path):
    f = frame((10, 200, 30))
    p = tmp_path / "эталон" / "главный.png"
    C.save(p, f)
    assert (C.load(p) == f).all()


def test_find_image():
    f = frame()
    icon = np.zeros((16, 16, 3), np.uint8)
    icon[4:12, :] = (255, 200, 0)
    icon[:, 6:10] = (0, 120, 255)
    f[70:86, 130:146] = icon
    hit = C.find_image(f, icon)
    assert hit and hit[0] == (130, 70, 16, 16)
    assert C.find_image(frame(), icon) is None


def test_windows_ocr_cyrillic():
    pytest.importorskip("winrt.windows.media.ocr")
    pytest.importorskip("PIL")
    from PIL import Image, ImageDraw, ImageFont
    from autotest.ocr import WinOcr
    try:
        ocr = WinOcr("ru")
    except RuntimeError as e:
        pytest.skip(str(e))
    img = Image.new("RGB", (1280, 200), (20, 20, 24))
    ImageDraw.Draw(img).text((40, 60), "Настройки   Яндекс Навигатор", fill=(240, 240, 240),
                             font=ImageFont.truetype(r"C:\Windows\Fonts\arial.ttf", 36))
    ws = ocr.read(np.array(img))
    assert find_in(ws, "настройки")
    assert find_in(ws, "Яндекс Навигатор")
