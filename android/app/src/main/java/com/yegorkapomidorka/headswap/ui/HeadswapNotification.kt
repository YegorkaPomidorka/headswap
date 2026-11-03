package com.yegorkapomidorka.headswap.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.dex.DexState

/**
 * Уведомление foreground-сервиса по Notification.dc.html: заголовок = состояние, текст = этапы, действие «Стоп».
 * Сервис только вызывает build(); пока он работает, тикер раз в секунду обновляет уведомление, если состояние изменилось.
 */
object HeadswapNotification {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastKey = ""

    fun build(ctx: Context, channel: String, notifId: Int): Notification {
        val app = ctx.applicationContext
        val n = from(app, channel, HeadswapStatus.current(app))
        startTicker(app, channel, notifId)
        return n
    }

    /** Режим Headswap Desktop: статус рабочего стола вместо этапов AA, без действия «Открыть Android Auto». */
    private fun desktopText(): String = DexState.error ?: "${DexState.status} · ${CarLifeState.status}"

    private fun desktopFrom(ctx: Context, channel: String): Notification {
        val loc = AppLocale.wrap(ctx)
        val text = desktopText()
        val stop = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setColor(0xFFF2B33D.toInt())
            .setContentTitle(loc.getString(R.string.desk_notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stop), loc.getString(R.string.action_stop), stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun from(ctx: Context, channel: String, st: HomeState): Notification {
        if (DexState.desktopSession) return desktopFrom(ctx, channel)
        val loc = AppLocale.wrap(ctx)       // applicationContext не знает выбранного языка на Android 10-12
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setColor(0xFFF2B33D.toInt())
            .setContentTitle(st.notifTitle.get(loc))
            .setContentText(st.notifText.get(loc))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val since = HeadswapStatus.connectedSince
        if (st.phase == Phase.CONNECTED && since > 0) b.setUsesChronometer(true).setWhen(since).setShowWhen(true)
        if (st.phase == Phase.RECONNECTING && st.graceLeftS >= 0) b.setProgress(st.graceTotalS.coerceAtLeast(1), st.graceLeftS, false)
        if (st.phase == Phase.ERROR || (st.phase == Phase.OFF && st.primary != null)) {
            val open = PendingIntent.getActivity(
                ctx, 1, Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_open), loc.getString(R.string.action_open_aa), open).build())
        }
        val stop = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stop), loc.getString(R.string.action_stop), stop).build())
        return b.build()
    }

    private fun startTicker(app: Context, channel: String, notifId: Int) {
        if (ticking) return
        ticking = true
        val nm = app.getSystemService(NotificationManager::class.java)
        handler.postDelayed(object : Runnable {
            override fun run() {
                val st = HeadswapStatus.current(app)       // заодно обновляет переходы состояния
                if (!CarLifeState.running) {
                    ticking = false
                    lastKey = ""
                    return
                }
                val loc = AppLocale.wrap(app)
                val key = if (DexState.desktopSession) "desktop|${desktopText()}"
                else "${st.notifTitle.get(loc)}|${st.notifText.get(loc)}|${st.graceLeftS}|${st.phase}"
                if (key != lastKey) {
                    lastKey = key
                    nm.notify(notifId, from(app, channel, st))
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }
}

/** Кнопка «Стоп» в уведомлении: пометить остановку пользователем и отдать сервису ACTION_STOP. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Actions.stopBridge(context)
    }
}
