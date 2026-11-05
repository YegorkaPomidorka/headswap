package com.yegorkapomidorka.headswap.launcher

import android.content.Context

/** Тема лаунчера: тёмное или светлое золото; «Авто» - по режиму фар машины (CHANGE_MD_THEME от ГУ). */
enum class ThemeChoice {
    DARK, LIGHT, AUTO;

    companion object {
        /** Старые темы-акценты (GOLD, GOLD_DEEP, GOLD_LIGHT - золото на тёмном) становятся тёмным золотом. */
        fun restore(name: String?): ThemeChoice = entries.firstOrNull { it.name == name } ?: DARK

        /**
         * Светлая ли палитра. «Авто» светлое только по дневному режиму ГУ (themeModes 0); ночь, «авто» ГУ и
         * «ГУ ещё ничего не прислала» - тёмное.
         */
        fun light(choice: ThemeChoice, huTheme: Int?): Boolean = when (choice) {
            DARK -> false
            LIGHT -> true
            AUTO -> huTheme == 0
        }
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

    /** «Карта на главном» слева: ряды главного зеркальные, рейка остаётся слева. Новый ключ: старый right_hand двигал рейку. */
    var mapLeft: Boolean
        get() = prefs.getBoolean("map_left", false)
        set(value) { prefs.edit().putBoolean("map_left", value).apply() }

    /** «Анимации кнопок» рейки. */
    var animations: Boolean
        get() = prefs.getBoolean("animations", true)
        set(value) { prefs.edit().putBoolean("animations", value).apply() }

    /** «Включать музыку автоматически»: при старте сессии сразу играет, как в Android Auto. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }

    /** Первый слот чипа плееров: свой плеер на телефоне; null - первый найденный. */
    var localPlayer: String?
        get() = prefs.getString("local_player", null)
        set(value) { prefs.edit().putString("local_player", value).apply() }

    /** Второй слот: закреплённый онлайн-плеер; null - «Последний» (что слушали последним). */
    var onlinePlayer: String?
        get() = prefs.getString("online_player", null)
        set(value) { prefs.edit().putString("online_player", value).apply() }

    /** Большая плашка главного (в четырёх плашках - место навигации). Ключ с прошлых версий. */
    var cardView: CardView
        get() = CardView.restore(prefs.getString("card_view", null))
        set(value) { prefs.edit().putString("card_view", value.name).apply() }

    /** Малая плашка в раскладках 2:1 и 1:2. */
    var smallView: CardView
        get() = CardView.restore(prefs.getString("small_view", null), CardView.MUSIC)
        set(value) { prefs.edit().putString("small_view", value.name).apply() }

    /** «Раскладка» главного: четыре плашки (по умолчанию) или две. */
    var homeLayout: HomeLayout
        get() = HomeLayout.restore(prefs.getString("home_layout", null))
        set(value) { prefs.edit().putString("home_layout", value.name).apply() }

    /** Пакет выбранного навигатора; null - по умолчанию ([NavigatorPick]). */
    var navigator: String?
        get() = prefs.getString("navigator", null)
        set(value) { prefs.edit().putString("navigator", value).apply() }

    /** «Масштаб карты» навигатора [pkg]: ступень [com.yegorkapomidorka.headswap.carapp.CarConfigMath.STEPS]. */
    fun mapScale(pkg: String): Int = prefs.getInt("map_scale:$pkg", com.yegorkapomidorka.headswap.carapp.CarConfigMath.AUTO_STEP)
    fun setMapScale(pkg: String, step: Int) { prefs.edit().putInt("map_scale:$pkg", step).apply() }

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
