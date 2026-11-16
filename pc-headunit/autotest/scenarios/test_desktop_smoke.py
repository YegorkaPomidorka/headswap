"""Пилот: рабочий стол Headswap 4.x на ГУ honda 1280x720 (bridgeMode=desktop).

Координаты боковой панели - для 1280x720; кнопки без подписей ищутся по месту, текст - через OCR.
Перед каждым тестом фикстура жмёт HOME: лаунчер помнит экран прошлого запуска, а шестерёнка - переключатель.
"""
import pytest

CLOCK = (0, 0, 150, 90)                 # часы в углу
STATUS = (450, 0, 830, 62)              # дата, сеть, громкость, заряд
APP_TILE = (540, 63, 712, 500)          # плитка навигатора/приложения: живая картинка

RAIL_APPS = (72, 264)                   # «Все приложения»
RAIL_SETTINGS = (72, 383)               # шестерёнка (повторный тап закрывает настройки)

SECTIONS = [
    ("Вид", "Тема"),
    ("Навигатор", "Перезапустить навигатор"),
    ("Музыка", "Первый слот"),
    ("Экран", "Раскладка"),
    ("Система", "Алиса только голосом"),   # «Версия Headswap» OCR читает то так, то «Headswap ... | Версия»
]


@pytest.fixture(autouse=True)
def only_1280(stand):
    if stand.video_size != (1280, 720) or stand.hu_profile != "honda":
        pytest.skip("координаты сценария - для honda 1280x720")


def test_home(stand):
    stand.wait_text("Ничего не играет")
    stand.check("home", masks=[CLOCK, STATUS, APP_TILE])


def test_settings_sections(stand):
    stand.tap(*RAIL_SETTINGS)
    stand.wait_text("Настройки")
    for section, marker in SECTIONS:
        stand.tap_text(section)
        stand.wait_text(marker, partial=True)
    stand.tap_text("Музыка")
    stand.check("settings-music", masks=[CLOCK, STATUS])
    stand.tap(*RAIL_SETTINGS)
    stand.wait_no_text("Настройки")


def test_all_apps(stand):
    stand.tap(*RAIL_APPS)
    stand.wait_text("Все приложения")
    stand.back()
    stand.wait_no_text("Все приложения")
