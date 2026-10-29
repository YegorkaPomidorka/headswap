package com.yegorkapomidorka.headswap.aa

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yegorkapomidorka.headswap.AppLog

/**
 * Запущен ли сервер ГУ Android Auto. Пока он работает, AA держит foreground-уведомление
 * (канал gearhead_connection_status, «Сервер головного устройства запущен»): по нему и судим.
 * К порту 5277 не подключаемся никак: голый TCP вешает сервер AA.
 */
object AaServerWatch {
    const val GEARHEAD = "com.google.android.projection.gearhead"
    const val CHANNEL = "gearhead_connection_status"

    /** null - слушатель не подключён (нет доступа к уведомлениям), true - уведомление сервера есть, false - слушатель подключён, уведомления нет. */
    @Volatile
    var running: Boolean? = null

    /** Уведомление сервера ГУ: постоянное (foreground) уведомление AA в канале состояния подключения. */
    fun isServerNotification(pkg: String, channel: String?, flags: Int): Boolean =
        pkg == GEARHEAD && channel == CHANNEL && (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

    /** Пользователь выдал приложению доступ к уведомлениям (enabled_notification_listeners содержит наш компонент). */
    fun accessGranted(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, AaNotifListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

class AaNotifListener : NotificationListenerService() {
    // что писали в лог в прошлый раз: строку пишем только при смене статуса или набора уведомлений AA
    private var lastLogged: String? = null

    override fun onListenerConnected() = refresh(null)

    override fun onListenerDisconnected() {
        AaServerWatch.running = null
        log(null, emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == AaServerWatch.GEARHEAD) refresh(null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // в activeNotifications только что снятое уведомление может ещё числиться
        if (sbn.packageName == AaServerWatch.GEARHEAD) refresh(sbn.key)
    }

    private fun refresh(exceptKey: String?) {
        val gearhead = try {
            activeNotifications?.filter { it.packageName == AaServerWatch.GEARHEAD && it.key != exceptKey }
        } catch (e: SecurityException) {
            null
        }
        val now = gearhead?.any { isServer(it) }
        AaServerWatch.running = now
        log(now, gearhead.orEmpty())
    }

    private fun isServer(sbn: StatusBarNotification) =
        AaServerWatch.isServerNotification(sbn.packageName, sbn.notification.channelId, sbn.notification.flags)

    private fun log(running: Boolean?, gearhead: List<StatusBarNotification>) {
        val text = when (running) {
            true -> "запущен"
            false -> "остановлен"
            null -> "неизвестно"
        }
        val items = gearhead.map {
            val n = it.notification
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
            val body = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
            "  gearhead id=${it.id} channel=${n.channelId} flags=0x${Integer.toHexString(n.flags)} title=$title text=$body"
        }
        val full = text + items.joinToString("") { "\n$it" }
        if (full == lastLogged) return
        lastLogged = full
        AppLog.i("сервер ГУ по уведомлению: $text")
        if (items.isNotEmpty()) AppLog.detail(items.joinToString("\n"))
    }
}
