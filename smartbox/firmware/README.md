# Прошивка бокса smartBox (AK3918) — прозрачная libSdAuto

Своя прошивка для дешёвого бокса (замена UG007). Бокс в AA-режиме режет CarLife: оба насоса
данных в `libSdAuto.so` рефреймят поток по 4-байтному AA-заголовку. Патч делает их прозрачными —
бокс становится трубой `/dev/usb_accessory ↔ TCP`, а CarLife кодирует Headswap.

Исходников бокса нет → правим стоковую `.so` бинарно (11 слов в `.text`, обе функции-насоса):
- `dongle_usb_data_proc @0x6fb0` — USB(ГУ) → телефон
- насос `@0x5c7c` (из `dongle_wifi_data_proc`) — телефон → USB(ГУ)

Каждая: `readn(4-байт заголовок) + разбор длины + readn(тело) + writen(заг+тело)`
→ `read(до 32 КБ) + writen(ровно столько)`. Рукопожатие, подъём гаджета, Wi-Fi — не трогаем.

## Сборка пакета

```sh
# стоковые .so лежат в C:\Users\pomidorka\Downloads\smartbox-fw\x\ (сборка ak_202610081659)
python3 patch_libsdauto.py x/libSdAuto.so out/libSdAuto.so
# + 4 стоковых файла в тот же каталог, затем GNU tar (файлы в корне, как сток .swu):
tar --format=gnu --owner=0 --group=0 --mode=0777 -cf hs-transparent.swu \
    libSdAuto.so libSdCommon.so libSdSampleRate.so libSdUtils.so sdDongle
```

`patch_libsdauto.py` проверяет исходные байты каждого слова → на чужой сборке libSdAuto стоп.

## Заливка на бокс

ПК на Wi-Fi бокса (`WirelessDongle-54E2`), панель `192.168.1.101`. Локальный upload в панели
закомментирован → скрипт `smartbox/relay/flash.sh` (протокол панели):
`downfile.cgi` (куски 10 КБ, последний `<name>:<md5>.2`) → `submition.cgi?filename=<name>`
(распаковывает `tar -xvf /tmp/<name> -C /usr/data/lib/`) → `reboot.cgi`.

```sh
bash flash.sh /path/hs-transparent.swu hs-transparent.swu <md5>
```

## Откат

`/usr/data/lib` — слой-переопределение поверх read-only rootfs; панель (lighttpd) от него не
зависит. Сломали AA → заливаем сток `ak_202610081659.swu` тем же `flash.sh`, или `factoryreset.cgi`
чистит `/usr/data`. **Прищепка/дамп SPI не нужны, пока жива панель.**

## Не проверено (нужна машина)

- Прозрачность данных: на столе ГУ нет, видно лишь чистый ли старт бокса.
- Топология: бокс — Wi-Fi AP (телефон коннектится к боксу :6733), а Headswap-приёмник
  (`DongleListener`) слушает :7250 и ждёт, что к нему придёт свисток. Кто к кому коннектится и
  не мешает ли AA-рукопожатие CarLife-ГУ — решать на Vezel. Патч насосов — необходимая база, но не
  гарантия end-to-end.
