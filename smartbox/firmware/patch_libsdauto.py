#!/usr/bin/env python3
"""Патч стоковой libSdAuto.so (AK3918) → прозрачная труба вместо резки CarLife.

Бокс в AA-режиме гоняет USB(ГУ)↔TCP(телефон) двумя насосами, оба рефреймят поток по
4-байтному AA-заголовку (readn(4) → длина BE16 → readn(тело) → writen(заг+тело)). CarLife
(внешний заголовок [chan u32][len u32]) это рассинхронизирует → ГУ не получает ответ.

Патч делает оба насоса прозрачными: read(до 32 КБ) → writen(ровно столько), без разбора.
Остальное (рукопожатие wifi_discovery/RFCOMM, подъём гаджета, выбор режима) не трогаем.

Переживание пересборки USB-гаджета (бокс сам передёргивает USB при подключении телефона, фейковый
AA-клиент бокса дерётся за /dev/usb_accessory): насос USB→телефон при ошибке чтения ставит fd=-1, закрывает,
ждёт 0,2 с и открывает /dev/usb_accessory заново (в лог «/dev/usb_accessory:9984 fd=N»), сеанс не рвёт;
реверс при сбое записи в USB теряет кусок, но не выходит. Остановку (флаг + join + close h[28]) делает
dongle_stop — переоткрытый fd лежит в h[28], утечки нет. Код в мёртвых участках старого разбора заголовков.

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
    # --- насос USB→телефон: ошибка чтения USB = переоткрыть аксессуар, не выходить ---
    (0x7030, 0xe59f1170, 0xea000007, "b 0x7054 (ошибка чтения USB: переоткрыть, не выходить)"),
    (0x7054, 0xe1834404, 0xe595001c, "ldr r0,[r5,#28] (старый fd)"),
    (0x7058, 0xe5d63001, 0xe3e01000, "mvn r1,#0"),
    (0x705c, 0xe1a04804, 0xe585101c, "str r1,[r5,#28] (fd=-1: реверс не пишет в мёртвый fd)"),
    (0x7060, 0xe2033003, 0xebfff300, "bl close"),
    (0x7064, 0xe3530001, 0xe3a00803, "mov r0,#0x30000 (~0,2 с)"),
    (0x7068, 0xe59f3144, 0xebfff277, "bl usleep"),
    (0x706c, 0xe1a04824, 0xe59f002c, "ldr r0,[pc,#0x2c] (лит 0x70a0)"),
    (0x7070, 0x02844004, 0xe08f0000, "add r0,pc,r0 (/dev/usb_accessory)"),
    (0x7074, 0xe1540003, 0xe3a01002, "mov r1,#2 (O_RDWR)"),
    (0x7078, 0x9a000009, 0xebfff26a, "bl open"),
    (0x707c, 0xe59f1134, 0xe585001c, "str r0,[r5,#28] (новый fd или -1)"),
    (0x7080, 0xe59f0134, 0xe1a03000, "mov r3,r0"),
    (0x7084, 0xe08f1001, 0xe59f0018, "ldr r0,[pc,#0x18] (лит 0x70a4)"),
    (0x7088, 0xe58d3000, 0xe08f0000, "add r0,pc,r0 (%s:%d fd=%d)"),
    (0x708c, 0xe59f212c, 0xe59f1014, "ldr r1,[pc,#0x14] (лит 0x70a8)"),
    (0x7090, 0xe1a03004, 0xe08f1001, "add r1,pc,r1 (/dev/usb_accessory)"),
    (0x7094, 0xe281108f, 0xe3a02c27, "mov r2,#9984 (метка переоткрытия в логе)"),
    (0x7098, 0xe08f0000, 0xebfff349, "bl printf"),
    (0x709c, 0xebfff348, 0xeaffffd8, "b 0x7004 (обратно в цикл насоса)"),
    (0x70a0, 0xea000026, 0x00007c7f, "лит: путь для open"),
    (0x70a4, 0xe1a02004, 0x000077d0, "лит: формат"),
    (0x70a8, 0xe2861004, 0x00007c5f, "лит: путь для printf"),
    # --- реверс: сбой записи в USB (гаджет пересобирается) не рвёт сеанс ---
    (0x5cb4, 0xe1dd40b6, 0xe92d4010, "push {r4,lr} (обёртка writen)"),
    (0x5cb8, 0xe595202c, 0xebfff835, "bl writen"),
    (0x5cbc, 0xe1a03424, 0xe3500000, "cmp r0,#0"),
    (0x5cc0, 0xe1834404, 0xb3a00000, "movlt r0,#0 (сбой записи в USB не рвёт сеанс)"),
    (0x5cc4, 0xe5dd3005, 0xe8bd8010, "pop {r4,pc}"),
    (0x5d5c, 0xebfff80c, 0xebffffd4, "bl обёртка (вместо writen)"),
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
