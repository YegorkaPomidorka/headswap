# CarLife 2.0: последовательность MD <-> HU

Описание того, как приложение (роль MD, телефон) разговаривает с ГУ (роль HU) по протоколу CarLife 2.0. Реализовано в `android/` (`CarLifeSession.kt`, `Handshake.kt`, `Protocol.kt`). Основано на наблюдениях за реальной ГУ Honda Vezel 2024 и на открытых источниках, перечисленных в [carlife-protocol.md](carlife-protocol.md). Framing не менялся относительно версии 1.0: внешний `[chan u32 BE][8+len u32 BE]`, CMD 8 байт `[len u16][reserved u16=0][type u32]`, видео 12 байт `[len u32][time u32][type u32]`.

Наблюдение на ГУ Honda Vezel: она шлёт `HU_PROTOCOL_VERSION {2.0}` дважды, затем `CARLIFE_DATA_SUBSCRIBE`, и молчит, пока MD не прислал ничего, кроме MATCH. `HU_AUTHEN_REQUEST` эта ГУ не присылает, шифрования канала нет (в `HU_FEATURE_CONFIG_RESPONSE` ключ `CONTENT_ENCRYPTION` не объявляется).

VW Tharu XR 2026 шлёт `HU_AUTHEN_REQUEST` и режет сообщения иначе: заголовок inner (8 байт) и тело идут отдельными внешними кадрами (`00 00 00 01 00 00 00 08` + `00 04 00 64 00 01 80 01`, затем `00 00 00 01 00 00 00 04` + `08 02 10 01`). `InnerAssembler` держит кадр, равный одному заголовку с declared len > 0, и дописывает следующие кадры того же канала до declared len (cmd-канал: заголовок 8 байт, data-каналы: 12; declared len > 1 МБ для cmd и > `MAX_RX_LEN` (1 МиБ) для data не удерживается). Сообщения целиком в одном кадре проходят без изменений.

## Последовательность

```
HU -> MD  0x18001 HU_PROTOCOL_VERSION {major=2 minor=0}      (повторный игнорируем, второй MATCH не шлём)
MD -> HU  0x10002 PROTOCOL_VERSION_MATCH_STATUS {1}           \
MD -> HU  0x1001B FOREGROUND (пусто)                           |  одна пачка, сразу,
MD -> HU  0x10018 SCREEN_ON (пусто, если экран включён)        |  ничего не ждём от ГУ
MD -> HU  0x10004 MD_INFO CarlifeDeviceInfo                    /
MD -> HU  0x10026 MODULE_STATUS, через 500 мс (44 байта, cnt=7)
HU -> MD  0x18043 CARLIFE_DATA_SUBSCRIBE  `08 01 12 02 08 00`
MD -> HU  0x10044 CARLIFE_DATA_SUBSCRIBE_DONE  `08 01 12 04 08 00 10 01`
HU -> MD  0x18003 HU_INFO                 (сохранить и залогировать, ответа нет)
HU -> MD  0x18027 STATISTIC_INFO
MD -> HU  0x1004B MD_AUTHEN_RESULT {true}   `08 01`
MD -> HU  0x10051 MD_FEATURE_CONFIG_REQUEST (пусто)
MD -> HU  0x10031 CAR_DATA_SUBSCRIBE        (пусто)
HU -> MD  0x18048 HU_AUTHEN_REQUEST {randomValue}  (Honda не шлёт; VW Tharu шлёт)
MD -> HU  0x10049 MD_AUTHEN_RESPONSE {encryptValue}  (сразу в ответ: RSA-подпись приватным ключом Baidu, Base64; ветка "2.0;" — md5-код, см. ниже)
HU -> MD  0x1804A HU_AUTHEN_RESULT                 (лог)
HU -> MD  0x18052 HU_FEATURE_CONFIG_RESPONSE       (лог всех ключей; CONTENT_ENCRYPTION=1 -> только предупреждение)
HU -> MD  0x18045/0x18046 SUBSCRIBE_START/STOP     (лог, данные не шлём)
HU -> MD  0x18007 VIDEO_ENCODER_INIT {w,h,fps}
MD -> HU  0x10008 VIDEO_ENCODER_INIT_DONE {w,h,fps}  (размер энкодера по ступеням от ширины ГУ)
MD -> HU  0x1001B FOREGROUND
HU -> MD  0x18009 VIDEO_ENCODER_START  -> только здесь запускается энкодер
MD -> HU  VIDEO [len][time=unix-сек][0x20001] + H.264 Annex-B; keepalive при простое: 0x20001 len=0
```

До START по каналу VIDEO MD молчит (никаких 0x20002). Порядок после MATCH внутри стартовой пачки и ответ на STATISTIC_INFO зашиты в `Handshake.startBatch` / `Handshake.onStatisticInfo` и проверяются тестами.

## Коды

