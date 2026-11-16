# Автотесты UI на стенде ГУ

Python прокликивает экран Headswap на эмуляторе ГУ CarLife (`carlife_hu.py`) и проверяет его без LLM:
текст через OCR Windows, картинку через сравнение с эталоном. Телефон подключается по USB, как для `stand.ps1`.

## Что нужно

- телефон по USB с отладкой; на Xiaomi ещё «Установка через USB» в настройках для разработчиков;
- `ffmpeg` в PATH, `opencv-python`, `numpy`;
- OCR: пакеты pywinrt и русский язык распознавания в Windows:
  `pip install winrt-runtime winrt-Windows.Media.Ocr winrt-Windows.Graphics.Imaging winrt-Windows.Storage.Streams winrt-Windows.Foundation winrt-Windows.Foundation.Collections winrt-Windows.Globalization`.
  rapidocr не подходит: его модели читают кириллицу как латиницу («Настройки» → «HacTpoMK»).

## Запуск

Из `pc-headunit`:

```
python -m pytest autotest/scenarios -v                 # сценарии
python -m pytest autotest/scenarios -v --update-refs   # переснять эталоны
python -m autotest dump "text Настройки" dump back     # разведка экранов без pytest
```

Подготовку телефона (сборка smoke, установка, `bridgeMode`, активация, `adb reverse`) делает
`stand.ps1 -PrepareOnly`. Флаг `--no-prepare` её пропускает.

Отчёт пишется в `out/autotest-<время>/`:
- `steps/NN-*.png` — кадры по шагам;
- `actions.log` — что нажималось;
- `carlife_hu.log` — лог эмулятора;
- лог телефона;
- `fail/*-actual.png` и `*-diff.png` — кадр и разница при несовпадении с эталоном.

## Сценарий

```python
def test_settings_opens(stand):
    stand.tap_text("Настройки")
    stand.wait_text("Вид")
    stand.check("settings", masks=[STATUS])
```

| Метод | Что делает |
|---|---|
| `tap(x, y)`, `long_press`, `swipe(x1, y1, x2, y2)` | касания в пикселях видео: кадр 1:1, без пересчёта |
| `back()`, `home()`, `voice()`, `key(code)` | кнопки ГУ |
| `tap_text("фраза")` | тап по надписи |
| `wait_text`, `wait_no_text`, `has_text`, `text()` | работа с текстом экрана |
| `check("имя", masks=[...])` | сравнение с `refs/<hu>-<W>x<H>/имя.png` |
| `tap_image("иконка")` | поиск картинки `refs/.../icons/иконка.png` |
| `settle()` | ждёт, пока экран перестанет меняться |
| `shot("имя")` | кадр в отчёт |

Текст сравнивается без регистра и с `ё` = `е`: OCR иногда пишет «домой» со строчной.

`check` считает пиксель изменившимся, если разница больше `tol` (24), а экран совпавшим, если таких пикселей не
больше `max_ratio` (1 %). Часы и уведомления закрывать масками `(x, y, w, h)`.

Если эталона нет, `check` его создаёт, а тест помечается skipped («эталон создан»). Новый эталон надо посмотреть
глазами и закоммитить. Эталоны зависят от профиля ГУ, размера экрана, темы и цветов десктопа. Перед съёмкой
эталонов тему и цвета фиксировать.
