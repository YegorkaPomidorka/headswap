#!/usr/bin/env python3
"""ПК в роли USB-хоста ГУ для стенда свистка.

Цепочка: carlife_hu.py (ГУ, TCP :7240) <-> aoa_host.py (USB-хост) <-> стик (AOA, приложение dongle)
<-> Wi-Fi <-> Headswap на телефоне (режим «Свисток», :7250).

Скрипт делает с устройством то же, что ГУ CarLife: AOA-рукопожатие со строками Baidu/CarLife, ждёт
переподключения в режиме аксессуара (18D1:2D00/2D01), подключается к carlife_hu.py как «телефон»
(как adb reverse в обычном стенде) и гоняет байты в обе стороны без разбора.

    python carlife_hu.py --duration 600          # в одном окне
    python aoa_host.py                           # в другом

Windows: нужен WinUSB на интерфейсе аксессуара (Zadig, устройство 18D1:2D01 Interface 0)
и libusb (pip install pyusb libusb-package).
"""
import argparse
import socket
import sys
import threading
import time

import usb.core
import usb.util

try:
    import libusb_package
    BACKEND = libusb_package.get_libusb1_backend()
except ImportError:  # Linux/macOS: системный libusb
    BACKEND = None

GOOGLE_VID = 0x18D1
AOA_PIDS = (0x2D00, 0x2D01)
REQ_GET_PROTOCOL, REQ_SEND_STRING, REQ_START = 51, 52, 53
STRINGS = ("Baidu", "CarLife", "Headswap bench HU", "1.0.0", "https://yegorkapomidorka.com/headswap", "0000000012345678")
CHUNK = 16384


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def find_accessory():
    for pid in AOA_PIDS:
        dev = usb.core.find(idVendor=GOOGLE_VID, idProduct=pid, backend=BACKEND)
        if dev is not None:
            return dev
    return None


def switch_to_accessory(vid, pid):
    dev = usb.core.find(idVendor=vid, idProduct=pid, backend=BACKEND)
    if dev is None:
        sys.exit(f"нет устройства {vid:04x}:{pid:04x}")
    proto = dev.ctrl_transfer(0xC0, REQ_GET_PROTOCOL, 0, 0, 2)
    ver = proto[0] | (proto[1] << 8)
    log(f"AOA протокол {ver}")
    if ver < 1:
        sys.exit("устройство не умеет AOA")
    for idx, s in enumerate(STRINGS):
        dev.ctrl_transfer(0x40, REQ_SEND_STRING, 0, idx, s.encode() + b"\0")
    dev.ctrl_transfer(0x40, REQ_START, 0, 0, None)
    log("AOA start, жду переподключения")
    usb.util.dispose_resources(dev)


def endpoints(dev):
    try:
        dev.set_configuration()
    except usb.core.USBError:
        pass  # на Windows/WinUSB конфигурация уже выбрана
    intf = dev.get_active_configuration()[(0, 0)]
    usb.util.claim_interface(dev, intf)
    ep_in = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)
    ep_out = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
    return ep_in, ep_out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vid", type=lambda x: int(x, 16), default=0x2207, help="VID устройства до AOA (стик Rockchip 2207)")
    p.add_argument("--pid", type=lambda x: int(x, 16), default=0x0010, help="PID устройства до AOA")
    p.add_argument("--hu", default="127.0.0.1:7240", help="адрес carlife_hu.py (stream)")
    a = p.parse_args()

    dev = find_accessory()
    if dev is None:
        switch_to_accessory(a.vid, a.pid)
        for _ in range(50):
            time.sleep(0.2)
            dev = find_accessory()
            if dev is not None:
                break
        if dev is None:
            sys.exit("устройство не вернулось в режиме аксессуара (на Windows — драйвер WinUSB через Zadig?)")
    log(f"аксессуар {dev.idVendor:04x}:{dev.idProduct:04x}")
    ep_in, ep_out = endpoints(dev)
    log(f"bulk in 0x{ep_in.bEndpointAddress:02x}, out 0x{ep_out.bEndpointAddress:02x}")

    host, port = a.hu.rsplit(":", 1)
    sock = socket.create_connection((host, int(port)))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    log(f"ГУ {a.hu} подключено")
    stop = threading.Event()
    stats = {"up": 0, "down": 0}

    def usb_to_hu():  # телефон (через стик) -> ГУ
        while not stop.is_set():
            try:
                data = ep_in.read(CHUNK, timeout=1000)
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as e:
                log(f"USB чтение: {e}")
                break
            if data:
                sock.sendall(bytes(data))
                stats["down"] += len(data)
        stop.set()

    def hu_to_usb():  # ГУ -> стик
        while not stop.is_set():
            data = sock.recv(CHUNK)
            if not data:
                log("ГУ закрыло сокет")
                break
            try:
                ep_out.write(data, timeout=5000)
            except usb.core.USBError as e:
                log(f"USB запись: {e}")
                break
            stats["up"] += len(data)
        stop.set()

    threads = [threading.Thread(target=f, daemon=True) for f in (usb_to_hu, hu_to_usb)]
    for t in threads:
        t.start()
    t0 = time.time()
    try:
        while not stop.wait(5):
            log(f"{time.time() - t0:5.0f} с: ГУ→стик {stats['up']} Б, стик→ГУ {stats['down']} Б")
    except KeyboardInterrupt:
        stop.set()
    sock.close()
    usb.util.dispose_resources(dev)
    log(f"итого: ГУ→стик {stats['up']} Б, стик→ГУ {stats['down']} Б")


if __name__ == "__main__":
    main()
