package com.yegorkapomidorka.headswap.launcher

import android.content.Context

/** Тема лаунчера: тёмная или светлая палитра (цвета - «Вид › Цвета»); «Авто» - по режиму фар машины (CHANGE_MD_THEME от ГУ). */
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

/** Размер боковой панели: «Маленькая», «Средняя», «Обычная»; «Обрезанная» - только на экранах высотой 480 ([LauncherSpec.h480]). */
enum class RailSize {
    SMALL, MEDIUM, NORMAL, CUT;

    companion object {
        fun restore(name: String?): RailSize = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/** Настройки лаунчера - SharedPreferences, как остальной проект (DexPrefs/AaSettings). */
class LauncherPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("headswap_launcher", Context.MODE_PRIVATE)

    var apps: List<AppRef>
        get() = AppList.decode(prefs.getString("apps", null))
        set(value) { prefs.edit().putString("apps", AppList.encode(value)).apply() }

    var theme: ThemeChoice
        get() = ThemeChoice.restore(prefs.getString("theme", null))
        set(value) { prefs.edit().putString("theme", value.name).apply() }

    /** Свои цвета «Вид › Цвета»: «акцент,оттенок фона,насыщенность фона». */
    var nightColors: ColorSet
        get() = ColorSet.decode(prefs.getString("colors_night", null), ColorSet.NIGHT)
        set(value) { prefs.edit().putString("colors_night", value.encode()).apply() }

    var dayColors: ColorSet
        get() = ColorSet.decode(prefs.getString("colors_day", null), ColorSet.DAY)
        set(value) { prefs.edit().putString("colors_day", value.encode()).apply() }

    var railSize: RailSize
        get() = RailSize.restore(prefs.getString("rail_size", null))
        set(value) { prefs.edit().putString("rail_size", value.name).apply() }

    /**
     * «Карта на главном» слева, физически. Новый ключ: старый right_hand двигал рейку.
     * По умолчанию на арабском (RTL) карта слева: весь главный - зеркало LTR, рейка справа, как в макете.
     */
    var mapLeft: Boolean
        get() = prefs.getBoolean("map_left", com.yegorkapomidorka.headswap.AppLocale.wrap(context).resources
            .configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
        set(value) { prefs.edit().putBoolean("map_left", value).apply() }

    /** «Анимации кнопок» рейки. */
    var animations: Boolean
        get() = prefs.getBoolean("animations", true)
        set(value) { prefs.edit().putBoolean("animations", value).apply() }

    /** «Заряд телефона» в строке статуса. */
    var showBattery: Boolean
        get() = prefs.getBoolean("show_battery", true)
        set(value) { prefs.edit().putBoolean("show_battery", value).apply() }

    /** «Включать музыку автоматически»: при старте сессии сразу играет, как в Android Auto. */
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) { prefs.edit().putBoolean("autoplay", value).apply() }

    /** «Алиса только голосом»: Алиса на скрытом дисплее и плашка снизу; выкл - панель Алисы слева (ADR 0026). */
    var aliceVoiceOnly: Boolean
        get() = prefs.getBoolean("alice_voice_only", true)
        set(value) { prefs.edit().putBoolean("alice_voice_only", value).apply() }

    /** «Уведомления» телефона на экране ГУ. */
    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) { prefs.edit().putBoolean("notifications", value).apply() }

    /** «Скрыть строку статуса» (Для разработчиков): на части VW ГУ она дублирует свою строку машины. */
    var hideStatusBar: Boolean
        get() = prefs.getBoolean("hide_status_bar", false)
        set(value) { prefs.edit().putBoolean("hide_status_bar", value).apply() }

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

    /** Что листают «Слои», по порядку ([LayerList]). */
    var layers: List<Layer>
        get() = LayerList.decode(prefs.getString("layer_list", null))
        set(value) { prefs.edit().putString("layer_list", LayerList.encode(value)).apply() }

    /** Пакет приложения в плашке-приложении ([CardView.APP]); до «Слоёв» это всегда был YouTube. */
    var tileApp: String
        get() = prefs.getString("tile_app", null) ?: FullscreenApp.YOUTUBE
        set(value) { prefs.edit().putString("tile_app", value).apply() }

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

    /** «Подсказки на приборке» навигатора [pkg]: машинная часть / теневая AA или окно как на телефоне ([NavigatorPick.hintsDefault]). */
    fun navHints(pkg: String): Boolean = prefs.getBoolean("nav_hints:$pkg", NavigatorPick.hintsDefault(pkg))
    fun setNavHints(pkg: String, on: Boolean) { prefs.edit().putBoolean("nav_hints:$pkg", on).apply() }

