package com.yegorkapomidorka.headswap.carapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.yegorkapomidorka.headswap.AppLog

/**
 * Навигаторы на Car App Library: сервис с action CarAppService и категорией NAVIGATION (queries в манифесте).
 * Вендоров не перечисляем - годится любой. Отказ хоста или приложения запоминается в [verdicts], тогда навигатор
 * показывается обычным окном.
 */
object CarAppCatalog {
    const val ACTION = "androidx.car.app.CarAppService"
    const val CATEGORY_NAVIGATION = "androidx.car.app.category.NAVIGATION"

    val verdicts = CarAppVerdicts()

    /** Все навигаторы на Car App Library по пакетам, включая отказавшие. */
    fun navigators(ctx: Context): Map<String, CarAppTarget> {
        val pm = ctx.packageManager
        val found = try {
            pm.queryIntentServices(Intent(ACTION).addCategory(CATEGORY_NAVIGATION), PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            AppLog.i("CARAPP поиск навигаторов не удался: ${e.javaClass.simpleName}: ${e.message}", false)
            emptyList()
        }
        val targets = found.mapNotNull { r ->
            val s = r.serviceInfo ?: return@mapNotNull null
            if (!s.exported || !s.enabled) return@mapNotNull null
            val version = runCatching { pm.getPackageInfo(s.packageName, 0).longVersionCode }.getOrDefault(0L)
            CarAppTarget(s.packageName, s.name, version, s.permission)
        }
        return CarAppPick.targets(targets, ctx.packageName)
    }

    /** Навигатор [pkg], если он на Car App Library и не отказал нам в этой версии; иначе null (окно). */
    fun usable(ctx: Context, pkg: String): CarAppTarget? {
        val t = navigators(ctx)[pkg] ?: return null
        if (verdicts.refusal(t) != null) return null
        val perm = t.permission
        if (perm != null && ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            AppLog.i("CARAPP $pkg: сервис требует $perm, хост не поднимаем", false)
            verdicts.refuse(t, CarAppRefusal.PERMISSION)
            return null
        }
        return t
    }
}
