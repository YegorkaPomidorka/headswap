# pc-headunit

Минимальный эмулятор головного устройства Android Auto для ПК. Нужен для отладки: подключается к «серверу головного устройства» AA на телефоне (TCP 5277), проходит Version -> TLS -> Auth -> Service Discovery, принимает H.264-видео в `out/video.h264` и пишет весь обмен в консоль и `out/session.log`. Работает без телефона-ГУ и без CarLife, поэтому удобен, чтобы проверить сторону AA отдельно от приложения Headswap.

Протокол описан в [../docs/aa-headunit.md](../docs/aa-headunit.md).

## Зависимости

- Python 3.10+
- `pip install -r requirements.txt` (пакет `protobuf` 5.x; сгенерированные `proto/*_pb2.py` требуют runtime >= 5.27.2)
- для тестов: `pytest`; тест с проверкой потока через `ffprobe` пропускается, если `ffmpeg`/`ffprobe` нет в PATH
- для перегенерации `proto/*_pb2.py` (не нужно для запуска): `pip install grpcio-tools==1.67.1`, затем `python proto/gen.py`

## Сертификат

Эмулятор, как и настоящая ГУ, обязан предъявить телефону клиентский сертификат, подписанный Google Automotive Link. В репозиторий сертификаты не входят (`certs/` в `.gitignore`). Положите PEM-файлы в одну из папок:

```
pc-headunit/certs/jvc/cert.pem   # JVC Kenwood, из aasdk: src/Messenger/Cryptor.cpp, cert/headunit.crt и headunit.key
pc-headunit/certs/jvc/key.pem
pc-headunit/certs/hur/cert.pem   # из headunit-revived: app/src/main/res/raw/cert и privkey
pc-headunit/certs/hur/key.pem
```

