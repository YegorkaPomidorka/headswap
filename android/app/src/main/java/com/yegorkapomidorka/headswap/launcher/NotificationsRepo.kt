package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.notify.HeadswapNotificationListener
import kotlin.math.max

/** Запись списка с картинками: [avatar] - отправитель (круг), [appIcon] - значок приложения. */
class NotifItem(
    val entry: NotifEntry,
    val appLabel: String,
    val avatar: ImageBitmap?,
    val appIcon: ImageBitmap?,
) {
    val key: String get() = entry.key
}

/** Язычок, который сейчас на экране; [seq] отличает одно появление от другого (по нему запускается таймер). */
class HeadsUp(val item: NotifItem, val seq: Int)

/**
 * Уведомления телефона для экрана ГУ. Пишет слушатель ([HeadswapNotificationListener]) и пользователь (UI), всё - с главного
 * потока. В лог идут только пакет и количество: ни текста, ни отправителей, ни ключей.
 */
internal object NotificationsRepo {
    /** Новые сверху. */
    var items by mutableStateOf<List<NotifItem>>(emptyList())
        private set
    var headsUp by mutableStateOf<HeadsUp?>(null)
        private set
    var shadeOpen by mutableStateOf(false)
        private set
    /** Строка шторки, раскрытая целиком; одна. */
    var expandedKey by mutableStateOf<String?>(null)
        private set

    /** «Уведомления» в настройках. Читает и слушатель, которому лаунчер мог ещё не успеть стартовать: [configure]. */
    @Volatile var enabled = true
        private set
    private var seq = 0
    /**
     * Сколько оверлеев уведомлений сейчас в композиции (экземпляры активности при пересоздании пересекаются, поэтому счётчик).
     * Слушатель привязан и без машины, а таймер язычка и шторка живут в оверлее: пока его нет, язычок не выпускаем,
     * а на уходе последнего сбрасываем язычок и шторку, чтобы старое не выехало при следующем подключении.
     */
    private var screens = 0

    fun screenShown() { screens++ }
    fun screenGone() {
        screens = max(0, screens - 1)
        if (screens == 0) { headsUp = null; closeShade() }
    }

    /** Включить или выключить приём; выключение стирает всё, что накопили. Главный поток. */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (on) HeadswapNotificationListener.resync()
        else { items = emptyList(); headsUp = null; shadeOpen = false; expandedKey = null }
    }

    /** Из слушателя, до того как лаунчер прочитал настройки. */
    fun configure(ctx: Context) { setEnabled(LauncherPrefs(ctx).notifications) }

    /** Первичное заполнение (подключение слушателя): без язычка. Записи, которых больше нет у системы, уходят. */
    fun sync(fresh: List<NotifItem>) {
        if (!enabled) return
        val byKey = fresh.associateBy { it.key }
        items = NotificationsLogic.sorted(fresh.map { it.entry }).take(NotificationsLogic.MAX_ITEMS).mapNotNull { byKey[it.key] }
        if (headsUp != null && items.none { it.key == headsUp?.item?.key }) headsUp = null
        if (expandedKey != null && items.none { it.key == expandedKey }) expandedKey = null
        if (items.isEmpty()) shadeOpen = false
        AppLog.i("NOTIFY список: ${items.size}", false)
    }

    /** Пришло или обновилось уведомление. */
    fun post(item: NotifItem) {
        if (!enabled) return
        val prev = items.firstOrNull { it.key == item.key }
        val byKey = (items + item).associateBy { it.key }
        items = NotificationsLogic.upsert(items.map { it.entry }, item.entry).mapNotNull { byKey[it.key] }
        if (NotificationsLogic.shouldHeadsUp(prev?.entry, item.entry, initial = false, shadeOpen = shadeOpen, onScreen = screens > 0)) {
            headsUp = HeadsUp(item, ++seq)
            AppLog.i("NOTIFY язычок: ${item.entry.pkg}", false)
        } else if (headsUp?.item?.key == item.key) {
            headsUp = HeadsUp(item, headsUp!!.seq) // тот же показ, свежее содержимое
        }
    }

    /** Система сняла уведомление (или мы сами через [dismiss]). */
    fun removed(key: String) {
        if (items.none { it.key == key }) return
        items = items.filter { it.key != key }
        if (headsUp?.item?.key == key) headsUp = null
        if (expandedKey == key) expandedKey = null
        if (items.isEmpty()) shadeOpen = false
    }

    /** × или смахивание: снять и на телефоне. */
    fun dismiss(key: String) {
        HeadswapNotificationListener.cancel(listOf(key))
        removed(key)
    }

    fun clearAll() {
        val keys = items.map { it.key }
        AppLog.i("NOTIFY очистить: ${keys.size}", false)
        HeadswapNotificationListener.cancel(keys)
        items = emptyList(); headsUp = null; expandedKey = null; shadeOpen = false
    }

    /** Таймер язычка вышел; чужой показ (уже сменился другим) не трогаем. */
    fun endHeadsUp(seq: Int) { if (headsUp?.seq == seq) headsUp = null }

    /** Открыть шторку; [expand] - строка, которую сразу раскрыть (тап по язычку). */
    fun openShade(expand: String? = null) {
        if (items.isEmpty()) return
        shadeOpen = true
        headsUp = null
        expandedKey = expand?.takeIf { k -> items.any { it.key == k } }
    }

    fun closeShade() { shadeOpen = false; expandedKey = null }

    fun toggleExpanded(key: String) { expandedKey = if (expandedKey == key) null else key }

    /** «Открыть в <Приложение>»: launcher-вход пакета из каталога лаунчера; нет входа - null и кнопки нет. */
    fun launchRef(pkg: String): AppRef? =
        LauncherModel.catalog.firstOrNull { it.packageName == pkg }?.let { AppRef(it.packageName, it.className) }

    fun open(item: NotifItem) {
        val ref = launchRef(item.entry.pkg) ?: return
        dismiss(item.key)
        closeShade()
        LauncherActions.openApp(ref)
    }
}
