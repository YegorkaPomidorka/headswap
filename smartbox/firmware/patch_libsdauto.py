#!/usr/bin/env python3
"""Патч стоковой libSdAuto.so (AK3918) → прозрачная труба вместо резки CarLife.

Бокс в AA-режиме гоняет USB(ГУ)↔TCP(телефон) двумя насосами, оба рефреймят поток по
4-байтному AA-заголовку (readn(4) → длина BE16 → readn(тело) → writen(заг+тело)). CarLife
(внешний заголовок [chan u32][len u32]) это рассинхронизирует → ГУ не получает ответ.

Патч: USB→телефон прозрачно (read(до 32 КБ) → writen(ровно столько)); телефон→USB — по сообщениям CarLife
(заголовок 8 Б отдельной записью, тело кусками ≤16384): границы USB-передач для ГУ значимы (ADR 0009).
Остальное (рукопожатие wifi_discovery/RFCOMM, подъём гаджета, выбор режима) не трогаем.

Переживание пересборки USB-гаджета (бокс сам передёргивает USB при подключении телефона, фейковый
AA-клиент бокса дерётся за /dev/usb_accessory): насос USB→телефон при ошибке чтения ставит fd=-1, закрывает,
ждёт 0,2 с и открывает /dev/usb_accessory заново (в лог «/dev/usb_accessory:9984 fd=N»), сеанс не рвёт;
реверс при сбое записи в USB теряет сообщение, но не выходит. Остановку (флаг + join + close h[28]) делает
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
    # --- pump phone→USB: по одному сообщению CarLife за вызов. Заголовок 8 Б отдельной записью в USB,
    #     тело кусками <= 16384 (ADR 0009, как RelayService.pumpDown): Vezel читает строго заголовок, потом тело.
    #     Сырой read(32K)->writen(n) склеивал сообщения телефона в одну USB-передачу (зонд aoa_rtt.py: [416]).
    #     Сбой записи в USB не рвёт сеанс (результат writen не смотрим), fd=-1 — не пишем.
    #     shortcut: длина тела не проверяется на потолок, при рассинхроне потока насос зависнет на чтении до
    #     обрыва сокета; проверку добавить, если в бокс пойдёт не только Headswap. ARMv5: без rev. ---
    (0x5c88, 0xe3a02004, 0xe3a02008, "mov r2,#8 (заголовок CarLife [chan u32][len u32] в стек)"),
    (0x5c98, 0xe3500004, 0xe3500008, "cmp r0,#8 (дальше стоковые beq 0x5cb4 / путь ошибки)"),
    (0x5cb4, 0xe1dd40b6, 0xe595001c, "ldr r0,[r5,#28] (fd USB)"),
    (0x5cb8, 0xe595202c, 0xe3700001, "cmn r0,#1"),
    (0x5cbc, 0xe1a03424, 0x11a01007, "movne r1,r7"),
    (0x5cc0, 0xe1834404, 0x13a02008, "movne r2,#8"),
    (0x5cc4, 0xe5dd3005, 0x1bfff832, "blne writen (заголовок отдельной передачей)"),
    (0x5cc8, 0xe1a04804, 0xe5d74004, "ldrb r4,[r7,#4] (len, big-endian)"),
    (0x5ccc, 0xe2033003, 0xe5d73005, "ldrb r3,[r7,#5]"),
    (0x5cd0, 0xe1a04824, 0xe1834404, "orr r4,r3,r4,lsl #8"),
    (0x5cd4, 0xe3530001, 0xe5d73006, "ldrb r3,[r7,#6]"),
    (0x5cd8, 0x02844004, 0xe1834404, "orr r4,r3,r4,lsl #8"),
    (0x5cdc, 0xe2846004, 0xe5d73007, "ldrb r3,[r7,#7]"),
    (0x5ce0, 0xe1560002, 0xe1834404, "orr r4,r3,r4,lsl #8 (r4 = осталось тела)"),
    (0x5ce4, 0x859f0080, 0xe3540000, "цикл: cmp r4,#0"),
    (0x5ce8, 0x81a01004, 0xda00000f, "ble 0x5d2c (тело кончилось)"),
    (0x5cec, 0x808f0000, 0xe3540901, "cmp r4,#0x4000"),
    (0x5cf0, 0x8a00000e, 0xb1a06004, "movlt r6,r4"),
    (0x5cf4, 0xe3a02004, 0xa3a06901, "movge r6,#0x4000 (r6 = кусок)"),
    (0x5cf8, 0xe1a01007, 0xe595000c, "ldr r0,[r5,#12] (fd телефона)"),
    (0x5cfc, 0xe5950028, 0xe5951028, "ldr r1,[r5,#40] (буфер)"),
    (0x5d00, 0xebfff754, 0xe1a02006, "mov r2,r6"),
    (0x5d04, 0xe5951028, 0xebfff795, "bl readn"),
    (0x5d08, 0xe1a02004, 0xe1500006, "cmp r0,r6"),
    (0x5d0c, 0xe2811004, 0x1a000008, "bne 0x5d34 (обрыв телефона: стоковый выход -1)"),
    (0x5d10, 0xe595000c, 0xe595001c, "ldr r0,[r5,#28] (fd USB)"),
    (0x5d14, 0xebfff791, 0xe3700001, "cmn r0,#1"),
    (0x5d18, 0xe1540000, 0x15951028, "ldrne r1,[r5,#40]"),
    (0x5d1c, 0xe1a02000, 0x11a02006, "movne r2,r6"),
    (0x5d20, 0x0a000007, 0x1bfff81b, "blne writen (кусок тела отдельной передачей)"),
    (0x5d24, 0xe59f0044, 0xe0444006, "sub r4,r4,r6"),
    (0x5d28, 0xe1a01004, 0xeaffffed, "b 0x5ce4 (цикл)"),
    (0x5d2c, 0xe08f0000, 0xe3a00000, "mov r0,#0 (сообщение ушло)"),
    (0x5d30, 0xebfff823, 0xea00000a, "b 0x5d60 (эпилог)"),
    # --- сокет телефона: TCP_NODELAY вместо TCP_KEEPCNT (прозрачный насос пишет кусками;
    #     Vezel шлёт заголовок 8 Б и тело 12 Б отдельно — Nagle держал тело до ACK, 40-200 мс) ---
    (0x6374, 0xe3a02006, 0xe3a02001, "mov r2,#1 (optname TCP_NODELAY вместо TCP_KEEPCNT)"),
    (0x6380, 0xe1a01002, 0xe3a01006, "mov r1,#6 (level IPPROTO_TCP; значение 3 != 0 -> вкл)"),
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
