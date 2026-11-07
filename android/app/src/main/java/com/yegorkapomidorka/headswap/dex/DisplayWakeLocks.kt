package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.os.PowerManager
import com.yegorkapomidorka.headswap.AppLog

/**
 * Дисплей в собственной DisplayGroup (OWN_DISPLAY_GROUP, нужен для ALWAYS_UNLOCKED) гаснет по своему таймауту экрана,
 * как у виртуального устройства. Держим его включённым wake lock'ом, привязанным к displayId (скрытый
 * PowerManager.newWakeLock(level, tag, displayId), работает в главном процессе под --no-hidden-api-checks).
 * Лучшее усилие: не получилось - строка в логе, дисплей работает как раньше.
 */
internal class DisplayWakeLocks(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val locks = HashMap<Int, PowerManager.WakeLock>()

    @Suppress("DEPRECATION")
    fun hold(displayId: Int) {
        release(displayId)
        try {
            val create = PowerManager::class.java.getMethod("newWakeLock",
                Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
            val lock = create.invoke(power, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "headswap:display$displayId", displayId) as PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            locks[displayId] = lock
            AppLog.i("WAKE экран $displayId удерживается включённым (wake lock по дисплею)", false)
        } catch (e: Throwable) {
            AppLog.i("WAKE экран $displayId: wake lock по дисплею не взят: ${e.javaClass.simpleName}: ${e.message}", false)
        }
    }

    companion object {
        /** Display.FLAG_OWN_DISPLAY_GROUP / FLAG_ALWAYS_UNLOCKED (android-16 Display.java, не DisplayManager.VIRTUAL_DISPLAY_FLAG_*). */
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 8
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 9

        /**
         * Замок по дисплею держим только у дисплея в своей группе: в общей он держал бы включённым экран самого телефона.
         * Смотрим на настоящую группу: Android 17 кладёт дисплей с OWN_DISPLAY_GROUP в группу 0 (h782), а дисплей
         * VirtualDevice в своей группе флага не несёт. Группа не прочиталась - по флагу, как раньше.
         */
        fun shouldHold(displayFlags: Int, groupId: Int? = null): Boolean =
            if (groupId != null) groupId != 0 else displayFlags and FLAG_OWN_DISPLAY_GROUP != 0

        fun describe(displayFlags: Int): String =
            "flags=0x${Integer.toHexString(displayFlags)} ownGroup=${displayFlags and FLAG_OWN_DISPLAY_GROUP != 0} " +
                "alwaysUnlocked=${displayFlags and FLAG_ALWAYS_UNLOCKED != 0}"
    }

    fun release(displayId: Int) {
        val lock = locks.remove(displayId) ?: return
        runCatching { if (lock.isHeld) lock.release() }
    }

    /** Отпускает замки дисплеев, которых уже нет среди [alive] (закрытый родитель уносит и дочерние). */
    fun retain(alive: Set<Int>) {
        for (id in locks.keys.toList()) if (id !in alive) release(id)
    }

    fun releaseAll() {
        for (id in locks.keys.toList()) release(id)
    }
}
