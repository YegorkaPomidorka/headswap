---
name: stand
description: Поднимает стенд Headswap без машины — smoke-сборка на телефоне по USB и окно ГУ на ПК (эмулятор CarLife с видео, тачем мышью и кнопками руля). Используй, когда Егор говорит «открой стенд», «подними стенд», «запусти стенд», «покажи на стенде», «проверим на стенде», «/stand».
---

# Стенд

Стенд = живое окно «CarLife HU …» на рабочем столе Егора. Эмулятор без окна (видео в файл) стендом не считается.

## Запуск

Из корня репо (worktree), телефон по USB с adb, экран разблокирован:

```
powershell -File pc-headunit\stand.ps1 -Build      # пересобрать smoke из текущей ветки и поставить
powershell -File pc-headunit\stand.ps1             # без пересборки
```

Запускать через PowerShell с `run_in_background: true`: скрипт ждёт, пока окно не закроют (по умолчанию `-Duration 3600`).

Параметры: `-Mode desktop|aa|keep` (bridgeMode, по умолчанию desktop), `-Size 1280x720` (под Vezel; 1920x720 — широкие ГУ), `-HuArgs '--touch-test'` (любые флаги `carlife_hu.py`), `-NoView`, `-Audio` (активация с `--audio`: звук рабочего стола по USB, ADR 0025).

Звук: окно играет MEDIA и TTS в устройство вывода Windows по умолчанию (модуль `sounddevice`), `-HuArgs '--mute'` — без звука. С включённым «Звук через Headswap» телефон молчит — звук ушёл в ГУ. Тишина на телефоне при выключенном — сначала проверить громкость музыки на динамике (`dumpsys audio`, STREAM_MUSIC speaker).

Что делает скрипт: на время сборки убирает из debug-манифеста `TEMPLATE_RENDERER` (иначе smoke не встаёт рядом с release, ADR 0022), ставит smoke, пишет `bridgeMode`, активирует мост с ПК (`ManualAccessLauncher`), `adb reverse tcp:7240`, запускает `carlife_hu.py --view` и `BridgeService --ei debug_tcp 7240`. Логи эмулятора и телефона кладёт в `pc-headunit/out/stand-<время>/`.

## Проверить, что стенд поднят

Прежде чем говорить «стенд открыт», убедиться, что окно есть и в заголовке идут кадры:

```
Get-Process python | ? MainWindowTitle -like 'CarLife HU*' | select Id,MainWindowTitle
```

Заголовок `CarLife HU  1280x720  NN fps` — видео идёт. `Waiting for the bridge...` дольше 20 с — смотреть вывод скрипта и лог телефона.

## В окне

- ЛКМ — тач (DOWN/MOVE/UP), ПКМ/Esc/Backspace — BACK, СКМ/Home — HOME.
- Ряд кнопок руля под видео: «Голос» — MODULE_CONTROL VR=1 как Vezel; VR_START, треки, Play/Pause, Трубка/Сброс, Назад, Домой — hard key CarLife. Нажатия в логе эмулятора: `view: руль <кнопка>`.
- Кадр для Егора: `ffmpeg -i <out>/carlife_video.h264 -vf "select=eq(n\,N)" -frames:v 1 f.png`.

## Остановить

Закрыть окно или `Get-Process python | ? MainWindowTitle -like 'CarLife HU*' | Stop-Process`. Smoke с телефона удалять только по просьбе: `adb uninstall com.yegorkapomidorka.headswap.smoke`.

## Подводные камни

- Xiaomi Егора не даёт `input keyevent` по adb (нет INJECT_EVENTS) — заблокировать экран с ПК нельзя, просить Егора.
- `INSTALL_FAILED_USER_RESTRICTED` — Егор должен разблокировать телефон и подтвердить установку.
- Активация слетает после force-stop/переустановки — скрипт активирует каждый запуск сам.
