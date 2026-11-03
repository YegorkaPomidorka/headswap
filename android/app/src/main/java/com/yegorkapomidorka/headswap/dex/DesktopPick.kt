package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.launcher.AppRef

/** Сторонний лаунчер вместо нашего на весь экран ГУ: что предложить в настройках телефона и как разобрать сохранённый выбор. */
internal object DesktopPick {
    /**
     * Лаунчеры для выбора: exported вход под LAUNCHER (каталог, наш пакет там уже отфильтрован), который сам же стоит под
     * HOME ([homeKeys] - «пакет/класс»). HOME-заглушки с отдельным LAUNCHER-входом (FallbackHome «Настроек») - не лаунчеры.
     */
    fun options(catalog: List<DesktopAppCatalog.Entry>, homeKeys: Set<String>): List<DesktopAppCatalog.Entry> =
        catalog.filter { it.key in homeKeys }.distinctBy { it.packageName }

    /** Строка вида «пакет/класс» с обеими частями. */
    fun isComponent(value: String?): Boolean = value != null && value.indexOf('/').let { it > 0 && it < value.length - 1 }

    /** Выбранный компонент «пакет/класс», пока его пакет есть среди вариантов (активность могла смениться); иначе null - Headswap. */
    fun selected(component: String?, options: List<DesktopAppCatalog.Entry>): DesktopAppCatalog.Entry? {
        val pkg = AppRef.parse(component ?: return null)?.pkg ?: return null
        return options.firstOrNull { it.packageName == pkg }
    }

    /** Установленные лаунчеры, кроме нашего пакета (у smoke-сборки он с суффиксом); запросы к PackageManager, не с главного потока. */
    fun installed(context: Context): List<DesktopAppCatalog.Entry> {
        val pm = context.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapNotNull { info ->
                val a = info.activityInfo ?: return@mapNotNull null
                DesktopAppCatalog.Entry(a.packageName, a.name,
                    runCatching { info.loadLabel(pm).toString() }.getOrDefault(a.packageName),
                    enabled = a.enabled && a.applicationInfo.enabled, exported = a.exported)
            }
        }.getOrElse { AppLog.i("DESKTOP лаунчеры: LAUNCHER ${it.javaClass.simpleName}", false); emptyList() }
        val homes = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNullTo(HashSet()) { info -> info.activityInfo?.let { "${it.packageName}/${it.name}" } }
        }.getOrElse { AppLog.i("DESKTOP лаунчеры: HOME ${it.javaClass.simpleName}", false); emptySet() }
        return options(DesktopAppCatalog.select(found, context.packageName), homes)
    }
}
