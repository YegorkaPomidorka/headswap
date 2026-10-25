# Headswap — Android Auto on CarLife head units

**English.** Headswap is an Android app that runs Android Auto on head units that only support Baidu CarLife 2.0 over USB (common on Chinese-market cars). The phone talks to Android Auto's "head unit server" on 127.0.0.1:5277 as if it were a head unit, re-encodes the 1280x720 video, and presents itself to the car as a CarLife phone over USB (AOA). Touch is mapped 1:1, audio and microphone go over USB. No dongles. Verified on a 2024 Honda Vezel. Unofficial, not affiliated with Google, Baidu or Honda. GPL-3.0.

![Android Auto на штатной ГУ Honda Vezel](docs/screenshot.jpg)

## Что это и зачем

На многих машинах китайского рынка в штатной ГУ есть только Baidu CarLife, а Android Auto нет. Обычно это лечат донглами или заменой ГУ. Headswap обходится без них: приложение на самом телефоне делает Android Auto «головным устройством» для самого себя и перекладывает картинку и управление в CarLife, который ГУ понимает.

## Как работает

```
телефон:  Android Auto  <->  Headswap  <->  USB (AOA, CarLife 2.0)  <->  ГУ с CarLife
          (сервер ГУ,         (мост)
           127.0.0.1:5277)
```

- Приложение подключается к «серверу головного устройства» Android Auto (режим разработчика AA) по TCP как head unit: версия, TLS, сервисы, видео H.264 1280x720.
- Для ГУ оно выступает телефоном CarLife: переключается в USB-аксессуар (AOA), проходит рукопожатие CarLife 2.0 и отдаёт видео в канал VIDEO.
- Видео перекодируется, кадр повторяется каждые 100 мс. Без этого ГУ рвёт USB на статичном экране.
- Касания идут 1:1: координаты ГУ пересчитываются в кадр 1280x720 и уходят в AA как тач-события.
- Звук AA и микрофон машины идут по USB (каналы CarLife), не по Bluetooth.
- Сервер ГУ в Android Auto пользователь включает сам, один раз: приложение только открывает настройки AA (⋮ → «Вкл. сервер головного устр-ва»). Сервер одноразовый, поэтому Headswap его не выключает и сессию AA без нужды не закрывает.

## Совместимость

Подтверждённые автомобили (штатные ГУ с Baidu CarLife, все для рынка Китая):

| Машина | Baidu CarLife |
| --- | --- |
| Dongfeng Peugeot 5008 2024 | — |
| Honda Breeze, 2 поколение | — |
| Honda CR-V, 6 поколение, дорестайл | — |
| Honda CR-V 2026 1.5T | 2.0 |
| Honda CR-V 2026 гибрид (работает с оговорками) | — |
| Honda Vezel 2022–2024 (GAC Honda) | 2.0 |
| Hyundai Elantra, 7 поколение, дорестайл | — |
| Hyundai ix35 2021 | — |
| Jetta VS5/VS7 (только с Baidu CarLife, версия с J-Link не поддерживается) | 2.0 |
| Kia K3 2023 | — |
| Kia Sportage Ace | — |
| Mazda CX-5 2025–2026 | 2.0 |
| Mazda CX-50 | — |
| Mitsubishi Outlander 4 2022 | — |
| Skoda Kamiq GT 2021 | — |
| Skoda Octavia Pro | 2.0 |
| Toyota Corolla Cross 2024–2025 | 2.0 |
| Toyota Levin | — |
| Volkswagen e-Bora 2019 (MIB 275A, CNS2 Delphi) | 2.0 |
| Volkswagen Golf 8, комплектация Pro, 2021 | — |
| Volkswagen ID.Unyx | — |
| Volkswagen Lamando L | — |
| Volkswagen Sagitar 2021–2022, дорестайл | 2.0 |
| Volkswagen T-Roc рестайлинг 2026 | — |
| Volkswagen Tharu XR 2025–2026 | 2.1 |
| Volkswagen Tiguan L Pro / Teramont Pro | — |

