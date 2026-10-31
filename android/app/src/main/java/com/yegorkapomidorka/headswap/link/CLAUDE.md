# link/ — транспорт к ГУ

Карта всего приложения — `docs/ARCHITECTURE.md`, решения — `docs/adr/` (воздух — ADR 0014).

- `StreamTransport` — USB AOA и debug-TCP: один поток с внешним заголовком, запись телом ≤ 16 КиБ (`Wire.CHUNK`).
  Mobis CCNC пишет тело одним write (ADR 0009). EIO в логе — текст IOException отсюда.
- `MultiSocketTransport` — воздух: телефон слушает 6 портов, ГУ — клиент, на каждый канал свой сокет.
- Три пути в общую сеть с ГУ: `bt/` (RFCOMM `carlife` + вход в P2P-группу ГУ), `wifi/HotspotBeacon` (хотспот телефона,
  UDP 7999), `car/CarNetwork` («Wi-Fi машины»). Хотспот мост сам не поднимает.
- Кабель побеждает воздух (`BridgeService`). Логика выбора режима — чистая, в `car/CarLogic`, покрыта тестами.
- В `WLog` не писать пароли, SSID, ключи.