    /** Пакет текущего музыкального плеера. */
    var musicPlayer: String?
        get() = prefs.getString("music_player", null)
        set(value) { prefs.edit().putString("music_player", value).apply() }

    /** Последний онлайн-плеер (второй слот чипа плееров): что слушали или выбирали последним. */
    var lastOnlinePlayer: String?
        get() = prefs.getString("last_online_player", null)
        set(value) { prefs.edit().putString("last_online_player", value).apply() }

    /** Последний свой плеер (иконка первого слота чипа, пока он не закреплён): что слушали или выбирали последним. */
    var lastLocalPlayer: String?
        get() = prefs.getString("last_local_player", null)
        set(value) { prefs.edit().putString("last_local_player", value).apply() }

    /** Пропущенные не новее этого момента уже просмотрены (тап по карточке «Телефон»). */
    var missedSeenMs: Long
        get() = prefs.getLong("missed_seen", 0L)
        set(value) { prefs.edit().putLong("missed_seen", value).apply() }

    // --- Текст песни на рабочем столе ---

    /** «Текст песни на экране»: единственный вход, без него слой не существует. */
    var lyricsOn: Boolean
        get() = prefs.getBoolean("lyrics_on", false)
        set(value) { prefs.edit().putBoolean("lyrics_on", value).apply() }

    /** Замок держит только позицию: цвет, размер, интервал остаются доступны. */
    var lyricsLocked: Boolean
        get() = prefs.getBoolean("lyrics_locked", false)
        set(value) { prefs.edit().putBoolean("lyrics_locked", value).apply() }

    /** Стык (середина перекрытия) в долях экрана, 0-1. */
    var lyricsX: Float
        get() = prefs.getFloat("lyrics_x", 0.5f)
        set(value) { prefs.edit().putFloat("lyrics_x", value).apply() }

    var lyricsY: Float
        get() = prefs.getFloat("lyrics_y", 0.80f)
        set(value) { prefs.edit().putFloat("lyrics_y", value).apply() }

    /** Размер шрифта: множитель к 6.25% высоты экрана (45 px на 1280x720). */
    var lyricsScale: Float
        get() = prefs.getFloat("lyrics_scale", 1.0f)
        set(value) { prefs.edit().putFloat("lyrics_scale", value).apply() }

    /** Интервал между строками в долях кегля (1.3 = 58 px при 45 px). */
    var lyricsGapEm: Float
        get() = prefs.getFloat("lyrics_gap_em", 1.3f)
        set(value) { prefs.edit().putFloat("lyrics_gap_em", value).apply() }

    /** Перекрытие концов в долях кегля (2.0 = 90 px, примерно два знака). */
    var lyricsOvEm: Float
        get() = prefs.getFloat("lyrics_ov_em", 2.0f)
        set(value) { prefs.edit().putFloat("lyrics_ov_em", value).apply() }

    /** Цвет в HSV: серый и чёрный не дают обратно оттенок, поэтому храним три числа. */
    var lyricsH: Float
        get() = prefs.getFloat("lyrics_h", 0f)
        set(value) { prefs.edit().putFloat("lyrics_h", value).apply() }

    var lyricsS: Float
        get() = prefs.getFloat("lyrics_s", 0f)
        set(value) { prefs.edit().putFloat("lyrics_s", value).apply() }

    var lyricsV: Float
        get() = prefs.getFloat("lyrics_v", 1f)
        set(value) { prefs.edit().putFloat("lyrics_v", value).apply() }

    /** Радужная заливка букв: по умолчанию включена, три слайдера цвета тогда не нужны. */
    var lyricsRainbow: Boolean
        get() = prefs.getBoolean("lyrics_rainbow", true)
        set(value) { prefs.edit().putBoolean("lyrics_rainbow", value).apply() }

    var lyricsAlpha: Float
        get() = prefs.getFloat("lyrics_alpha", 0.9f)
        set(value) { prefs.edit().putFloat("lyrics_alpha", value).apply() }

    /** Один раз опускаем позицию по умолчанию ниже: у первого выпуска стык стоял слишком высоко. */
    var lyricsPosMoved: Boolean
        get() = prefs.getBoolean("lyrics_pos_moved", false)
        set(value) { prefs.edit().putBoolean("lyrics_pos_moved", value).apply() }

    /** Направление радуги: 0 - горизонталь, 1 - вертикаль, 2 - диагональ. */
    var lyricsDir: Int
        get() = prefs.getInt("lyrics_dir", 0)
        set(value) { prefs.edit().putInt("lyrics_dir", value).apply() }
}
