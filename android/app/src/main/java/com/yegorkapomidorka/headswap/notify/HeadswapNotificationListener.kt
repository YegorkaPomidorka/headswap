package com.yegorkapomidorka.headswap.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.launcher.NotifEntry
import com.yegorkapomidorka.headswap.launcher.NotifItem
import com.yegorkapomidorka.headswap.launcher.NotifRaw
import com.yegorkapomidorka.headswap.launcher.NotificationsLogic
import com.yegorkapomidorka.headswap.launcher.NotificationsRepo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Уведомления телефона для экрана магнитолы. Системе доступ выдаёт активация с ПК (`cmd notification allow_listener`),
 * свой экран в настройках телефона тоже подойдёт. Живёт в главном процессе рядом с лаунчером.
 *
 * В лог - только пакет и количество, никогда текст, заголовок, отправитель и ключ: логи уходят в поддержку.
 * Тела обратных вызовов целиком под try/catch: extras чужих приложений при разборе умеют бросать что угодно,
 * и это не должно ронять мост.
 */
class HeadswapNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        runCatching { NotificationsRepo.configure(applicationContext) }
    }

    override fun onListenerConnected() {
        instance = this
        sync()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        runCatching { requestRebind(ComponentName(this, HeadswapNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        if (!NotificationsRepo.enabled) return
        val importance = importanceOf(sbn.key, rankingMap)
        io.execute {
            try {
                val item = parse(sbn, importance)
                main.post {
                    if (item != null) NotificationsRepo.post(item) else NotificationsRepo.removed(sbn.key)
                }
            } catch (e: Exception) {
                AppLog.i("NOTIFY разбор: ${e.javaClass.simpleName} (${sbn.packageName})", false)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        val key = sbn.key
        main.post { NotificationsRepo.removed(key) }
    }

    private fun sync() {
        if (!NotificationsRepo.enabled) return
        io.execute {
            try {
                val active = activeNotifications ?: return@execute
                val ranking = currentRanking
                val items = active.mapNotNull { sbn ->
                    try { parse(sbn, importanceOf(sbn.key, ranking)) }
                    catch (e: Exception) { AppLog.i("NOTIFY разбор: ${e.javaClass.simpleName} (${sbn.packageName})", false); null }
                }
                main.post { NotificationsRepo.sync(items) }
            } catch (e: Exception) {
                AppLog.i("NOTIFY список: ${e.javaClass.simpleName}", false)
            }
        }
    }

    private fun importanceOf(key: String, ranking: RankingMap?): Int {
        if (ranking == null) return NotificationsLogic.IMPORTANCE_DEFAULT
        val r = Ranking()
        return if (ranking.getRanking(key, r)) r.importance else NotificationsLogic.IMPORTANCE_DEFAULT
    }

    /** null - в список не нужно. Вне главного потока: тут грузятся картинки. */
    private fun parse(sbn: StatusBarNotification, importance: Int): NotifItem? {
        val ctx: Context = applicationContext
        val n = sbn.notification ?: return null
        val ex = n.extras ?: Bundle()
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val raw = NotifRaw(sbn.packageName, n.flags, sbn.isClearable, ex.getString(Notification.EXTRA_TEMPLATE), n.category, title, text)
        if (!NotificationsLogic.accept(raw, ctx.packageName)) return null

        val appInfo = appInfo(ctx, sbn.packageName, ex)
        val label = appInfo?.let { runCatching { ctx.packageManager.getApplicationLabel(it).toString() }.getOrNull() } ?: sbn.packageName
        val msg = if (ex.getString(Notification.EXTRA_TEMPLATE)?.endsWith("\$MessagingStyle") == true) lastMessage(ex) else null
        val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val (who, body) = NotificationsLogic.compose(label, title, text, msg?.text, msg?.sender, conversation)

        // личный чат - лицо собеседника, группа и обычное - картинка уведомления
        val large = n.getLargeIcon()
        val avatarIcon = if (conversation != null) large ?: msg?.icon else msg?.icon ?: large
        val avatar = avatarIcon?.let { load(ctx, it, AVATAR_PX) }
        val appIcon = appIcons[sbn.packageName] ?: appInfo?.let { info ->
            runCatching { ctx.packageManager.getApplicationIcon(info) }.getOrNull()?.let { draw(it, AVATAR_PX) }
        }?.also { appIcons[sbn.packageName] = it }

        val entry = NotifEntry(sbn.key, sbn.packageName, who, body, sbn.postTime, importance)
        return NotifItem(entry, label, avatar, appIcon)
    }

    private class Msg(val text: String, val sender: String?, val icon: Icon?)

    /** Последнее сообщение MessagingStyle: ключи пакетов сообщений платформы text, time, sender, sender_person. */
    @Suppress("DEPRECATION")
    private fun lastMessage(ex: Bundle): Msg? {
        val list = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = list.filterIsInstance<Bundle>().maxByOrNull { it.getLong("time") } ?: return null
        val text = last.getCharSequence("text")?.toString() ?: return null
        val person: android.app.Person? = last.getParcelable<Parcelable>("sender_person") as? android.app.Person
        val sender = person?.name?.toString() ?: last.getCharSequence("sender")?.toString()
        return Msg(text, sender, person?.icon)
    }

    @Suppress("DEPRECATION")
    private fun appInfo(ctx: Context, pkg: String, ex: Bundle): ApplicationInfo? =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
            // пакет может быть не виден нам (package visibility): само уведомление несёт ApplicationInfo отправителя
            ?: runCatching { ex.getParcelable<ApplicationInfo>("android.appInfo") }.getOrNull()

    private fun load(ctx: Context, icon: Icon, px: Int): ImageBitmap? =
        try { icon.loadDrawable(ctx)?.let { draw(it, px) } } catch (e: Exception) { null }

    /** Квадрат [px] x [px]; растровую картинку не растягиваем, а обрезаем по центру. */
    private fun draw(d: Drawable, px: Int): ImageBitmap? {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val src = (d as? BitmapDrawable)?.bitmap
        if (src != null && src.width > 0 && src.height > 0) {
            val side = minOf(src.width, src.height)
            val from = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
            canvas.drawBitmap(src, from, Rect(0, 0, px, px), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            d.setBounds(0, 0, px, px)
            d.draw(canvas)
        }
        return bmp.asImageBitmap()
    }

    companion object {
        /** Квадрат аватара и значка в пикселях: больше самого крупного места (язычок 72 px на 1280x720, с запасом на 2240x976). */
        private const val AVATAR_PX = 128
        @Volatile private var instance: HeadswapNotificationListener? = null
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "headswap-notify-io").apply { isDaemon = true } }
        private val appIcons = ConcurrentHashMap<String, ImageBitmap>()

        /** Снять уведомления на телефоне; слушатель отключён - молча ничего (локально запись уже убрана). */
        fun cancel(keys: List<String>) {
            val svc = instance ?: return
            io.execute { for (k in keys) runCatching { svc.cancelNotification(k) } }
        }

        /** Тумблер включили заново: перечитать то, что висит сейчас. */
        fun resync() { instance?.sync() }
    }
}
