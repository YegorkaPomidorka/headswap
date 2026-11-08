package com.yegorkapomidorka.headswap.diag

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.ui.Actions
import com.yegorkapomidorka.headswap.ui.LogShare
import com.yegorkapomidorka.headswap.ui.Readiness
import com.yegorkapomidorka.headswap.ui.SettingsSection
import com.yegorkapomidorka.headswap.update.Updater

/** Кнопки-действия под шагами диагноза (поле action в logdiag_rules.json) -> готовый код приложения. */
object DiagActions {
    private val LABELS = mapOf(
        "open_aa" to R.string.diag_act_open_aa,
        "video" to R.string.diag_act_video,
        "audio" to R.string.diag_act_audio,
        "battery" to R.string.diag_act_battery,
        "autostart" to R.string.diag_act_autostart,
        "app_settings" to R.string.diag_act_app_settings,
        "wifi" to R.string.diag_act_wifi,
        "hotspot" to R.string.diag_act_hotspot,
        "bluetooth" to R.string.diag_act_bluetooth,
        "onboarding" to R.string.diag_act_onboarding,
        "update" to R.string.diag_act_update,
        "share_log" to R.string.diag_act_share_log,
    )

    /** Подпись кнопки; null = действие неизвестно, кнопку не показываем. */
    fun label(action: String?): Int? = LABELS[action]

    fun run(host: MainActivity, action: String) {
        try {
            when (action) {
                "open_aa" -> Actions.openAaSettings(host)
                "video" -> host.showSettingsSection(SettingsSection.VIDEO)
                "audio" -> host.showSettingsSection(SettingsSection.SOUND)
                "battery" -> Readiness.requestIgnoreBattery(host)
                "autostart" -> if (Readiness.isXiaomi()) Readiness.openXiaomiAutostart(host) else Readiness.appDetails(host)
                "app_settings" -> Readiness.appDetails(host)
                "wifi" -> open(host, Settings.ACTION_WIFI_SETTINGS)
                "hotspot" -> hotspot(host)
                "bluetooth" -> Readiness.openBluetoothSettings(host)
                "onboarding" -> host.showOnboarding(rerun = true)
                "update" -> Updater.checkAndUpdate(host)
                "share_log" -> LogShare.shareReport(host)
                else -> AppLog.e("DIAG: неизвестное действие $action")
            }
        } catch (e: Exception) {
            AppLog.e("DIAG: действие $action не выполнилось", e)
        }
    }

    /** Настройки точки доступа (раздел модема), нет такого экрана на прошивке - беспроводные сети. */
    private fun hotspot(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.i("DIAG: TetherSettings не открылся (${e.javaClass.simpleName}), открываю беспроводные сети")
            open(ctx, Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    private fun open(ctx: Context, action: String) {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
