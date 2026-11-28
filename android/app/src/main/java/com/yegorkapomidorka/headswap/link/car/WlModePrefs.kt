package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/** Выбранный способ беспроводного подключения ([WlMode]). Отдельный файл prefs: [CarProfile.clear] его не стирает. */
class WlModePrefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("wl_mode", Context.MODE_PRIVATE)

    val mode: WlMode get() = WlMode.entries.firstOrNull { it.name == sp.getString("mode", null) } ?: WlMode.NONE

    /** «Стоп» человеком в режиме BOX: автостарт по ACL бокса молчит ([WlPolicy.boxAutoStart]), пока не снимут ручным стартом. */
    var boxMuted: Boolean
        get() = sp.getBoolean("boxMuted", false)
        set(v) { sp.edit().putBoolean("boxMuted", v).apply() }

    /** Время (System.currentTimeMillis) последнего ACL-события от бокса. */
    var boxSeenMs: Long
        get() = sp.getLong("boxSeenMs", 0L)
        set(v) { sp.edit().putLong("boxSeenMs", v).apply() }

    /**
     * Сменить режим. Переход между BOX и остальными забывает устройство из [CarProfile] ([CarPairing.forget]): MAC бокса
     * не должен считаться машиной и наоборот. Идущий сервис не трогает: остановить его - дело вызывающего (ACTION_STOP).
     */
    fun set(new: WlMode) {
        val old = mode
        if (old == new) return
        if (WlPolicy.forgetOnSwitch(old, new) && CarProfile(app).isSet) CarPairing.forget(app)
        sp.edit().putString("mode", new.name).apply()
    }
}
