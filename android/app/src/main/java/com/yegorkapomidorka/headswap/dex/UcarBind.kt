package com.yegorkapomidorka.headswap.dex

import android.content.ComponentName
import android.content.Intent

/**
 * Подключение автомобильного интерфейса Amap (高德地图): запуск com.autonavi.map.activity.UcarMapActivity
 * с тремя extra, которые он единственный понимает (реверс 17.03.0.2008, метод u(Intent)):
 *
 *   isUcarMode             (boolean) — единственный шлюз: при false всё остальное не действует;
 *                                     проверки подписи или имени пакета в этой цепочке нет.
 *   screenMode             (int)     — 1 = полный домашний экран, 2 = только карта, навигация в карточке.
 *   isActivityMetricsFirst (boolean) — читать метрики окна до создания surface или после.
 *
 * English:
 * Binding for Amap's (高德地图) built-in car UI: launch com.autonavi.map.activity.UcarMapActivity
 * with the three extras it is the only one to read (from decompiled 17.03.0.2008, method u(Intent)):
 *
 *   isUcarMode             (boolean) — the only gate; when false nothing else applies. There is no
 *                                     signature or package-name check anywhere in this chain.
 *   screenMode             (int)     — 1 = full home UI, 2 = map only with navigation in a card.
 *   isActivityMetricsFirst (boolean) — read window metrics before creating the surface, or after.
 *
 * Примечание: у UcarMapActivity нет intent-filter LAUNCHER, запуск только явным компонентом,
 * поэтому обходим проверку requireLauncher в Headswap («компонент должен быть в списке лаунчеров»).
 * Note: UcarMapActivity has no LAUNCHER intent-filter, so it can only be started as an explicit component;
 * that is why we bypass Headswap's requireLauncher check ("must be in the launcher list").
 */
internal object UcarBind {
    const val PKG = "com.autonavi.minimap"
    const val CAR_ACT = "com.autonavi.map.activity.UcarMapActivity"

    /**
     * Полноэкранное окно навигатора (клавиша NAVI, развёрнутая карточка): полный домашний экран.
     * Full-area navigator window (NAVI key, expanded card): full home UI with search, cruise and route entry.
     */
    const val MODE_FULL = 1

    /**
     * Малое окно (плитка, карточка): только карта, без локальных элементов управления.
     * Small window (tile, card): map only, no local controls.
     */
    const val MODE_CARD = 2

    /**
     * Любой вход в Amap (телефонный или автомобильный интерфейс) перехватывает Headswap.
     * Any Amap entry point (phone UI or car UI alike) is taken over by Headswap.
     */
    fun isAmap(component: ComponentName): Boolean = component.packageName == PKG

    fun isCarShell(component: ComponentName): Boolean =
        component.packageName == PKG && component.className == CAR_ACT

    fun isCarShell(packageName: String, className: String): Boolean =
        packageName == PKG && className == CAR_ACT

    /**
     * Три параметра сразу; screenMode по роли окна (0 = не задавать, остаётся по умолчанию 1).
     * All three extras at once; screenMode by window role (0 = leave it unset, defaults to 1).
     */
    fun apply(intent: Intent, screenMode: Int) {
        intent.putExtra("isUcarMode", true)
        intent.putExtra("screenMode", if (screenMode > 0) screenMode else MODE_FULL)
        intent.putExtra("isActivityMetricsFirst", true)
    }
}
