"""Пилот: рабочий стол Headswap 4.x на ГУ honda 1280x720 (bridgeMode=desktop), на любом языке из --lang.

Тексты - ключами строк приложения (stand.R.<ключ>), координаты и маски - для 1280x720 слева направо; в арабском лаунчер
зеркалится (рейка и часы справа), поэтому точки идут через stand.at(), рамки через stand.area().
Перед каждым тестом фикстура жмёт HOME: лаунчер помнит экран прошлого запуска, а шестерёнка - переключатель.
"""
import re
import subprocess
import time

import numpy as np

import pytest

from autotest.ocr import WinOcr, lines_text
from autotest.stand import StandError

CLOCK = (0, 0, 150, 90)                 # часы в углу
STATUS = (450, 0, 830, 62)              # дата, сеть, громкость, заряд
APP_TILE = (540, 63, 712, 500)          # плитка навигатора/приложения: живая картинка
NOTIFY = (150, 63, 780, 150)            # всплывающее уведомление телефона (Telegram и т. п.) и его язычок
MUSIC_TILE = (164, 63, 356, 500)        # плитка музыки: трек зависит от того, что играло на телефоне

RAIL_APPS = (72, 264)                   # «Все приложения»
RAIL_SETTINGS = (72, 383)               # шестерёнка (повторный тап закрывает настройки); в «Стекле» там же
RAIL_NAVI = (72, 650)                   # навигация: золотой блок («Сплошная») или линза («Стекло»)

# «Вид» (полный размер, альбом): запасные точки для сегментов и тумблера, если OCR не прочёл надпись (иероглифы, DD.MM)
LOOK_SOLID = (1008, 432)                # «Сплошная»
LOOK_GLASS = (1180, 432)                # «Стекло»
LOOK_DMY = (1050, 645)                  # «ДД.ММ»
LOOK_MDY = (1180, 645)                  # «ММ.ДД»
LOOK_CLOCK12 = (797, 643)               # тумблер «12-часовой формат»
LOOK_CLOCK12_BOX = (755, 612, 100, 62)  # его рамка: состояние тумблера сверяется по кадру (OCR часов на zh теряет цифры)

# раздел настроек -> строка, которая есть только на его странице
SECTIONS = [
    ("l_sec_look", "l_s_anim"),          # подпись в две строки («Анимации / кнопок») - проверка склейки
    ("l_sec_nav", "l_nav_restart"),
    ("l_sec_music", "l_s_autoplay"),
    ("l_sec_home", "l_s_layout"),
    ("l_sec_system", "l_s_notifications"),  # не «Версия Headswap» (OCR читает её то так, то «Headswap ... | Версия») и не «Alice»: китайский OCR пишет «AIice»
]


@pytest.fixture(autouse=True)
def only_1280(stand):
    if stand.video_size != (1280, 720) or stand.hu_profile != "honda":
        pytest.skip("координаты сценария - для honda 1280x720")


def test_home(stand):
    stand.wait_text(stand.R.l_phone_no_access)
    stand.check("home", masks=[stand.area(r) for r in (CLOCK, STATUS, NOTIFY, APP_TILE, MUSIC_TILE)])


def test_settings_sections(stand):
    R = stand.R
    stand.tap(*stand.at(*RAIL_SETTINGS))
    stand.wait_text(R.l_settings)
    for section, marker in SECTIONS:
        stand.tap_text(R(section))
        stand.wait_text(R(marker), partial=True)
    stand.tap_text(R.l_sec_look)       # не «Музыка»: порядок плееров там зависит от того, что играло последним
    stand.wait_text(R.l_s_rail_style, partial=True)     # без ожидания эталон мог сняться на полпути смены страницы
    stand.check("settings-look", masks=[stand.area(r) for r in (CLOCK, STATUS)])
    stand.tap(*stand.at(*RAIL_SETTINGS))
    stand.wait_no_text(R.l_settings)


def test_all_apps(stand):
    stand.tap(*stand.at(*RAIL_APPS))
    stand.wait_text(stand.R.l_all_apps)
    stand.back()
    stand.wait_no_text(stand.R.l_all_apps)


# ---- «Вид»: стиль панели, формат даты, 12 ч, навигатор на рейке. Каждый тест возвращает настройки как были. ----

def _home_masks(stand):
    return [stand.area(r) for r in (CLOCK, STATUS, NOTIFY, APP_TILE, MUSIC_TILE)]


def _open_look(stand):
    """Настройки -> «Вид» (HOME перед тестом закрыл настройки)."""
    R = stand.R
    stand.tap(*stand.at(*RAIL_SETTINGS))
    stand.wait_text(R.l_settings)
    stand.tap_text(R.l_sec_look)
    stand.wait_text(R.l_s_rail_style, partial=True)


def _segment(stand, key, fallback):
    """Тап по сегменту «Вида»: по надписи, а если OCR её не прочёл - по точке (макет одинаков на всех языках)."""
    try:
        stand.tap_text(stand.R(key), timeout=3)
    except StandError:
        stand.act(f"segment {key}: OCR missed, tap by point {fallback}")
        stand.tap(*stand.at(*fallback))


def _phone_now(fmt):
    """Время телефона (дата и часы в лаунчере - его), не ПК."""
    return subprocess.run(["adb", "shell", "date", fmt], capture_output=True, text=True, timeout=10).stdout.strip()


