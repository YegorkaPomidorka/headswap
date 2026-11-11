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
 * Возраст: этот скрытый автомобильный вход существует более трёх лет — один и тот же UcarMapActivity
 * и те же extra в нескольких версиях Amap, то есть это давняя часть продукта, а не отладочный вход.
 * Note: UcarMapActivity has no LAUNCHER intent-filter, so it can only be started as an explicit component;
 * that is why we bypass Headswap's requireLauncher check ("must be in the launcher list").
 * Age: this hidden car-link entry has existed for more than three years (the same UcarMapActivity and the
 * same extras across several Amap releases), so it is a long-lived component, not a debug-only entry.
 * 中文：
 * 高德地图内置车机界面的接驳：启动 com.autonavi.map.activity.UcarMapActivity，并带上它唯一识别的
 * 三个 extra（来自反编译 17.03.0.2008，方法 u(Intent)）：
 *
 *   isUcarMode             (boolean) — 唯一门禁：为 false 时其余全部不生效；这条链路里没有签名或包名校验。
 *   screenMode             (int)     — 1 = 完整主页界面，2 = 只留地图、导航收进卡片。
 *   isActivityMetricsFirst (boolean) — 建 surface 之前还是之后读取窗口度量。
 *
 * 注：UcarMapActivity 没有 LAUNCHER 的 intent-filter，只能作为显式组件启动，因此绕开 Headswap 的
 * requireLauncher 校验（「组件必须在启动器列表里」）。
 * 存在时间：这个隐藏的车联入口已经存在 3 年以上——多代高德版本一直用同一个 UcarMapActivity 和同一组
 * extra，说明它是长期维护的组件，不是临时调试入口。
 */
internal object UcarBind {
    const val PKG = "com.autonavi.minimap"
    const val CAR_ACT = "com.autonavi.map.activity.UcarMapActivity"

    /**
     * Полноэкранное окно навигатора (клавиша NAVI, развёрнутая карточка): полный домашний экран.
     * Full-area navigator window (NAVI key, expanded card): full home UI with search, cruise and route entry.
     * 全屏导航窗口（NAVI 键、展开后的卡片）：完整主页界面，带搜索、巡航、路线入口。
     */
    const val MODE_FULL = 1

    /**
     * Малое окно (плитка, карточка): только карта, без локальных элементов управления.
     * Small window (tile, card): map only, no local controls.
     * 小窗（卡片、桌面小窗）：只留地图，没有本地控件。
     */
    const val MODE_CARD = 2

    /**
     * Любой вход в Amap (телефонный или автомобильный интерфейс) перехватывает Headswap.
     * Any Amap entry point (phone UI or car UI alike) is taken over by Headswap.
     * 高德的任何入口（手机界面或车机界面）都由 Headswap 接管。
     */
    fun isAmap(component: ComponentName): Boolean = component.packageName == PKG

    fun isCarShell(component: ComponentName): Boolean =
        component.packageName == PKG && component.className == CAR_ACT

    fun isCarShell(packageName: String, className: String): Boolean =
        packageName == PKG && className == CAR_ACT

    /**
     * Три параметра сразу; screenMode по роли окна (0 = не задавать, остаётся по умолчанию 1).
     * All three extras at once; screenMode by window role (0 = leave it unset, defaults to 1).
     * 三个参数一次写齐；screenMode 按窗口角色给（0 = 不设置，保持默认 1）。
     */
    fun apply(intent: Intent, screenMode: Int) {
        intent.putExtra("isUcarMode", true)
        intent.putExtra("screenMode", if (screenMode > 0) screenMode else MODE_FULL)
        intent.putExtra("isActivityMetricsFirst", true)
    }
}
