# Архитектура Headswap

Карта для того, кто открыл репо впервые (человек или Claude). Что где лежит, как идут данные, что нельзя ломать.
Почему сделано именно так — в [docs/adr/](adr/README.md). Протоколы — в `docs/carlife-protocol.md`,
`docs/carlife-v2-handshake.md`, `docs/aa-headunit.md`.

Ссылки на код даны по именам классов и функций, номера строк не пишем: они быстро устаревают. Искать грепом
или через LSP. `$P` = `android/app/src/main/java/com/yegorkapomidorka/headswap`.

## Что это

Телефон стоит между протоколом Baidu CarLife, на котором говорит ГУ, и Android Auto на этом же телефоне:

```
ГУ машины  ⇄  CarLife (USB AOA или Wi-Fi)  ⇄  Headswap  ⇄  TCP 127.0.0.1:5277  ⇄  Android Auto (сервер ГУ)
```

Для ГУ Headswap — телефон с Baidu CarLife (MD). Для AA — головное устройство («Desktop Head Unit»). Сервер ГУ
в AA пользователь включает сам в режиме разработчика AA (ADR 0001, 0002).

ГУ общается с Baidu CarLife, а не с AA, поэтому часть функций AA теряется: всё, что AA ждёт от машины, приходит
только если для этого есть аналог в CarLife. Сюда относятся тёмная/светлая тема карты (датчика фар в CarLife нет,
есть только CHANGE_MD_THEME), передача и задний ход, скорость и прочие события машины (данные машины CarLife
мы вслепую не запрашиваем, ADR 0010). Прежде чем обещать функцию AA, проверить, есть ли для неё сообщение в CarLife.

## Стек и сборка

- Один gradle-модуль `:app` (`android/`), minSdk 29, target 37, JDK 17.
- **Без AndroidX и Compose.** `MainActivity : android.app.Activity`, экраны строятся кодом (`ui/Kit.kt`, `ui/Screen.kt`).
  Строки: `res/values` (en) и `res/values-ru`, обе правятся вместе.
- Варианты: `debug`, `release`, `smoke` (buildType: debug с суффиксом `.smoke`, ставится рядом с release).
  Flavors нет. `-Pwlbeta=false` выключает воздух.
- Сертификаты AA (`assets/aa/{hur,jvc}`) в git нет. Задача `syncAaCerts` берёт их из основного checkout, release без них падает.
  Подпись release — `~/.android-keys/headswap-release.properties`.
- Тесты: `cd android; ./gradlew testDebugUnitTest` (JUnit4, `android/app/src/test/...`). Python: `pc-headunit/tests/`,
  `python -X utf8 -m unittest tools/test_logdiag.py`.

## Пакеты