_digits_ocr = None


def _region_digits(stand, rect):
    """Цифры в рамке английским OCR: цифры часов латинские на любом языке, а арабский движок «7:36» с «PM» под ним
    не читает совсем (пустая строка)."""
    global _digits_ocr
    if _digits_ocr is None:
        _digits_ocr = WinOcr("en")
    words = _digits_ocr.read(stand.settle(), stand.area(rect))
    return re.sub(r"\D", "", "".join(lines_text(words)))


def _switch_diff(stand, ref):
    x, y, w, h = stand.area(LOOK_CLOCK12_BOX)
    return float(np.abs(stand.settle()[y:y + h, x:x + w].astype(int) - ref).mean())


def _wait_digits(stand, ok, msg, timeout=10.0):
    """Ждать, пока цифры часов на рейке (OCR) подойдут под ok: лаунчер перечитывает время не мгновенно."""
    end = time.monotonic() + timeout
    while True:
        got = _region_digits(stand, CLOCK)
        if ok(got):
            return
        if time.monotonic() > end:
            stand.shot("clock-wrong")
            raise StandError(f"{msg}: на рейке {got!r}")
        time.sleep(0.3)


def test_rail_style_glass(stand):
    """«Стиль панели»: «Стекло» -> эталон главного в стекле, «Сплошная» -> главный как в test_home."""
    R = stand.R
    _open_look(stand)
    try:
        _segment(stand, "l_s_style_glass", LOOK_GLASS)
        stand.home()
        stand.wait_text(R.l_phone_no_access)
        stand.check("home-glass", masks=_home_masks(stand))
    finally:
        stand.home()
        _open_look(stand)
        _segment(stand, "l_s_style_solid", LOOK_SOLID)
    stand.home()
    stand.wait_text(R.l_phone_no_access)
    stand.check("home", masks=_home_masks(stand))


def _status_date(stand):
    """Дата из строки состояния как (первое, второе): «Вт 17.11» -> ("17", "11"). OCR иногда ставит вокруг точки пробелы
    или «·» (китайский: «17 · 11»), поэтому ищем две пары цифр через любой разделитель."""
    text = " ".join(stand.text(region=stand.area(STATUS)))
    m = re.search(r"(?<!\d)(\d\d)\s*[.·,．。]\s*(\d\d)(?!\d)", text)
    return (m.group(1), m.group(2)) if m else None


def _wait_date(stand, want, timeout=10.0):
    end = time.monotonic() + timeout
    while True:
        got = _status_date(stand)
        if got == want:
            return
        if time.monotonic() > end:
            stand.shot("date-wrong")
            raise StandError(f"дата в строке состояния {got}, ждали {want}")
        time.sleep(0.3)


def test_date_format(stand):
    """«Дата»: ДД.ММ <-> ММ.ДД меняет порядок в строке состояния (проверка текстом, дата телефона)."""
    dd, mm = _phone_now("+%d.%m").split(".")
    if dd == mm:
        pytest.skip(f"сегодня {dd}.{mm}: порядок не различить")
    _open_look(stand)
    _wait_date(stand, (dd, mm))
    try:
        _segment(stand, "l_s_date_mdy", LOOK_MDY)
        _wait_date(stand, (mm, dd))
    finally:
        _segment(stand, "l_s_date_dmy", LOOK_DMY)
    _wait_date(stand, (dd, mm))


def test_clock12_in_look(stand):
    """12 ч теперь в «Виде»: тумблер меняет часы на рейке (при 10-12 ч текст не меняется, тогда только кадр тумблера)."""
    h24 = int(_phone_now("+%H"))
    _open_look(stand)
    x, y, w, h = stand.area(LOOK_CLOCK12_BOX)
    off = stand.settle()[y:y + h, x:x + w].astype(int)
    before_digits = _region_digits(stand, CLOCK)
    stand.tap(*stand.at(*LOOK_CLOCK12))
    try:
        assert _switch_diff(stand, off) > 10, "тумблер 12 ч не переключился"
        if h24 not in (10, 11, 12):
            want = f"{h24 % 12 or 12}"
            _wait_digits(stand, lambda d: d.startswith(want) and not d.startswith(f"{h24:02d}"),
                         f"часы не перешли на 12 ч (было {before_digits!r}, час {h24})")
    finally:
        stand.tap(*stand.at(*LOOK_CLOCK12))
    assert _switch_diff(stand, off) < 6, "тумблер 12 ч не вернулся"
    # в «Экране» тумблера 12 ч больше нет
    stand.tap_text(stand.R.l_sec_home)
    stand.wait_text(stand.R.l_s_layout, partial=True)
    stand.wait_no_text(stand.R.l_s_clock12, partial=True)


def test_navigator_button_toggles(stand):
    """Нажатие навигации открывает навигатор на весь экран, повторное - возвращает главный."""
    R = stand.R
    stand.wait_text(R.l_phone_no_access)
    stand.tap(*stand.at(*RAIL_NAVI))
    stand.wait_no_text(R.l_phone_no_access, timeout=15)
    stand.tap(*stand.at(*RAIL_NAVI))
    stand.wait_text(R.l_phone_no_access, timeout=15)
