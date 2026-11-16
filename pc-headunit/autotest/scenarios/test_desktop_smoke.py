"""Пилот: рабочий стол Headswap 4.x на ГУ honda 1280x720 (bridgeMode=desktop), на любом языке из --lang.

Тексты - ключами строк приложения (stand.R.<ключ>), координаты и маски - для 1280x720 слева направо; в арабском лаунчер
зеркалится (рейка и часы справа), поэтому точки идут через stand.at(), рамки через stand.area().
Перед каждым тестом фикстура жмёт HOME: лаунчер помнит экран прошлого запуска, а шестерёнка - переключатель.
"""
import pytest

CLOCK = (0, 0, 150, 90)                 # часы в углу
STATUS = (450, 0, 830, 62)              # дата, сеть, громкость, заряд
APP_TILE = (540, 63, 712, 500)          # плитка навигатора/приложения: живая картинка
MUSIC_TILE = (164, 63, 356, 500)        # плитка музыки: трек зависит от того, что играло на телефоне

RAIL_APPS = (72, 264)                   # «Все приложения»
RAIL_SETTINGS = (72, 383)               # шестерёнка (повторный тап закрывает настройки)

# раздел настроек -> строка, которая есть только на его странице
SECTIONS = [
    ("l_sec_look", "l_s_anim"),          # подпись в две строки («Анимации / кнопок») - проверка склейки
    ("l_sec_nav", "l_nav_restart"),
    ("l_sec_music", "l_s_slot1"),
    ("l_sec_home", "l_s_layout"),
    ("l_sec_system", "l_s_alice_voice"),   # не «Версия Headswap»: её OCR читает то так, то «Headswap ... | Версия»
]


@pytest.fixture(autouse=True)
def only_1280(stand):
    if stand.video_size != (1280, 720) or stand.hu_profile != "honda":
        pytest.skip("координаты сценария - для honda 1280x720")


def test_home(stand):
    stand.wait_text(stand.R.l_phone_no_access)
    stand.check("home", masks=[stand.area(r) for r in (CLOCK, STATUS, APP_TILE, MUSIC_TILE)])


def test_settings_sections(stand):
    R = stand.R
    stand.tap(*stand.at(*RAIL_SETTINGS))
    stand.wait_text(R.l_settings)
    for section, marker in SECTIONS:
        stand.tap_text(R(section))
        stand.wait_text(R(marker), partial=True)
    stand.tap_text(R.l_sec_look)       # не «Музыка»: порядок плееров там зависит от того, что играло последним
    stand.check("settings-look", masks=[stand.area(r) for r in (CLOCK, STATUS)])
    stand.tap(*stand.at(*RAIL_SETTINGS))
    stand.wait_no_text(R.l_settings)


def test_all_apps(stand):
    stand.tap(*stand.at(*RAIL_APPS))
    stand.wait_text(stand.R.l_all_apps)
    stand.back()
    stand.wait_no_text(stand.R.l_all_apps)
