#!/system/bin/sh
# Свисток Headswap: кладётся в /system/etc/install-recovery.sh (init запускает его root-ом при загрузке,
# сервис flash_recovery). Даёт приложению прямой доступ к USB-аксессуару и сразу включает режим
# аксессуара: стик видится хосту как 18D1:2D01, ГУ не нужно рукопожатие AOA.
chmod 666 /dev/usb_accessory
# приложение «передёргивает» USB при обрыве связи с телефоном, чтобы ГУ начало сессию заново
chmod 666 /sys/class/android_usb/android0/enable
setprop persist.sys.usb.config accessory,adb
setprop sys.usb.config accessory,adb
# OTG Rockchip: 0 авто, 1 хост, 2 устройство (галка «Connect to PC»). После загрузки бывает 1 — держим 2.
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    echo 2 > /sys/bus/platform/drivers/usb20_otg/force_usb_mode
    sleep 5
done