| Где | Что |
|---|---|
| `$P/` (корень) | Сторона CarLife и сервис. `BridgeService` — оркестратор. `CarLifeSession` — одна сессия с ГУ. `Handshake`, `HuAuth` — рукопожатие и HU_AUTHEN. `Protocol.kt` (`Ch`, `Msg`, `Wire`, `FrameParser`), `Proto.kt` (свой protobuf). `VideoSource` — заглушка до прихода AA. `AppLog`, `CrashLog`. |
| `$P/aa/` | Сторона AA и мозг моста. `AapSession` (TCP+TLS к 5277), `AapMessages` (SDR, каналы), `AaBridge.kt` (`AaBridgeCore` — всё связывание и флаги ГУ; `AaConnector` — переподключение), `BridgeHost` (держит AA между сессиями CarLife). Видео: `AaTranscoder`, `GlRelay`, `FeedQueue`, `IdrSplice`/`IdrIntra`/`RefDecoder`, `SpsCrop`, `VideoMode`, `CarPreset`, `BitratePolicy`, `IdrBitrateCeiling`, `ThermalThrottle`. Звук: `AaAudio`, `MusicResampler`, `Equalizer`, `TtsPreroll`. Микрофон: `AaMic`, `PhoneMicCapture`. Мета: `AaMeta` (`MetaRelay`). Тач: `TouchPacer`, `TouchDecimator`. Настройки: `AaSettings` (`AaPrefs`). Диагностика: `AaServerCheck`, `AaProbe`. |
| `$P/link/` | Транспорт к ГУ. `CarTransport` + `TransportCaps`, `StreamTransport` (USB и debug-TCP, один поток), `MultiSocketTransport` (воздух, 6 сокетов). `link/bt` — RFCOMM `carlife`, вход в Wi-Fi Direct (`CarlifeBtProbe`, `P2pJoin`). `link/car` — выбранная машина, автостарт по ACL, «Wi-Fi машины» (`CarProfile`, `CarAclReceiver`, `CarNetwork`, `CarLogic`). `link/wifi` — маяк `HotspotBeacon` (UDP 7999), `HotspotDetect`. `link/diag/WLog` — лог воздуха. |
| `$P/ui/` | Экраны (`HomeScreen`, `OnboardingScreen`, `SettingsScreen`, `DevScreen`, `DiagScreen`, `CarChooser`), чистая логика (`HeadswapState`/`HomeLogic`, `Readiness`), `LogShare` (zip логов). |
| `$P/diag/` | `LogDiag` — Kotlin-порт `tools/logdiag.py`, те же правила. |
| `$P/update/` | `UpdateLogic` (GitHub releases/latest, чистая), `Updater` (скачать, SHA-256, PackageInstaller). ADR 0012. |
| `src/debug`, `src/release` | `DebugTransport.kt`: в debug — TCP к эмулятору ГУ, в release — заглушка. |
| `pc-headunit/` | Python. `hu.py` — AA-ГУ для ПК (эталоны `golden.txt` для тестов). `carlife_hu.py` — эмулятор ГУ CarLife для проверки моста. |
| `tools/` | `logdiag.py` + `logdiag_rules.json` (причина обрыва по логу), `logdigest.py` (сводка лога). |

Три файла-ядра большие: `AaBridge.kt` (~2800 строк), `CarLifeSession.kt` (~1500), `BridgeService.kt` (~1100).
Читать по нужной функции, не целиком.

## Поток данных

**1. Старт.** USB: ГУ переводит телефон в аксессуар Baidu/CarLife → `USB_ACCESSORY_ATTACHED` в `MainActivity` →
`Actions.scan` → разрешение → `BridgeService.onStartCommand` → `command()` → `startSession(StreamTransport.fromPfd(...))`.
Воздух: кнопка (`ACTION_WIFI_B`) или ACL выбранной машины (`CarAclReceiver` → `ACTION_WIFI_B_AUTO`) → `startWifiB` → `startTcp6`.
Кабель побеждает воздух. После обрыва кабеля при машине рядом по BT — переход на воздух через 3 с.

**2. Транспорт воздуха.** Телефон слушает 6 портов (CMD 7240, VIDEO 8240, MEDIA 9240, TTS 9241, VR 9242, TOUCH 9340),
ГУ подключается клиентом. Как телефон и ГУ оказываются в одной сети:
(a) BT RFCOMM `carlife` + вход в P2P-группу ГУ (Mazda G6S); (b) хотспот телефона + маяк `HotspotBeacon`;
(c) «Wi-Fi машины» — телефон входит в сеть ГУ (`CarNetwork`, VW). ADR 0014.

**3. Сессия CarLife** (`CarLifeSession.onCmd`):
HU_PROTOCOL_VERSION → стартовая пачка (`Handshake.startBatch`) → HU_INFO и STATISTIC_INFO (тут определяется ГУ, см. ниже) →
HU_AUTHEN → `HuAuth` → FEATURE_CONFIG (AUDIO_TRANSMISSION_MODE=1 — звук по BT, по USB не шлём) →
VIDEO_ENCODER_INIT (размер берём из видео-режима, а не у ГУ; размер ГУ только запоминаем) → START
(нет за 1,5 с — стартуем сами) → PAUSE/START/RESET по ходу → MD_EXIT.

