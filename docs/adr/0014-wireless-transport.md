# 0014. Беспроводной CarLife: CarTransport, Wi-Fi Direct и маяк сети машины

- Статус: принято
- Дата: 2026-10-15 (2.0.0), уточнения до 2026-10-30
- Источники: PR #24, #30, #32, #38, #48, #59, #68, #72, #117, `docs/wireless-carlife.md`, `link/CarTransport.kt:7-26`, `link/bt/P2pJoin.kt`, `link/wifi/HotspotBeacon.kt`, `link/wifi/HotspotDetect.kt`

## Контекст

CarLife по воздуху устроен по-разному. У Mazda G6S — Bluetooth RFCOMM плюс Wi-Fi Direct. У VW BT 1420 — сеть самой
машины, телефон подключается к ней и шлёт UDP-маяк (порт 7999). У Honda Vezel 2024 беспровода нет (`docs/wireless-carlife.md`).
Протокол (`CarLifeSession`) не должен знать, как каналы лежат на проводе.

## Решение

- `CarTransport` с `TransportCaps`: `usb-aoa`, `tcp-debug` (один поток с внешним заголовком), `tcp6` (6 сокетов Baidu без
  внешнего заголовка). Разбор вариантов A и B взят из LifeAuto Lite 0.1.6 (PR #30).
- Ветка A (Mazda): телефон — RFCOMM-сервер `carlife`, в P2P-группу ГУ входит клиентом (`P2pJoin.kt`); `MD_WIFI_IP` отвечает на
  `IP_REQUEST` (`BtFrames.kt:16-17`). При распаде группы и живом BT идёт rejoin (PR #48, #68, общая логика).
- VW BT 1420: сеть машины и UDP-маяк (`HotspotBeacon.kt`).
- Хотспот мост не поднимает; `HotspotDetect` (PR #117) только подсказывает, включён ли он.
- `targetSdk = 37` и `ACCESS_LOCAL_NETWORK` (`build.gradle.kts:20`, `AndroidManifest.xml:26`): без разрешения входящий TCP
  из LAN молча режется, loopback (AA 5277) разрешения не требует.

## Альтернативы

- LocalOnlyHotspot для лечения молчащего 5277: Егор 24.10 решил чинить USB-сторону (0002).
- PR #38 (targetSdk 37) перенесён в #59.
- Для Mazda пауза 15 с на запрос «Wi-Fi машины» пропускается (PR #72). Почему именно так: ? (спросить Егора)

## Последствия

- Транспорт — отдельный интерфейс: новые `TransportCaps` добавляются, не расширяя `CarLifeSession`.
- Каждая смена `targetSdk` требует проверить права на локальную сеть, иначе беспровод молча перестанет работать.
- Обрывы по воздуху разбирать по лог-диагностике (0016): часть вызвана VPN и Wi-Fi хотспота на телефоне (h328, h307).