| Код | Имя | Напр. | Тело |
|---|---|---|---|
| 0x18001 | HU_PROTOCOL_VERSION | HU->MD | `08 major 10 minor` (повторный игнорируется) |
| 0x10002 | PROTOCOL_VERSION_MATCH_STATUS | MD->HU | `08 01` |
| 0x1001B | FOREGROUND | MD->HU | пусто |
| 0x10018 | SCREEN_ON | MD->HU | пусто |
| 0x10004 | MD_INFO | MD->HU | CarlifeDeviceInfo |
| 0x10026 | MODULE_STATUS | MD->HU | CarlifeModuleStatusList; через 500 мс после MATCH |
| 0x18043 | CARLIFE_DATA_SUBSCRIBE | HU->MD | CarlifeSubscribeMobileCarLifeInfoList |
| 0x10044 | CARLIFE_DATA_SUBSCRIBE_DONE | MD->HU | то же, + supportFlag |
| 0x18045 / 0x18046 | SUBSCRIBE_START / STOP | HU->MD | список moduleID (у нас только лог) |
| 0x18003 | HU_INFO | HU->MD | CarlifeDeviceInfo (HU) |
| 0x18027 | STATISTIC_INFO | HU->MD | CarlifeStatisticsInfo |
| 0x1004B | MD_AUTHEN_RESULT | MD->HU | `08 01` |
| 0x10051 | MD_FEATURE_CONFIG_REQUEST | MD->HU | пусто |
| 0x10031 | CAR_DATA_SUBSCRIBE | MD->HU | пусто |
| 0x18048 | HU_AUTHEN_REQUEST | HU->MD | randomValue (string); Honda не шлёт, VW Tharu шлёт |
| 0x10049 | MD_AUTHEN_RESPONSE | MD->HU | encryptValue (string): Base64 подписи `versionVerify(randomValue)` |
| 0x1804A | HU_AUTHEN_RESULT | HU->MD | authenResult (bool) |
| 0x18052 | HU_FEATURE_CONFIG_RESPONSE | HU->MD | CarlifeFeatureConfigList |
| 0x18007 | VIDEO_ENCODER_INIT | HU->MD | `{w,h,fps}` |
| 0x10008 | VIDEO_ENCODER_INIT_DONE | MD->HU | `{w,h,fps}` |
| 0x18009 | VIDEO_ENCODER_START | HU->MD | пусто |
| 0x1800A / 0x1800B | PAUSE / RESET | HU->MD | пусто |
| 0x1800C / 0x1000D | FRAME_RATE_CHANGE / _DONE | HU<->MD | `{fps}`, допустимо 3..30 |
| 0x20001 | VIDEO_DATA / keepalive (len=0) | MD->HU | H.264 |
| 0x20002 | VIDEO_HEARTBEAT | MD->HU | нет; шлётся только при "mobile pausing", нами не используется |
| 0x18056 / 0x10057 | VIDEO_ENCODER_JPEG / ACK | HU<->MD | ACK пустой (у нас ACK, шлём всё равно H.264) |
| 0x18079 / 0x10080 | REQUEST / RESPONSE_MD_THEME_STATE | HU<->MD | `{themeModes}`: 0 светлая, 1 тёмная, 2 авто |

Остальные коды CMD-канала: [carlife-protocol.md](carlife-protocol.md), §3.

## MD_INFO (CarlifeDeviceInfo)

Поля: os="Android", board, bootloader, brand, cpu_abi(2), device, display, fingerprint, hardware, host, cid=Build.ID, manufacturer, model, product, serial="unknown", codename, incremental, release=Build.VERSION.RELEASE, btaddress="unknown", carlifeversion (константа в приложении), carlifeType=1.

SDK: при `SDK_INT >= 33` шлётся `sdk="29"`, `sdk_int=29`. На Android 16 / SDK 36 именно этот случай. Иначе настоящие значения.

## MODULE_STATUS

Для ГУ major >= 2: `cnt=7`, пары (moduleID, statusID) = (1,0)(2,0)(3,0)(4,0)(6,0)(8,0)(9,0). Тело 44 байта: `08 07 12 04 08 01 10 00 ...`. Для major < 2 только пять пар.

## Размер видео

Не "как просит ГУ", а ступени по ширине ГУ. Для major 2 (это не "новый ГУ", wide не бывает): ширина < 800 -> 768x432; < 1024 -> 848x480; < 1280 -> 1024x576; иначе 1280x720. "Новый ГУ" = major 4 или 3.2: wide при w/h >= 2.3 даёт 1024x384 / 1280x480 / 1920x720, а >= 1920 без wide даёт 1920x1080. fps в INIT_DONE = fps из INIT (если 1..60), иначе 30. Переключатель `force720` даёт 1280x720. Слой пользовательских/серверных разрешений не воспроизводится.

## Что не реализовано

- MD_AUTHEN_RESPONSE для ветки `randomValue = "2.0;<rnd>"` (нативный `EncryptionUtils.getVerifyCode` у Baidu): `encryptValue = md5hex("CARLIFE" + brand + model + sdk + <rnd>)`, строчный hex, brand/model/sdk из отправленного MD_INFO, `<rnd>` — вторая часть `split(";")`. Шлёт ГУ Honda CR-V 2026 (Китай, `honda_3ya`, CarLife HU v2.3.4), `<rnd>` там дата вида `2026-10-14-12-01-33`; без ответа ГУ пишет «устройство не авторизовано» и рвёт USB каждые ~40 с. Обычный `randomValue` подписывается как у Baidu 8.6.7, см. выше.
- Шифрование канала: проверенная ГУ его не объявляет. Если `HU_FEATURE_CONFIG_RESPONSE` содержит CONTENT_ENCRYPTION=1, в лог пишется предупреждение; цепочка 0x1006A..0x1006E (RSA public key -> AES key -> ENCRYPT_READY, описана в carlife-protocol.md, §8) не сделана.
- Данные подписки (turn-by-turn, музыка): DONE говорит supportFlag=true для moduleID 0 и 2, но после SUBSCRIBE_START данные не шлются.
- JPEG-режим видео не поддерживается, шлём только H.264.