Актуальный список — на [странице проекта](https://yegorkapomidorka.com/headswap/).

Отчёты о других машинах приветствуются (Telegram и 4PDA ниже). Минимальная версия Android: 10 (API 29).

## Установка (для пользователей)

1. Скачайте APK: https://yegorkapomidorka.com/headswap/
2. В Android Auto включите режим разработчика (Настройки -> 10 тапов по «Версия») и в меню «три точки» пункт «Сервер головного устройства» (Start head unit server). Включать нужно вручную (кнопка «Открыть Android Auto» в приложении): службы спецвозможностей больше нет.
3. Подключите телефон к ГУ по USB.

Обновления: в версиях новее 1.1.10 Headswap сам находит новую версию в [релизах на GitHub](https://github.com/YegorkaPomidorka/headswap/releases) и ставит её поверх старой, настройки сохраняются. Отключается в Настройках → Обновления. При первом обновлении Android попросит разрешить установку приложений из Headswap.

## Сборка из исходников

Нужен JDK 17.

```
cd android
./gradlew assembleDebug
```

Release-подпись читается из `~/.android-keys/headswap-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Без этого файла собирается только debug.

### Сертификат head unit

Приложение не содержит сертификат в репозитории. Android Auto принимает от head unit только сертификат, подписанный Google Automotive Link. Публичные сертификат и ключ, которые используются в открытых проектах, лежат в:

- [opencardev/aasdk](https://github.com/opencardev/aasdk) (продолжение [f1xpl/aasdk](https://github.com/f1xpl/aasdk)): JVC Kenwood, `src/Messenger/Cryptor.cpp` (строки 253-299), он же в `cert/headunit.crt` и `cert/headunit.key`;
- [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived): `app/src/main/res/raw/cert` и `privkey`.

Положите PEM-файлы так:

```
android/app/src/main/assets/aa/jvc/cert.pem
android/app/src/main/assets/aa/jvc/key.pem
```

и/или (набор из headunit-revived)

```
android/app/src/main/assets/aa/hur/cert.pem
android/app/src/main/assets/aa/hur/key.pem
```

Для эмулятора на ПК то же самое в `pc-headunit/certs/jvc/` (и `hur/`). `*.pem` и `*.key` в `.gitignore`.

## Структура репозитория

- `android/`: приложение (Kotlin, пакет `com.yegorkapomidorka.headswap`): мост AA <-> CarLife (`BridgeService`, `CarLifeSession`, `CarLifeState`, `AppLog`), клиент AA (`aa/`), интерфейс (`ui/`).
- `pc-headunit/`: эмулятор ГУ Android Auto на Python для отладки на ПК, см. [pc-headunit/README.md](pc-headunit/README.md).
- `docs/`: спецификации протоколов: [aa-headunit.md](docs/aa-headunit.md) (Android Auto со стороны ГУ), [carlife-protocol.md](docs/carlife-protocol.md) (CarLife по AOA), [carlife-v2-handshake.md](docs/carlife-v2-handshake.md) (рукопожатие CarLife 2.0).

## Ограничения

- Звонки только через Bluetooth HFP: по USB они не идут.
- Сервер ГУ в Android Auto одноразовый: после обрыва сессии его надо включить заново вручную.
- HyperOS запрещает `adb input`, поэтому часть отладочных приёмов недоступна.
- Протокол CarLife 2.0 реализован ровно настолько, насколько его требует проверенная ГУ (без аутентификации и шифрования). ГУ, которые их требуют, не заработают.
- Android Auto может в любой момент поменять поведение сервера ГУ: это недокументированный режим разработчика.

## Ссылки

- Сайт и APK: https://yegorkapomidorka.com/headswap/
- Релизы на GitHub (те же APK, из них обновляется приложение): https://github.com/YegorkaPomidorka/headswap/releases
- Telegram: https://t.me/headswap
- Тема на 4PDA: https://4pda.to/forum/index.php?showtopic=1127415

## Дисклеймер

Неофициальный проект. Не связан с Google, Baidu и Honda; Android Auto, CarLife и Honda являются товарными знаками их владельцев. Используйте на свой риск. Не отвлекайтесь за рулём.

## Лицензия

GNU GPL v3.0, см. [LICENSE](LICENSE). © 2026 Yegor (yegorkapomidorka).

Исключение: `pc-headunit/proto/*.proto` основаны на определениях протокола из [headunit-revived](https://github.com/andreknieriem/headunit-revived) (AGPL-3.0). Всё остальное - GPL-3.0.
