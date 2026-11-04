// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder

/**
 * Клавиатура на экране ГУ. Для вторичного доверенного дисплея Android по умолчанию ставит IME policy
 * FALLBACK_DISPLAY, и клавиатура всплывает на телефоне. Переключаем свои дисплеи в LOCAL через скрытый
 * IWindowManager (главный процесс запущен `am instrument --no-hidden-api-checks`, право INTERNAL_SYSTEM_WINDOW
 * из активации). Дисплей 0 не трогаем никогда.
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object DisplayIme {
    const val PERMISSION = "android.permission.INTERNAL_SYSTEM_WINDOW"
    private const val LOCAL = 0 // WindowManager.DISPLAY_IME_POLICY_LOCAL
    private val wmType by lazy { Class.forName("android.view.IWindowManager") }
    private val windowManager by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
        checkNotNull(Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder))
    }
    private val getPolicy by lazy { wmType.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType) }
    private val setPolicy by lazy { wmType.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }

    private val api by lazy {
        try { windowManager; getPolicy; setPolicy; true }
        catch (_: Exception) { false }
        catch (_: LinkageError) { false }
    }

    fun available(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED && api

    /** Android может молча не применить политику, поэтому после set читаем её обратно. */
    fun enable(id: Int) {
        require(id > 0) { "Клавиатуру основного экрана не трогаем" }
        if (getPolicy.invoke(windowManager, id) != LOCAL) {
            setPolicy.invoke(windowManager, id, LOCAL)
            check(getPolicy.invoke(windowManager, id) == LOCAL) { "Android не применил клавиатуру к дисплею $id" }
        }
    }
}

/**
 * Дисплеи, которым ещё не удалось поставить LOCAL: повтор по пульсу активации (5 с), с удвоением до минуты.
 * Ключ - id и токен регистрации, чтобы повтор не попал на чужой дисплей с тем же id после переподключения.
 */
internal class DelegatedImeRoutes {
    private class Route(val entry: DelegatedInputRegistry.Entry) {
        var retryAt = 0L
        var delay = FIRST_RETRY_MS
    }
    private val pending = LinkedHashMap<Int, Route>()

    @Synchronized fun add(entry: DelegatedInputRegistry.Entry) { pending[entry.id] = Route(entry) }
    @Synchronized fun retain(live: Set<Int>) { pending.keys.retainAll(live) }
    @Synchronized fun clear() = pending.clear()

    /** [apply] возвращает true, если LOCAL подтверждён. Не больше одной неудачной попытки за пульс. */
    @Synchronized fun refresh(now: Long, apply: (DelegatedInputRegistry.Entry) -> Boolean) {
        val due = pending.values.filter { it.retryAt <= now }.sortedBy { it.retryAt }
        for (route in due) {
            if (apply(route.entry)) pending.remove(route.entry.id)
            else {
                route.retryAt = now + route.delay
                route.delay = (route.delay * 2).coerceAtMost(MAX_RETRY_MS)
                break
            }
        }
    }

    private companion object {
        const val FIRST_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
