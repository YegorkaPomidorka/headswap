package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.dex.DesktopPick
import com.yegorkapomidorka.headswap.dex.DexPrefs

/** Стенд без машины: экран, раздел настроек, тема и сторона карты лаунчера из adb (MainActivity --es cmd launcher). */
internal object LauncherDebug {
    fun apply(ctx: Context, intent: Intent) {
        LauncherModel.init(ctx)
        val model = LauncherModel
        Handler(Looper.getMainLooper()).post {
            intent.getStringExtra("theme")?.let { t -> ThemeChoice.entries.firstOrNull { it.name.equals(t, true) }?.let(model::chooseTheme) }
            intent.getStringExtra("map")?.let { model.chooseMapLeft(it == "left") }
            intent.getStringExtra("page")?.let { p -> SettingsPage.entries.firstOrNull { it.name.equals(p, true) }?.let { model.settingsPage = it } }
            // лаунчер на ГУ «пакет/класс» (DexPrefs); "own" - наш. Применяется тем же путём, что из настроек:
            // showDesktop -> LauncherActivity.onNewIntent перечитывает выбор
            intent.getStringExtra("desktop")?.let { d ->
                if (d == "own" || DesktopPick.isComponent(d)) {
                    DexPrefs(ctx).desktopComponent = d.takeUnless { it == "own" }
                    BridgeService.requestDesktopHome(ctx)
                }
            }
            when (intent.getStringExtra("area")) {
                "home" -> LauncherActions.home()
                "settings" -> model.go(Area.Settings)
            }
            AppLog.i("LAUNCHER debug: экран=${model.area} раздел=${model.settingsPage} тема=${model.theme} карта слева=${model.mapLeft}", false)
        }
    }
}