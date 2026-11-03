package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog

/** Стенд без машины: экран, раздел настроек, тема и сторона карты лаунчера из adb (MainActivity --es cmd launcher). */
internal object LauncherDebug {
    fun apply(ctx: Context, intent: Intent) {
        LauncherModel.init(ctx)
        val model = LauncherModel
        Handler(Looper.getMainLooper()).post {
            intent.getStringExtra("theme")?.let { t -> ThemeChoice.entries.firstOrNull { it.name.equals(t, true) }?.let(model::chooseTheme) }
            intent.getStringExtra("map")?.let { model.chooseMapLeft(it == "left") }
            intent.getStringExtra("page")?.let { p -> SettingsPage.entries.firstOrNull { it.name.equals(p, true) }?.let { model.settingsPage = it } }
            // рабочий стол «пакет/класс»; "own" - наш главный
            intent.getStringExtra("desktop")?.let { d -> model.chooseDesktop(d.takeUnless { it == "own" }); LauncherActions.home() }
            when (intent.getStringExtra("area")) {
                "home" -> LauncherActions.home()
                "settings" -> model.go(Area.Settings)
            }
            AppLog.i("LAUNCHER debug: экран=${model.area} раздел=${model.settingsPage} тема=${model.theme} карта слева=${model.mapLeft}", false)
        }
    }
}