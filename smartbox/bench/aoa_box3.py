#!/usr/bin/env python3
"""Диагностика бокса smartBox на стенде: ПК = USB-хост ГУ (AOA) <-> carlife_hu.py :7240.

- бокс до AOA = 18D1:4EE1, в аксессуаре = 18D1:2D00 (WinUSB через Zadig на оба);
- бокс сам передёргивает USB после AOA — мост заново находит аксессуар, сокет к ГУ держит,
  недописанный кусок ГУ->бокс дописывает после переподключения;
- запись ГУ->бокс ждёт, пока бокс начнёт читать (он читает USB только после подключения телефона).
Счётчики байт раз в 5 с.
"""
import argparse, socket, threading, time
import usb.core, usb.util
try:
    import libusb_package
    BACKEND = libusb_package.get_libusb1_backend()
except ImportError:
    BACKEND = None

GOOGLE_VID = 0x18D1
AOA_PIDS = (0x2D00, 0x2D01)
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


def try_switch(vid, pid):
    dev = usb.core.find(idVendor=vid, idProduct=pid, backend=BACKEND)
    if dev is None:
        return False
    try:
        proto = dev.ctrl_transfer(0xC0, 51, 0, 0, 2)
        log(f"AOA протокол {proto[0] | (proto[1] << 8)}, шлю строки и start")
        for idx, s in enumerate(STRINGS):
            dev.ctrl_transfer(0x40, 52, 0, idx, s.encode() + b"\0")
        dev.ctrl_transfer(0x40, 53, 0, 0, None)
    except usb.core.USBError as e:
        log(f"AOA: {e}")
    usb.util.dispose_resources(dev)
    return True


def open_accessory(dev):
    intf = dev.get_active_configuration()[(0, 0)]
    usb.util.claim_interface(dev, intf)
    pick = lambda d: usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == d)
    return pick(usb.util.ENDPOINT_IN), pick(usb.util.ENDPOINT_OUT)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--vid", type=lambda x: int(x, 16), default=0x18D1)
    p.add_argument("--pid", type=lambda x: int(x, 16), default=0x4EE1)
    p.add_argument("--hu", default="127.0.0.1:7240")
    a = p.parse_args()
    cur = {"dev": None, "in": None, "out": None, "gen": 0}
    lock = threading.Lock()
    stop = threading.Event()
    st = {"up": 0, "down": 0, "wait": 0, "reconn": 0}

    def drop(gen, why):
        with lock:
            if cur["gen"] == gen and cur["dev"] is not None:
                log(f"USB пропал ({why})")
                try:
                    usb.util.dispose_resources(cur["dev"])
                except Exception:
                    pass
                cur["dev"] = cur["in"] = cur["out"] = None

    def connect_usb():
        while not stop.is_set():
            dev = find_accessory()
            if dev is None:
                time.sleep(1.0 if try_switch(a.vid, a.pid) else 0.3)
                continue
            try:
                ep_in, ep_out = open_accessory(dev)
            except (usb.core.USBError, NotImplementedError) as e:
                log(f"аксессуар {dev.idProduct:04x} не открыть: {e}")
                time.sleep(0.5)
                continue
            with lock:
                cur["gen"] += 1
                cur["dev"], cur["in"], cur["out"] = dev, ep_in, ep_out
            log(f"аксессуар 18d1:{dev.idProduct:04x} in 0x{ep_in.bEndpointAddress:02x} out 0x{ep_out.bEndpointAddress:02x} (#{cur['gen']})")
            return

    connect_usb()
    host, port = a.hu.rsplit(":", 1)
    sock = socket.create_connection((host, int(port)))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    log(f"ГУ {a.hu} подключено")

    def usb_to_hu():
        while not stop.is_set():
            with lock:
                ep, gen = cur["in"], cur["gen"]
            if ep is None:
                time.sleep(0.1)
                continue
            try:
                data = ep.read(CHUNK, timeout=500)
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as e:
                drop(gen, f"чтение: {e}")
                continue
            if data:
                sock.sendall(bytes(data))
                st["down"] += len(data)

    first = {}

    def usb_write(data):
        while not stop.is_set():
            with lock:
                ep, gen = cur["out"], cur["gen"]
            if ep is None:
                time.sleep(0.1)
                continue
            try:
                with wlock:
                    ep.write(data, timeout=1000)
                return
            except usb.core.USBTimeoutError:
                st["wait"] += 1
            except usb.core.USBError as e:
                drop(gen, f"запись: {e}")

    def resend():  # как Vezel: повторять первое сообщение ГУ, пока от бокса ни байта
        n = 0
        while not stop.wait(3):
            if st["down"] > 0:
                log(f"бокс ответил — повторы HU_PROTOCOL_VERSION стоп (было {n})")
                return
            if "data" in first:
                usb_write(first["data"])
                n += 1

    def hu_to_usb():
        while not stop.is_set():
            data = sock.recv(CHUNK)
            if "data" not in first:
                first["data"] = data
                log(f"первое сообщение ГУ {len(data)} Б: {data[:24].hex()}")
            if not data:
                log("ГУ закрыло сокет")
                stop.set()
                return
            usb_write(data)
            st["up"] += len(data)

    wlock = threading.Lock()
    for f in (usb_to_hu, hu_to_usb, resend):
        threading.Thread(target=f, daemon=True).start()
    t0 = time.time()
    tick = 0
    while not stop.wait(1):
        tick += 1
        with lock:
            gone = cur["dev"] is None
        if gone:
            st["reconn"] += 1
            connect_usb()
        if tick % 5 == 0:
            log(f"{time.time() - t0:5.0f} с: ГУ->бокс {st['up']} Б, бокс->ГУ {st['down']} Б, ждали запись {st['wait']} с, переподкл. USB {st['reconn']}")
    log(f"итого: ГУ->бокс {st['up']} Б, бокс->ГУ {st['down']} Б")


if __name__ == "__main__":
    main()
