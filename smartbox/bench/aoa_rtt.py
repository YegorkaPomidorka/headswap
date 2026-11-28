#!/usr/bin/env python3
"""Зонд «как Vezel»: AOA к боксу, шлёт HU_PROTOCOL_VERSION 2.0 (байты Vezel, resv 0x30B4) каждые N с,
ничего не отвечает, меряет время от записи в USB до первого байта ответа телефона (через бокс).
Vezel принимает ответ только в окне ~100 мс."""
import sys, time, usb.core, usb.util
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aoa_box2 import find_accessory, try_switch, open_accessory, log  # noqa: E402

FRAME = bytes.fromhex("000000010000000c" "000430b4" "00018001" "08021000")
PERIOD = float(sys.argv[1]) if len(sys.argv) > 1 else 3.2


def connect():
    while True:
        dev = find_accessory()
        if dev is None:
            time.sleep(1.0 if try_switch(0x18D1, 0x4EE1) else 0.3)
            continue
        try:
            ep_in, ep_out = open_accessory(dev)
            log("аксессуар 18d1:%04x" % dev.idProduct)
            break
        except Exception as e:
            log(f"не открыть: {e}")
            time.sleep(0.5)
    return ep_in, ep_out


def main():
    ep_in, ep_out = connect()
    log("аксессуар открыт; жду телефон (Режим бокса), шлю PROTOCOL_VERSION каждые %.1f с" % PERIOD)
    n = 0
    while True:
        n += 1
        try:
            ep_out.write(FRAME[:8], timeout=1000); ep_out.write(FRAME[8:], timeout=1000)  # как Vezel: 2 пакета
        except usb.core.USBTimeoutError:
            log(f"#{n}: бокс не читает USB (телефона нет)")
            time.sleep(PERIOD)
            continue
        except usb.core.USBError as e:
            log(f"#{n}: USB запись: {e} — переподключаюсь")
            ep_in, ep_out = connect()
            continue
        t0 = time.perf_counter()
        got, first, sizes = 0, None, []  # sizes: границы USB-передач бокса (Vezel читает строго 8, потом тело)
        while time.perf_counter() - t0 < max(PERIOD - 0.02, 0.2):
            try:
                data = ep_in.read(16384, timeout=50)
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as e:
                log(f"#{n}: USB чтение: {e} — переподключаюсь")
                ep_in, ep_out = connect()
                break
            if data:
                if first is None:
                    first = (time.perf_counter() - t0) * 1000
                got += len(data)
                sizes.append(len(data))
        if first is None:
            log(f"#{n}: ответа нет")
        else:
            log(f"#{n}: первый байт ответа через {first:6.1f} мс ({'ОК' if first <= 100 else 'ПОЗДНО'}), всего {got} Б, передачи {sizes}")


if __name__ == "__main__":
    main()
