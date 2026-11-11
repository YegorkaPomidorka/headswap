package com.yegorkapomidorka.headswap.carapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Геолокация навигатора на Car App Library. Под нашим хостом без «Разрешить в любом режиме» его NavigationService не
 * может перейти в foreground с типом location, и GPS нет (стенд, h1029): курсор не появляется, маршрут не ведётся.
 */
data class CarAppLocation(val fine: Boolean, val coarse: Boolean, val background: Boolean) {
    /** Строка для лога сессии. */
    fun describe() = "fine=$fine coarse=$coarse фон=$background"

    companion object {
        fun of(ctx: Context, pkg: String): CarAppLocation {
            val pm = ctx.packageManager
            fun granted(p: String) = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
            return CarAppLocation(
                fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
                coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            )
        }
    }
}