Источники: [opencardev/aasdk](https://github.com/opencardev/aasdk) (продолжение [f1xpl/aasdk](https://github.com/f1xpl/aasdk)), [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived). Набор выбирается флагом `--cert jvc|hur` (по умолчанию `jvc`). Для тестов нужны оба набора.

## Запуск

```
adb forward tcp:5277 tcp:5277
python hu.py --duration 30 --cert jvc
```

На телефоне перед этим: Android Auto -> Настройки -> 10 тапов по «Версия» -> меню «три точки» -> «Start head unit server». Сервер принимает одно соединение за запуск и зависает после грубого обрыва, поэтому останавливайте скрипт через Ctrl+C или `--duration`: он закроет сессию корректно.

Полезные флаги: `--check` (локальная проверка сертификата и TLS без подключения), `--touch-test` (тап в центр через 10 с после первого кадра), `--resolution`, `--fps`, `--moto`, `--full-audio`, `--minimal`. Полный список: `python hu.py --help`.

## Эмулятор ГУ CarLife (`carlife_hu.py`)

Только стандартная библиотека. По умолчанию (`--transport stream`) это TCP-сервер: мост Headswap на телефоне подключается к нему одним соединением через `adb reverse tcp:7240 tcp:7240`, весь обмен идёт с внешним заголовком канала. Лог и видео пишутся в `out/carlife_hu.log` и `out/carlife_video.h264`.

```
adb reverse tcp:7240 tcp:7240
python carlife_hu.py --duration 120
```

## Стенд одной командой (`stand.ps1`)

Телефон по USB с adb, на ПК окно ГУ:

```
.\stand.ps1 -Build              # собрать и поставить smoke, режим рабочего стола, активация, окно
.\stand.ps1                     # без пересборки
.\stand.ps1 -Mode aa -Size 1920x720 -HuArgs '--touch-test'
```

Скрипт сам убирает из debug-манифеста на время сборки `TEMPLATE_RENDERER` (иначе smoke не встаёт рядом с release, ADR 0022), пишет `bridgeMode` в `aa.xml`, активирует мост с ПК (`ManualAccessLauncher`), делает `adb reverse`, запускает `carlife_hu.py --view` и сервис моста. Логи эмулятора и телефона кладёт в `out/stand-<время>/`.

## Живое окно

`--view` открывает окно с видео от моста в реальном времени; клики мышью уходят мосту как касания ГУ. Нужны tkinter (идёт с Python для Windows) и `ffmpeg` в PATH. Эмулятор слушает порт, поэтому запускается первым, потом мост:

```
adb reverse tcp:7240 tcp:7240
python carlife_hu.py --view --size 1920x720
# в другом терминале, пока окно ждёт мост:
adb shell am start-foreground-service -n com.yegorkapomidorka.headswap.smoke/com.yegorkapomidorka.headswap.BridgeService --ei debug_tcp 7240
```

Пакет smoke (`applicationId` с суффиксом `.smoke`) собирается задачей `assembleSmoke`; в нём включён `debug_tcp`. Имя класса сервиса полное: он лежит в `namespace` `com.yegorkapomidorka.headswap`, а сокращение `.BridgeService` `am` развернёт в пакет `...smoke`. У debug-сборки пакет `com.yegorkapomidorka.headswap`, а `--ei debug_tcp 7240` тот же.

- `--size WxH` (по умолчанию `1920x720`) — экран ГУ, который эмулятор просит в `VIDEO_ENCODER_INIT`. Мост отвечает своим размером в `INIT_DONE`; в логе обе цифры (`video size from INIT_DONE: 1920x720 (same as asked)`), окно подстраивается под ответ моста.
- `--view-scale K` — масштаб окна. По умолчанию подбирается под экран ПК (не больше 1.0); координаты кликов пересчитываются обратно в пиксели видео.
- `--duration` с `--view` по умолчанию без ограничения; закрытие окна и Ctrl+C завершают сессию так же, как истечение времени (итог в логе).
- Левая кнопка: нажатие = DOWN, перетаскивание = MOVE (не чаще 60 раз в секунду), отпускание = UP. Правая кнопка, Esc, Backspace — хард-кнопка BACK (`TOUCH_CAR_HARD_KEY_CODE`, код 0x0E); средняя кнопка и Home — HOME (0x01; мост транслирует его в Android HOME только в режиме Headswap Desktop).
- Работает и с `--transport tcp6`.

Конвейер: видео пишется в `out/carlife_video.h264` и параллельно уходит в `ffmpeg` (`-f h264 -i pipe:0 -f rawvideo -pix_fmt rgb24`), кадры показываются через tkinter без Pillow. Сетевой цикл эмулятора окном не блокируется: ffmpeg кормится отдельной нитью, в окно идёт только последний кадр. Задержка картинки около 70 мс. Проверка конвейера без телефона: `python carlife_view.py file.h264 1920x720 0.75`.

## Режим 6 сокетов (беспроводной формат)

В беспроводном формате CarLife у каждого канала свой TCP-сокет и нет внешнего заголовка. Телефон слушает шесть портов, эмулятор подключается к ним как клиент (CMD 7240, VIDEO 8240, MEDIA 9240, TTS 9241, VR 9242, TOUCH 9340). Порты достаём с телефона через `adb forward`, поэтому хост по умолчанию 127.0.0.1:

```
adb forward tcp:7240 tcp:7240
adb forward tcp:8240 tcp:8240
adb forward tcp:9240 tcp:9240
adb forward tcp:9241 tcp:9241
adb forward tcp:9242 tcp:9242
adb forward tcp:9340 tcp:9340
adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_tcp6 true
python carlife_hu.py --transport tcp6 --touch-test
```

Сначала запускается мост на телефоне (он открывает порты), потом эмулятор. Логика сессии та же: HU_PROTOCOL_VERSION, init burst после MD_INFO, авторизация, heartbeat раз в секунду, тап через 15 с после первого кадра, итог в конце. Для Data-канала (порт 9440, телефон его принимает и игнорирует) есть `--extra-data-socket`.

Проверка реакции моста на потерю канала: эмулятор закрывает один сокет и ждёт, закроет ли мост остальные.

```
python carlife_hu.py --transport tcp6 --kill-chan VIDEO --kill-after 10
```

`--kill-chan` принимает CMD, VIDEO, MEDIA, TTS, VR, TOUCH; `--kill-after` считается от первого видеокадра. В итоге будет строка `kill: VIDEO at 12.3 s, bridge closed others after 0.4 s` или `bridge did NOT close others within 10 s` (последнее значит, что мост остался висеть с частью каналов).

## Циклы паузы

Сценарий «ГУ ставит видео на паузу и снова запускает» (на START мост сбрасывает декодер и ждёт новый IDR). Работает в обоих режимах:

```
python carlife_hu.py --pause-cycles 20 --pause-every 10 --pause-for 3 --duration 300
```

`--pause-cycles N` (по умолчанию 0 = выключено), `--pause-every S` (по умолчанию 10: секунд от первого видеокадра до первого PAUSE, дальше от каждого START до следующего PAUSE), `--pause-for S` (по умолчанию 3: между PAUSE и START). `--duration` должен покрывать N * (every + for), иначе сессия кончится раньше. Эмулятор шлёт `VIDEO_ENCODER_PAUSE` (0x0001800A, пустое тело, как у START), ждёт, шлёт `VIDEO_ENCODER_START` и меряет от START: время до первого видеокадра и до первого ключевого кадра (в Annex-B буфере есть NAL типа 5). Кадры во время паузы считаются отдельно: мост на PAUSE слать их не должен, первый такой кадр попадает в лог с задержкой от PAUSE (несколько мс = кадр «в пути», десятки и сотни = мост не остановился). В итоге:

```
pause #1: paused_frames=0, start->first_frame=120 ms, start->idr=480 ms
pause cycles: 20, worst start->idr 910 ms, frames during pause total 0
```

`none` вместо времени значит, что кадра или IDR после START так и не пришло до следующего PAUSE или конца сессии; в сводной строке это отмечено как `(no IDR in K cycles)`. Если сессия кончилась посреди паузы, этот цикл в счёт не идёт, в логе будет строка `session ended during pause`.

## Ветка B: хотспот телефона

Телефон раздаёт хотспот, ПК подключается к нему как ГУ-клиент. Адрес телефона вручную не нужен: эмулятор слушает UDP-маяк (`CarlifeHost` раз в 2 с), берёт адрес отправителя первой датаграммы как host и дальше работает как в режиме 6 сокетов.

1. Включить хотспот на телефоне, подключить ПК к нему.
2. На телефоне запустить ветку B:
   ```
   adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_wifi_b true
   ```
3. На ПК:
   ```
   python carlife_hu.py --transport tcp6 --discover --probe-8999 --touch-test
   ```

На Windows разрешите python входящий UDP 7999 в брандмауэре (частная сеть), иначе маяк не дойдёт. Если у ПК несколько сетевых карт, укажите карту хотспота: `--bind-ip <ip ПК в сети хотспота>`.

Флаги: `--discover` (только с `--transport tcp6`), `--beacon-port` (по умолчанию 7999), `--bind-ip` (по умолчанию 0.0.0.0), `--discover-timeout` (по умолчанию 60 с; без маяка сессия завершается с `no beacon within 60 s`), `--probe-8999` (после маяка одна UDP-датаграмма на порт 8999 телефона, ответ ждётся до 2 с и необязателен). В итоге выводятся `discovered_host`, число маяков и результат пробы.

## Тесты

```
python -m pytest tests -q
```

Тесты автономные: «телефон» написан прямо в тесте и общается с сессией через `socket.socketpair()`. Порт 5277 и телефон не нужны.
