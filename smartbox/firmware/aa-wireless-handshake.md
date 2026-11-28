# BT-рукопожатие бокса (AA-wireless) — расшифровка живого захвата

Чтобы бокс ушёл в AA-режим (поднял USB-аксессуар и TCP-сервер :6733), телефон должен сыграть роль
беспроводного Android Auto по Bluetooth. Захвачено 27.11 с живого бокса (Xiaomi, mac 70:21:7F:4D:37:DB),
лог `sdDongle_4.log` пишет сырые RFCOMM-байты обеих сторон.

## Транспорт
- **Bluetooth RFCOMM**, бокс — сервер. UUID стандартного AA-wireless: `4de17a00-52cb-11e6-bdf4-0800200c9a66`
  (в btsnoop не виден — SDP до снупа; но Google AA коннектится, значит бокс его объявляет).
- Телефон — RFCOMM-**клиент** (`createRfcommSocketToServiceRecord`).

## Фрейминг
`[uint16 len BE][uint16 type BE][protobuf payload]`, где len = длина payload.

## Типы (номера — этого бокса, не Google) и поля
| type | кто шлёт | смысл | payload (protobuf) |
|------|----------|-------|--------------------|
| 4 | бокс→тел | VersionRequest | `18 00 20 <freq varint>` (f3=0, f4=5180 для ch36) |
| 5 | тел→бокс | StartRequest | `08 00 10 00 1A 08 <id 8 байт> 20 00` (f3=id-строка) |
| 1 | бокс→тел | StartResponse | `0A <len><ip> 10 <port varint> 18 01` → ip `192.168.1.101`, port `6733`=0x34cd varint `cd 34` |
| 2 | тел→бокс | InfoRequest | пусто (len 0) |
| 3 | бокс→тел | InfoResponse | `0A<ssid> 12<psk> 1A<bssid> 20 <sec>` → ssid `WirelessDongle-54E2`, psk `12345678`, bssid `cc:c4:b2:a9:80:54`, sec 8 |
| 7 | тел→бокс | статус | `18 00` |
| 6 | тел→бокс | статус/connect | `08 00` |

## Порядок (из захвата)
1. BT connect (бокс: `btCarlinkConnectNotify auto start`, `start_auto_wrapper`, `dongle_start`).
2. бокс→тел **VersionRequest** (type4).
3. тел→бокс **StartRequest** (type5), id напр. ASCII `852f8b92` (похоже случайный токен — телефон генерит).
4. бокс→тел **StartResponse** (type1): ip+port 6733.
5. тел→бокс **InfoRequest** (type2, пусто).
6. бокс→тел **InfoResponse** (type3): ssid/psk/bssid/sec.
7. тел→бокс **статус** (type7 `1800`, type6 `0800`).
8. Телефон коннектится к Wi-Fi бокса (WirelessDongle-54E2/12345678, ch36 5 ГГц), TCP на 192.168.1.101:6733.
9. Бокс: `send_command AT#P0`, `receive usb connect msg` — USB-аксессуар поднят, AA-сессия пошла по :6733.
   Там наши прозрачные насосы гонят байты как есть → Headswap говорит CarLife.

## Для режима бокса в Headswap
- ssid/psk/ip/port статичны на этом боксе — можно и прочитать из InfoResponse/StartResponse, и знать заранее.
- Рукопожатие НУЖНО (без него бокс не откроет :6733): проиграть type5, type2, статусы, реагируя на бокс.
- Данные после коннекта — тот же `StreamTransport(USB_TCP)`, что у свистка (бокс прозрачен нашим патчем).

## Открытые вопросы
- Нужен ли боксу явный VersionResponse на его type4 (в захвате отдельного типа от телефона не видно — возможно
  type7/6 закрывают; у бокса 20-с watchdog `mWlAutoWatchTimer`). Проверить на реальном коннекте.
- id в StartRequest (`852f8b92`): любой или привязан к MAC телефона. Начать со случайного 8-символьного.
