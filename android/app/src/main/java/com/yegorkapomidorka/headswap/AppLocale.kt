package com.yegorkapomidorka.headswap

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Язык интерфейса приложения: «system» (по умолчанию), «en», «ru», «ar» или «zh-CN».
 * Арабский применяется с расширением «-u-nu-latn» (западные цифры 0–9, как в остальном интерфейсе и на ГУ);
 * [get] возвращает «ar» (сравнение по language).
 * Китайский упрощённый: тег «zh-CN» (ресурсы values-zh-rCN); [get] сводит любой китайский language «zh» к нему.
 * Android 13+: системный per-app язык (LocaleManager, виден и в системных настройках, см. res/xml/locales_config.xml).
 * Android 10–12: выбор хранится в prefs, контексты activity/сервисов оборачиваются через [wrap] в attachBaseContext.
 */
object AppLocale {
    const val SYSTEM = "system"
    val TAGS = listOf(SYSTEM, "en", "ru", "ar", "zh-CN")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** Текущий выбор: на 33+ из LocaleManager (его могли сменить в системных настройках), ниже из prefs. */
    fun get(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) return SYSTEM
            val lang = list[0].language
            return (if (lang == "zh") "zh-CN" else lang).takeIf { it in TAGS } ?: SYSTEM
        }
        return stored(ctx)
    }

    /** Сменить язык. На 33+ система сама пересоздаёт activity; ниже вызывающий делает recreate(). */
    fun set(ctx: Context, tag: String) {
        val t = if (tag in TAGS) tag else SYSTEM
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (t == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(applyTag(t))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply()
    }

    /**
     * Интерфейс на арабском (выбран явно или «Системный» при арабской системе). Без IPC: на 33+ per-app язык уже в
     * конфигурации контекста, ниже - выбор из prefs или язык системы. Лаунчер по нему прячет русские сервисы (h1003).
     */
    fun isArabic(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return ctx.resources.configuration.locales[0]?.language == "ar"
        val t = stored(ctx)
        return if (t == SYSTEM) android.content.res.Resources.getSystem().configuration.locales[0]?.language == "ar" else t == "ar"
    }

    /** Нужен ли ручной recreate() после [set]. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < 33

    /** Тег для системы: у арабского принудительно латинские цифры. */
    private fun applyTag(tag: String): String = if (tag == "ar") "ar-u-nu-latn" else tag

    private fun stored(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /**
     * Контекст с выбранным языком для Android 10–12 (на 33+ система уже подставила per-app язык). На любом API
     * арабский без системы цифр получает западные цифры ([latnDigits]); остальное на 33+ возвращается как есть.
     * Использовать в attachBaseContext и там, где строки берутся из applicationContext.
     */
    fun wrap(base: Context): Context {
        val tag = if (Build.VERSION.SDK_INT >= 33) SYSTEM else stored(base)
        val locale = if (tag != SYSTEM) Locale.forLanguageTag(applyTag(tag)) else latnDigits(base.resources.configuration.locales[0])
        if (locale == null || locale == base.resources.configuration.locales[0]) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }

    /**
     * Арабский без явной системы цифр (системный язык ar-SA или per-app «ar» из системных настроек Android 13+)
     * дал бы восточно-арабские цифры в getString(%d) - возвращаем ту же локаль с «nu=latn». Иначе null.
     */
    private fun latnDigits(l: Locale?): Locale? {
        if (l == null || l.language != "ar" || l.getUnicodeLocaleType("nu") != null) return null
        return Locale.Builder().setLocale(l).setUnicodeLocaleKeyword("nu", "latn").build()
    }
}
