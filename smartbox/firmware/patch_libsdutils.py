#!/usr/bin/env python3
"""Патч стоковой libSdUtils.so (AK3918) → USB-роль всегда peripheral (device/gadget).

В AA-режиме бокс после подъёма гаджета (switch peripheral) зачем-то зовёт
ak3918_switch_usb_mode(3) → ветка host: `echo > .../UDC` отвязывает гаджет, AOA-линк к
ГУ рвётся (read -1, errno=5 EIO), ГУ зацикливается на HU_PROTOCOL_VERSION. Это поведение
reverse-CarPlay, которое на CarPlay-способной ГУ (Honda Vezel) включается и для AA.

Правка: в ak3918_switch_usb_mode гасим переход в host. Одна инструкция:

  0x73fc  cmp r5, #0            ; r5 = mode (0=peripheral, !=0=host)
  0x7404  bne 0x74d4 (host)  -> NOP (mov r0,r0)   ; mode!=0 теперь тоже падает в peripheral

Любой вызов (в т.ч. mode=3) делает ровно device-setup: rmmod ak_hcd; insmod ak_udc
reset_phy=1; echo 20200000.usb > UDC (гаджет привязан). /dev/usb_accessory жив, насосы
libSdAuto несут CarLife. CarPlay-reverse (нужен host) отключается — нам не нужен, AA-only.

.text: Addr==Off (0x4d28) → файловое смещение == виртуальный адрес.
Правка проверяет исходное слово; несовпадение → стоп (защита от чужой сборки).

Использование: python3 patch_libsdutils.py <stock libSdUtils.so> <out libSdUtils.so>
"""
import sys, struct

# (vaddr, old_word, new_word, комментарий) — little-endian ARM
PATCHES = [
    (0x7404, 0x1a000032, 0xe1a00000, "bne host -> NOP (always peripheral/device)"),
]

TEXT_ADDR, TEXT_OFF, TEXT_SIZE = 0x4d28, 0x4d28, 0x16c1c


def off(vaddr):
    assert TEXT_ADDR <= vaddr < TEXT_ADDR + TEXT_SIZE, f"{vaddr:#x} вне .text"
    return vaddr - TEXT_ADDR + TEXT_OFF


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = bytearray(open(src, "rb").read())
    for vaddr, old, new, note in PATCHES:
        o = off(vaddr)
        cur = struct.unpack_from("<I", data, o)[0]
        if cur != old:
            sys.exit(f"СТОП {vaddr:#x}: ждали {old:#010x}, нашли {cur:#010x} — не та сборка libSdUtils")
        struct.pack_into("<I", data, o, new)
        print(f"  {vaddr:#06x}: {old:#010x} -> {new:#010x}  {note}")
    open(dst, "wb").write(data)
    print(f"готово: {dst} ({len(data)} Б, {len(PATCHES)} правок)")


if __name__ == "__main__":
    main()
