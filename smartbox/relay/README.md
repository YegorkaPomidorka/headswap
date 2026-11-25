# Реле бокса smartBox (AK3918)

Своя труба для прошивки дешёвого бокса smartBox (Anyka AK3918AN, Linux 4.4) — замена редкому UG007.
Повторяет на C режим `runDirectLoop` из [`RelayService.java`](../../android/dongle/src/main/java/com/yegorkapomidorka/headswap/dongle/RelayService.java).

Бокс стоит AOA-аксессуаром перед ГУ и Wi-Fi-клиентом точки телефона. Реле гоняет байты
`/dev/usb_accessory ↔ phone:7250`:

- **ГУ → телефон:** сырые байты, как пришли.
- **телефон → ГУ:** кадрами `[chan u32][len u32] + тело`; заголовок отдельным `write`, тело кусками
  ≤ 16384 — часть ГУ чувствительна к границам USB-передач, а TCP их стирает (ADR 0009).

Приёмник на телефоне готов и не меняется: Headswap слушает `:7250` в режиме «Ждать свисток»
(`BridgeService`, `DongleListener`, `TransportCaps.USB_TCP`).

Поднятие USB-гаджета и Wi-Fi-клиента — дело прошивки бокса (`start_accessory.sh`, `wpa_ctrl`).
Реле — только труба: один fd на `/dev/usb_accessory` (драйвер пускает одно открытие, `open_excl`).

## Сборка

```sh
make arm      # relay-arm: статика, ARM EABI5 soft-float — на бокс
make native   # relay:     локальная сборка для теста (нужен gcc хоста)
```

ARM-бинарь: `ELF 32-bit LSB, ARM, EABI5, statically linked, soft-float ABI`.

## Запуск

```sh
relay [phone_host] [accessory_path]
```

По умолчанию `phone_host` = шлюз из `/proc/net/route` (точка телефона), `accessory_path` = `/dev/usb_accessory`.

## Тест без железа

`test_relay.py` подменяет `/dev/usb_accessory` на raw-pty, телефон — локальным TCP-сервером,
и проверяет обе стороны трубы побайтово (вниз — рефрейминг кадра > 16 КБ, вверх — сырьё).

```sh
# ARM-бинарь под qemu (как на боксе):
Q=$(find ~/qemu-local -name qemu-arm-static)      # qemu-user-static
RELAY_QEMU="$Q" python3 test_relay.py ./relay-arm

# или локальная сборка:
python3 test_relay.py ./relay
```

Проверено 25.11.2026 под qemu-arm 8.2: обе стороны прошли (кадр 17169 Б и 5000 Б сырья побайтово).
Границы USB-передач pty не сохраняет — это проверяется только на живом боксе.

## Что не сделано (ждёт железо/дамп)

- Поведение при обрыве телефона: `pump_up` может висеть на `read(/dev/usb_accessory)`, пока ГУ молчит;
  на живой ГУ она шлёт пульс/тач, так что чтение вернётся и сессия перезапустится. На боксе проверить.
- Нет сиквенса «телефон до включения гаджета» (на UG007 это sysfs `android_usb/enable`): на AK3918
  гаджет поднимает прошивка, путь неизвестен до полного дампа SPI. Проверить порог ответа ГУ на боксе.
