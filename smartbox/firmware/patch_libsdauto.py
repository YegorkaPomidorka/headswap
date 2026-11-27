#!/usr/bin/env python3
"""Патч стоковой libSdAuto.so (AK3918) → прозрачная труба вместо резки CarLife.

Бокс в AA-режиме гоняет USB(ГУ)↔TCP(телефон) двумя насосами, оба рефреймят поток по
4-байтному AA-заголовку (readn(4) → длина BE16 → readn(тело) → writen(заг+тело)). CarLife
(внешний заголовок [chan u32][len u32]) это рассинхронизирует → ГУ не получает ответ.

Патч делает оба насоса прозрачными: read(до 32 КБ) → writen(ровно столько), без разбора.
Остальное (рукопожатие wifi_discovery/RFCOMM, подъём гаджета, выбор режима) не трогаем.

Учёт чтений USB (0x70dc–0x7120) обязателен: сторож watchUsbAutoTimerProc каждые 2 с считает
простой и после 16 тиков без чтений делает exit(0) всего sdDongle (сеанс умирал через ~40 с).

  dongle_usb_data_proc @0x6fb0  — USB(ГУ) → телефон (бесконечный цикл)
  pump phone→USB       @0x5c7c  — телефон → USB(ГУ) (кадр за вызов из dongle_wifi_data_proc)

.text: Addr==Off (0x3e48) → файловое смещение == виртуальный адрес.
Каждая правка проверяет исходное слово; несовпадение → стоп (защита от чужой сборки).

Использование: python3 patch_libsdauto.py <stock libSdAuto.so> <out libSdAuto.so>
"""
import sys, struct

# (vaddr, old_word, new_word, комментарий) — little-endian ARM, по одному слову
PATCHES = [
    # --- dongle_usb_data_proc (USB→телефон): read(32K)->writen(n) ---
    (0x7018, 0xe3a02004, 0xe3a02902, "mov r2,#0x8000 (читать до 32К, не 4)"),
    (0x7024, 0xebfff2cd, 0xebfff2d6, "bl read@plt (вместо readn)"),
    (0x704c, 0xe1d640b2, 0xe1a04000, "mov r4,r0 (len=n)"),
    (0x7050, 0xe1a03424, 0xea000021, "b 0x70dc (учёт: usb_read_count++, простой=0, перевзвод таймера 2с → writen)"),
    (0x7130, 0xe2842004, 0xe1a02004, "mov r2,r4 (писать n, не len+4)"),
    # --- pump phone→USB: read(32K в буфер [r5+40])->writen(n) ---
    (0x5c88, 0xe3a02004, 0xe3a02902, "mov r2,#0x8000"),
    (0x5c8c, 0xe1a01007, 0xe5951028, "ldr r1,[r5,#40] (читать в большой буфер, не в стек)"),
    (0x5c94, 0xebfff7b1, 0xebfff7ba, "bl read@plt (вместо readn)"),
    (0x5c98, 0xe3500004, 0xe2504000, "subs r4,r0,#0 (r4=n, флаги)"),
    (0x5c9c, 0x0a000004, 0xca000028, "bgt 0x5d44 (n>0 → writen; иначе ошибка)"),
    (0x5d54, 0xe1a02006, 0xe1a02004, "mov r2,r4 (писать n, не len+4)"),
]

TEXT_ADDR, TEXT_OFF, TEXT_SIZE = 0x3e48, 0x3e48, 0x88c0


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
            sys.exit(f"СТОП {vaddr:#x}: ждали {old:#010x}, нашли {cur:#010x} — не та сборка libSdAuto")
        struct.pack_into("<I", data, o, new)
        print(f"  {vaddr:#06x}: {old:#010x} -> {new:#010x}  {note}")
    open(dst, "wb").write(data)
    print(f"готово: {dst} ({len(data)} Б, {len(PATCHES)} правок)")


if __name__ == "__main__":
    main()