**4. Сторона AA** (`BridgeService.createHost` → `AaConnector` → `AapSession`): TCP 127.0.0.1:5277 → VERSION → TLS
(сертификаты jvc/hur) → AUTH_COMPLETE → SDR (`head_unit_make/model` всегда «Google / Desktop Head Unit», выбранная марка машины — только в поле `make`; ADR 0015) → каналы
CONTROL0, SENSOR1, VIDEO2, INPUT3, SPEECH4, SYSTEM5, MEDIA6, MIC7, BT8, MEDIA_PLAYBACK9, NAVIGATION10.
Закрытие всегда ByeBye → shutdownOutput → дочитать EOF → close.

**5. Видео** (`AaBridgeCore.onFrame` → `aaFrame`):
- *Перекодирование* (по умолчанию, ADR 0004): `feedTranscoder` → `AaTranscoder` (декодер → `GlRelay` → энкодер,
  повтор кадра каждые 100 мс, нить `aa-trans-feed` через `FeedQueue` — ADR 0011) → `onEncoderFrame` → ГУ.
- *Прямой проброс*: `passthroughFrame`; поля режет `SpsCrop` правкой SPS; раз в 2 с свой IDR (`IdrSplice`, ADR 0005).
- В ГУ: `CarLifeSession.sendVideo` → `AnnexB.fixSps` → `sendVideoData`. Очередь переполнилась (>30 кадров или
  ~500 мс) — кадры выбрасываются до следующего IDR.
- Где решается «проброс или перекодирование» — в четырёх местах: `AaPrefs.transcode`, `VideoGeometry.effective()`,
  `BridgeService.startSession` (режим не кропается — принудительно перекодирование), рантайм-флаги ГУ (Pioneer, Mobis).

**6. Звук.** AA MEDIA(6) → CarLife MEDIA(3), AA SPEECH(4) → CarLife TTS(4), SYSTEM(5) не пересылается.
Музыка 48 кГц стерео (ресэмпл `MusicResampler` для 44,1/32/24/22), TTS 16 кГц моно. Порядок как у Baidu:
перед TTS MODULE_STATUS NAVI=1, TTS_STOP с задержкой 1,5 с.

**7. Микрофон.** ГУ шлёт VR_MIC_DATA (PCM16 16 кГц) → `AaMicRouter` → `AapSession.sendMicPcm`.
Режим car/phone/auto (auto: при тишине от ГУ 2 с — микрофон телефона).

**8. Тач.** `CarLifeSession.onTouch` → `AaBridgeCore.onCarlifeTouch` → `TouchDecimator` → `dispatchTouch` →
`VideoGeometry.mapTouch` → `AapSession.sendTouch`. Кнопки руля — `onCarlifeHardKey`.

**9. Навигация и приборка.** AA NAVIGATION(10) → `MetaRelay` → `sendNaviTurn` (NAVI_NEXT_TURN_INFO), только после
подписки ГУ. Honda: `HondaClusterRoad` ужимает название улицы под приборку.

**10. Метаданные медиа.** AA MEDIA_PLAYBACK(9) → `MetaRelay` → `sendMediaInfo` (обложка JPEG < 30 КБ).

**11. Конец.** Обрыв USB/EOF → `BridgeService.onSessionClosed` → `BridgeHost` держит AA живой 8 с и отдаёт её новой сессии.

## Обходы под конкретные ГУ

Правило: обход включается только на том ГУ, где найден, флагом (ADR 0006). Исключение из `CLAUDE.md`: фиксы по марке
действуют на все модели и каналы марки (Honda определяется по префиксу канала 2077/2083).

