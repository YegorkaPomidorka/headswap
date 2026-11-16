package com.yegorkapomidorka.headswap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Глобальная и китайская ветки лаунчера (без Яндекса; в китайской навигаторы - только Amap) по тегу языка; тесты по мотивам форка TNTfish/headswap. */
class AppLocaleTest {
    @Test fun storedChineseTagUsesGlobalApps() {
        // Android 10–12: в prefs лежит тег из выбора языка целиком, «zh-CN»
        assertTrue(AppLocale.usesGlobalAppsForLanguage("zh-CN"))
    }

    @Test fun chineseVariantsUseGlobalApps() {
        for (tag in listOf("zh", "zh-Hans-CN", "zh-TW")) assertTrue(tag, AppLocale.usesGlobalAppsForLanguage(tag))
    }

    @Test fun arabicAndFrenchUseGlobalApps() {
        for (tag in listOf("ar", "ar-SA", "ar-u-nu-latn", "fr", "fr-FR")) assertTrue(tag, AppLocale.usesGlobalAppsForLanguage(tag))
    }

    @Test fun onlyChineseUsesChineseApps() {
        for (tag in listOf("zh", "zh-CN", "zh-Hans-CN", "zh-TW")) assertTrue(tag, AppLocale.usesChineseAppsForLanguage(tag))
        for (tag in listOf("ar", "fr", "en", "ru", "system")) assertFalse(tag, AppLocale.usesChineseAppsForLanguage(tag))
        assertFalse(AppLocale.usesChineseAppsForLanguage(null))
    }

    @Test fun otherLanguagesDoNot() {
        for (tag in listOf("en", "en-US", "ru", "ru-RU", "de", "system")) assertFalse(tag, AppLocale.usesGlobalAppsForLanguage(tag))
        assertFalse(AppLocale.usesGlobalAppsForLanguage(null))
    }
}
