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