ГУ определяется в `AaBridgeCore.onHuInfo` (manufacturer/model/product/board из HU_INFO) и `onHuStatistic`
(`STATISTIC_INFO.channel` — по сути прошивка ГУ). Флаги — `@Volatile var hu*` в `AaBridgeCore`, константы каналов
в его companion.

| Флаг | ГУ | Что делает |
|---|---|---|
| `huDesayG6S` | Mazda CX-5 Desay G6S, ch 20622116 | без маршрутного NAVI=1; на PAUSE пустой VIDEO_DATA (USB) / heartbeat (воздух) |
| `huHonda` | ch 2077xxxx/2083xxxx | roadName под приборку, ждать подписку, перевзвод NAVI |
| `huHonda20772121` | Civic 11, Vezel 20832125 | IDR по тапу после камеры (h295) |
| `huVw20082120` | ch 20082120/22/34/14, 21290002 | **по смыслу «heartbeat на PAUSE каждые 100 мс»**, имя историческое (ADR 0007) |
| `huIdleVideoHeartbeat` | 20082114, 21290002 | heartbeat и без PAUSE после 1 с без кадров |
| `huVwCns2Delphi` | 20082114 | речь в канал VR, пейсер тача, свои кнопки звонка |
| `huVwBt1420` | «VW BT 1420» | воздух: heartbeat на PAUSE, тёплое возобновление |
| `huVw20082138` | VW ID (20082138, 20082128+«VW BT 9491», пресет ID. UNYX) | потолок битрейта по IDR (ADR 0008) |
| `huHyundaiCn7` | product `wp_cn7cpe_cn` | музыка 32 кГц |
| `huPioneerRm56` | Pioneer RM56E8B | перекодирование, 800 кбит/с, ≤10 fps, музыка 22 кГц |
| `huMazda20622111` | ch 20622111 | VIDEO_DATA ≤ 15000 Б, 1,5 Мбит/с, обложка ≤ 12 КБ (ADR 0009) |
| `huMobisNx4c` | board/model CCNC, ch 20022188 | «режим Baidu»: AOA одним write, 20 fps, свой потолок IDR |

**Добавить ГУ:**
1. Константа канала/имени в companion `AaBridgeCore`.
2. `@Volatile var huXxx` с `private set` рядом с остальными.
3. **Сброс в `attach()`.** `AaBridgeCore` живёт дольше сессии CarLife (grace 8 с), без сброса флаг утечёт на следующую ГУ.
4. Установка в `onHuInfo`/`onHuStatistic`.
5. Поведение — в `CarLifeSession` (потолки, нарезка, heartbeat) или в ядре.
6. Тест в `aa/AaBridgeTest.kt`.
7. По желанию пресет `CarPreset` и строки en/ru.

Пресеты разрешений — enum `VideoMode` (R720 по умолчанию, R800, R1024, R1080, WIDE*, WIDE_2_1, WIDE_640, WIDE_976).
Машины — enum `CarPreset`: выбор разом пишет transcode/videoMode/DPI/fps/musicRate. **Enum хранятся в prefs по имени —
не переименовывать.**

## Логи

- `AppLog`: logcat `Headswap` + файл `headswap-<дата>.log` в external files. `AppLog.msg` пишет `RX|TX <КАНАЛ> 0x… ИМЯ len=…`.
  `WLog`: строки `WL <фаза> <событие> k=v`. `CrashLog`: краши и причины прошлых смертей процесса (`low_memory`).
- Отчёт пользователя — zip из `LogShare` (`device.txt`, до 5 логов по 4 МиБ с конца, `wireless.log`). Где брать полные логи — память `headswap-logs`.
- Ориентиры в логе: `== USB_ACCESSORY_ATTACHED`, `сессия: старт`, `== РЕЗЮМЕ СЕССИИ (N с, причина: …)`, `== HU_INFO:`,
  `== STATISTIC_INFO:`, `== ГУ ПРОСИТ РАЗРЕШЕНИЕ`, `VIDEO_ENCODER_PAUSE`, `шлю пустой VIDEO_HEARTBEAT`, `BRIDGE IDR -> CarLife`,
  `AA сессия установлена`, `AA нет VERSION_RESPONSE`, `EOF (ГУ закрыла сокет)`. EIO — системный текст IOException из
  `StreamTransport` («ошибка чтения/записи: …»).
