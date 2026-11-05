package com.yegorkapomidorka.headswap.dex

import java.util.Locale

/**
 * Android 17: виртуальный дисплей с OWN_DISPLAY_GROUP система всё равно кладёт в группу телефона (0), и он засыпает
 * вместе с экраном телефона. Дисплей, созданный внутри VirtualDevice (привязка CDM с профилем APP_STREAMING), получает
 * группу дисплеев и питания своего устройства. ADR 0029. Здесь только чистая логика, вызовы платформы - в [DesktopVirtualDevice].
 */
internal object VirtualDevicePolicy {
    /** Android 17. На 16 и ниже OWN_DISPLAY_GROUP работает (Xiaomi Егора), путь не трогаем. */
    const val MIN_SDK = 37
    /** Ручное переключение для проверки: `adb shell setprop debug.headswap.vdev 1|0`, пусто - по версии Android. */
    const val OVERRIDE_PROPERTY = "debug.headswap.vdev"
    const val CREATE_VIRTUAL_DEVICE = "android.permission.CREATE_VIRTUAL_DEVICE"
    const val PROFILE_APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    /** VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED. */
    const val LOCK_STATE_ALWAYS_UNLOCKED = 1
    const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
    const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2

    fun enabled(sdkInt: Int, override: String?): Boolean = when (override?.trim()) {
        "1", "true", "on" -> sdkInt >= 34 // VirtualDevice.createVirtualDisplay(VirtualDisplayConfig, ...) - Android 14+
        "0", "false", "off" -> false
        else -> sdkInt >= MIN_SDK
    }

    /** Фиктивный, но постоянный MAC: та же привязка находится при следующей активации, дубликатов нет. */
    fun fakeMac(packageName: String, userId: Int): String {
        val hash = 31 * packageName.hashCode() + userId
        return String.format(Locale.ROOT, "02:48:53:%02X:%02X:%02X", hash ushr 16 and 0xff, hash ushr 8 and 0xff, hash and 0xff)
    }

    /** `cmd companiondevice associate USER PACKAGE MAC [PROFILE] [SELF_MANAGED]`; профиль с 15, self-managed с 16. */
    fun associateCommand(userId: Int, packageName: String, mac: String, sdkInt: Int): String = buildString {
        append("cmd companiondevice associate $userId $packageName $mac")
        if (sdkInt >= 35) append(" $PROFILE_APP_STREAMING")
        if (sdkInt >= 36) append(" false")
    }

    fun disassociateCommand(userId: Int, packageName: String, mac: String): String =
        "cmd companiondevice disassociate $userId $packageName $mac"

    /** Привязку пересоздаём, если у неё не тот профиль (на 14 профиль не задаётся и не проверяется). */
    fun associationUsable(profile: String?, sdkInt: Int): Boolean = sdkInt < 35 || profile == PROFILE_APP_STREAMING

    /**
     * Флаги дисплея внутри VirtualDevice: без OWN_DISPLAY_GROUP (иначе своя группа не нужна устройству и на 17 дисплей
     * опять уходит в группу 0) и без ALWAYS_UNLOCKED (его даёт lock state устройства).
     */
    fun deviceDisplayFlags(flags: Int): Int =
        flags and (DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED).inv()

    /** Вторая попытка, если устройство не приняло публичный дисплей: так создают дисплей MAA-Meow и MoonClicker. */
    fun deviceDisplayFallbackFlags(flags: Int): Int =
        deviceDisplayFlags(flags) and (VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_PRESENTATION).inv()

    /** Своя группа = id > 0 (0 - группа экрана телефона, -1 - нет группы). */
    fun ownGroup(displayGroupId: Int?): Boolean = displayGroupId != null && displayGroupId > 0
}
