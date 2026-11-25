#!/usr/bin/env python3
"""Smoke-тест реле бокса без железа.

Аксессуар /dev/usb_accessory подменяется raw-pty (двунаправленный символьный fd),
телефон — локальным TCP-сервером на PHONE_PORT. Проверяем обе стороны трубы:
  вниз (телефон→ГУ): сервер шлёт кадр [chan][len]+тело → читаем из pty ровно head+тело;
  вверх (ГУ→телефон): пишем сырьё в pty → сервер получает те же байты.

Запуск:  python3 test_relay.py ./relay          (бинарь хоста, make native)
         RELAY_QEMU="qemu-arm" python3 test_relay.py ./relay-arm   (ARM под qemu)

Границы USB-передач pty не сохраняет — проверяется только побайтовая целостность и рефрейминг.
"""
import os, pty, tty, socket, struct, subprocess, sys, threading, time

PHONE_PORT = 7250
CHUNK = 16384


def recvn(sock, n):
    buf = b""
    while len(buf) < n:
        b = sock.recv(n - len(buf))
        if not b:
            raise EOFError(f"сокет закрыт на {len(buf)}/{n}")
        buf += b
    return buf


def read_fd(fd, n):
    buf = b""
    while len(buf) < n:
        b = os.read(fd, n - len(buf))
        if not b:
            raise EOFError(f"pty закрыт на {len(buf)}/{n}")
        buf += b
    return buf


def main():
    relay_bin = sys.argv[1] if len(sys.argv) > 1 else "./relay"
    runner = os.environ.get("RELAY_QEMU", "").split() if os.environ.get("RELAY_QEMU") else []

    # телефон
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PHONE_PORT))
    srv.listen(1)

    # аксессуар = raw-pty
    master, slave = pty.openpty()
    tty.setraw(master)
    tty.setraw(slave)
    slave_path = os.ttyname(slave)

    proc = subprocess.Popen(runner + [relay_bin, "127.0.0.1", slave_path])
    os.close(slave)  # slave держит реле

    srv.settimeout(10)
    conn, _ = srv.accept()
    conn.settimeout(10)

    ok = True

    # --- вниз: кадр телефон → ГУ ---
    chan = 7
    body = bytes((i * 37 + 11) & 0xFF for i in range(CHUNK + 777))  # > CHUNK, с 0x00/0x0a/0x0d
    frame = struct.pack(">II", chan, len(body)) + body
    conn.sendall(frame)
    got = read_fd(master, len(frame))
    if got == frame:
        print(f"PASS вниз: кадр {len(frame)} Б прошёл побайтово")
    else:
        ok = False
        print(f"FAIL вниз: {len(got)} Б, совпадение={got == frame}")

    # --- вверх: сырьё ГУ → телефон ---
    raw = bytes((i * 13 + 5) & 0xFF for i in range(5000))
    os.write(master, raw)
    got = recvn(conn, len(raw))
    if got == raw:
        print(f"PASS вверх: {len(raw)} Б прошли побайтово")
    else:
        ok = False
        print(f"FAIL вверх: {len(got)} Б, совпадение={got == raw}")

    conn.close()
    srv.close()
    os.close(master)
    time.sleep(0.3)
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()

    print("ИТОГ:", "OK" if ok else "ПРОВАЛ")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