- **Разбор лога начинать с `tools/logdiag.py`**, потом `logdigest.py`. Правила общие с приложением (`tools/logdiag_rules.json`
  → assets через `copyLogdiagRules`). **Формат строк лога — контракт:** поменял строку — поправь маркеры в JSON (ADR 0016).

## Инварианты (не ломать)

- **Сервер AA 5277 одноразовый.** Грязный обрыв или голый TCP connect/close его глушат, перезапустить его приложение не может.
  Закрывать только через ByeBye; новую AA-сессию — после полного закрытия старой; живую AA не рвать из-за смены режима/DPI
  (новый режим — после ручного перезапуска сервера). `AaServerCheck`/`AaProbe` не запускать при живом мосте. ADR 0003.
- **Потеряли видеокадр — P-кадры не слать**, ждать IDR (запрос IDR не чаще раза в 1,5 с).
- **Весь H.264 в ГУ — через `AnnexB.fixSps`**: SPS от Exynos ГУ не принимают.
- **CAR_DATA_START не слать вслепую.** В коде его сейчас нет вообще; слепой START вешал видео на Vezel. Только по RSP
  и только объявленным модулям (ADR 0010).
- **Пакеты в ГУ ≤ 16 КиБ** (`Wire.CHUNK`), у части ГУ ещё меньше (ADR 0009).
- **Флаги `hu*` сбрасываются в `attach()`.**
- **Состояние `BridgeService` трогает только главная нить**, колбэки с других нитей — через `main.post`.
- Кроп-режимы в пробросе работают только через SPS-кроп (`spsCropable`), иначе откат на R720.
- Пароли, SSID, ключи в лог не пишутся.
- Рубильники экспериментов в companion `AaBridgeCore`: `PAUSE_KEEPALIVE_ENABLED`, `NAVI_STATUS_ENABLED`,
  `MODULE_MUSIC_ENABLED`, `ROUTE_NAVI_STATUS_ENABLED`, `AIR_WARM_RESUME_ENABLED`.

## Симптом → куда смотреть

| Симптом | Куда |
|---|---|
| EIO через секунды после PAUSE | heartbeat на PAUSE: `CarLifeSession.pauseHeartbeat`, флаг `huVw20082120`; ADR 0007 |
| EIO сразу после крупного IDR | `IdrBitrateCeiling`, потолки по каналу; ADR 0008 |
| USB встаёт после большого пакета | нарезка `Wire.CHUNK`, `huMazda20622111`; ADR 0009 |
| Чёрный экран после камеры заднего хода | свой IDR (`IdrSplice`), `huHonda20772121`; ADR 0005 |
| Поля, растянуто, не тот размер | `VideoMode`, `SpsCrop`, VIDEO_ENCODER_INIT в логе |
| AA не подключается, `нет VERSION_RESPONSE` | сервер 5277 заглох: ADR 0003; база кейсов (память `hu/`) |
| Обрывы по воздуху | `MultiSocketTransport`, `P2pJoin`, `CarNetwork`, строки `WL`; VPN рвёт сокеты |
| Звук молчит / приглушён | `AaAudio`, AUDIO_TRANSMISSION_MODE, TTS_STOP; источник на ГУ |
| Нет подсказок на приборке | подписка ГУ, `MetaRelay.sendNaviTurn`, `HondaClusterRoad` |
| Тач мимо / рывками | `VideoGeometry.mapTouch`, `TouchDecimator`, `TouchPacer` |

Конкретные машины и прошлые разборы — база кейсов ГУ в памяти Claude (`memory/hu/INDEX.md`, вне репо: там данные
пользователей).
