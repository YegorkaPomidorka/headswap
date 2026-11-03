package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Тема лаунчера: акцент и подсветка углов. Светлой нет. */
enum class ThemeChoice(val accent: Color, val glow: Boolean) {
    GOLD(Color(0xFFD4AF37), true),
    GOLD_DEEP(Color(0xFFC9A227), true),
    GOLD_LIGHT(Color(0xFFE8C65A), true),
    DARK(Color(0xFFD4AF37), false);

    companion object {
        fun restore(name: String?): ThemeChoice = entries.firstOrNull { it.name == name } ?: GOLD
    }
}

/** Настройки лаунчера - SharedPreferences, как остальной проект (DexPrefs/AaSettings). */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_launcher", Context.MODE_PRIVATE)

    var apps: List<AppRef>
        get() = AppList.decode(prefs.getString("apps", null))
        set(value) { prefs.edit().putString("apps", AppList.encode(value)).apply() }

    var theme: ThemeChoice
        get() = ThemeChoice.restore(prefs.getString("theme", null))
        set(value) { prefs.edit().putString("theme", value.name).apply() }

    /** Правый руль: раскладка зеркальная, рейка и блок навигации справа. */
    var rightHand: Boolean
        get() = prefs.getBoolean("right_hand", false)
        set(value) { prefs.edit().putBoolean("right_hand", value).apply() }

    var cardView: CardView
        get() = CardView.restore(prefs.getString("card_view", null))
        set(value) { prefs.edit().putString("card_view", value.name).apply() }

    /** Пакет выбранного навигатора; null - по умолчанию ([NavigatorPick]). */
    var navigator: String?
        get() = prefs.getString("navigator", null)
        set(value) { prefs.edit().putString("navigator", value).apply() }

    /** Пакет текущего музыкального плеера. */
    var musicPlayer: String?
        get() = prefs.getString("music_player", null)
        set(value) { prefs.edit().putString("music_player", value).apply() }

    /** Последний онлайн-плеер (второй слот чипа плееров): что слушали или выбирали последним. */
    var lastOnlinePlayer: String?
        get() = prefs.getString("last_online_player", null)
        set(value) { prefs.edit().putString("last_online_player", value).apply() }

    /** Пропущенные не новее этого момента уже просмотрены (тап по карточке «Телефон»). */
    var missedSeenMs: Long
        get() = prefs.getLong("missed_seen", 0L)
        set(value) { prefs.edit().putLong("missed_seen", value).apply() }
}
