package com.yegorkapomidorka.headswap.launcher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Уведомление телефона для экрана ГУ, без Android-типов и картинок (аватары и значки держит [NotifItem]).
 * В логи не попадает ничего, кроме пакета и количества: текст и отправители - личные данные пользователя.
 */
data class NotifEntry(
    val key: String,
    val pkg: String,
    /** Кто: отправитель, название чата или заголовок. */
    val who: String,
    val text: String,
    val postTime: Long,
    /** Важность канала (NotificationManager.IMPORTANCE_*). */
    val importance: Int,
)

/** То, что нужно фильтру от StatusBarNotification; поля и флаги платформы разобраны в слушателе. */
data class NotifRaw(
    val pkg: String,
    val flags: Int,
    val clearable: Boolean,
    /** Notification.EXTRA_TEMPLATE. */
    val template: String?,
    val category: String?,
    val title: String,
    val text: String,
)

object NotificationsLogic {
    // Notification.FLAG_*
    const val FLAG_ONGOING = 0x2
    const val FLAG_NO_CLEAR = 0x20
    const val FLAG_GROUP_SUMMARY = 0x200
    /** NotificationManager.IMPORTANCE_DEFAULT: с неё уведомление звучит, и язычок имеет смысл. */
    const val IMPORTANCE_DEFAULT = 3
    const val CATEGORY_CALL = "call"
    private val MEDIA_TEMPLATES = setOf(
        "android.app.Notification\$MediaStyle",
        "android.app.Notification\$DecoratedMediaCustomViewStyle",
    )

    /** Список ограничен: у каждой записи свои картинки. */
    const val MAX_ITEMS = 50
    private const val BASE_MS = 4000L
    private const val PER_CHUNK_MS = 1000L
    private const val CHUNK = 40
    private const val MAX_MS = 10_000L

    /** Показывать ли уведомление в списке: пропускаем постоянные, неубираемые, сводки групп, плееры, звонки, свои и пустые. */
    fun accept(n: NotifRaw, ownPackage: String): Boolean {
        if (n.pkg == ownPackage) return false
        if (n.flags and (FLAG_ONGOING or FLAG_NO_CLEAR or FLAG_GROUP_SUMMARY) != 0) return false
        if (!n.clearable) return false
        if (n.template in MEDIA_TEMPLATES) return false
        if (n.category == CATEGORY_CALL) return false
        return n.title.isNotBlank() || n.text.isNotBlank()
    }

    /**
     * Что показать строкой: [who] - кто, [text] - о чём. MessagingStyle: личный чат - отправитель и его сообщение,
     * группа - название чата и «отправитель: сообщение». Обычное: заголовок и текст (если текста нет, текстом
     * становится заголовок, а «кто» - название приложения).
     */
    fun compose(
        appLabel: String, title: String, text: String,
        messagingText: String?, messagingSender: String?, conversationTitle: String?,
    ): Pair<String, String> {
        if (!messagingText.isNullOrBlank()) {
            val sender = messagingSender?.trim().orEmpty()
            val chat = conversationTitle?.trim().orEmpty()
            return if (chat.isNotEmpty()) {
                chat to (if (sender.isNotEmpty()) "$sender: $messagingText" else messagingText)
            } else {
                (sender.ifEmpty { title.trim().ifEmpty { appLabel } }) to messagingText
            }
        }
        val t = title.trim()
        val body = text.trim()
        if (body.isEmpty()) return appLabel to t
        return t.ifEmpty { appLabel } to body
    }

    /**
     * Выезжает ли язычок. [initial] - первичное заполнение (подключение слушателя, переподключение): старые
     * уведомления не показываем. Пока открыта шторка, язычка нет; и пока лаунчера нет на экране ГУ ([onScreen]):
     * таймер язычка живёт в оверлее, без него утреннее сообщение выехало бы вечером при подключении. Новый ключ
     * или изменившийся текст - да.
     */
    fun shouldHeadsUp(prev: NotifEntry?, next: NotifEntry, initial: Boolean, shadeOpen: Boolean, onScreen: Boolean = true): Boolean {
        if (initial || shadeOpen || !onScreen) return false
        if (next.importance < IMPORTANCE_DEFAULT) return false
        return prev == null || prev.text != next.text
    }

    /** Сколько держать язычок: 4 с и по секунде на каждые 40 символов, не больше 10 с. */
    fun showMs(text: String): Long = min(MAX_MS, BASE_MS + PER_CHUNK_MS * (text.length / CHUNK))

    /** Новые сверху; при равном времени - по ключу, чтобы порядок не прыгал. */
    fun sorted(items: List<NotifEntry>): List<NotifEntry> =
        items.sortedWith(compareByDescending<NotifEntry> { it.postTime }.thenBy { it.key })

    /** Список после прихода [item]: заменяет запись с тем же ключом, держит порядок по времени и предел [MAX_ITEMS]. */
    fun upsert(list: List<NotifEntry>, item: NotifEntry): List<NotifEntry> =
        sorted(list.filter { it.key != item.key } + item).take(MAX_ITEMS)

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** Размеры язычка и шторки. Макет нарисован на 1280x720 (T 96); остальное пропорционально T. */
object NotifGeom {
    /** Язычок по ширине текста: от 480 до 900 при T 96, но не шире области за вычетом «ушей» и полей. */
    fun tongueWidth(t: Int, areaW: Int, ear: Int, margin: Int): Pair<Int, Int> {
        val room = max(1, areaW - 2 * ear - 2 * margin)
        // на широких экранах 900T/96 - едва половина области; там не уже 60% области (Егор 08.11, 1920x836)
        val maxW = min(max(Math.round(900 * t / 96f), Math.round(areaW * 0.6f)), room)
        val minW = min(Math.round(480 * t / 96f), maxW)
        return minW to maxW
    }

    /** Список в шторке: не выше 75% высоты экрана за вычетом верха шторки и нижней кромки с полоской. */
    fun listMaxHeight(screenH: Int, top: Int, footer: Int, pad: Int): Int =
        max(0, Math.round(screenH * 0.75f) - top - footer - pad)

    /**
     * Стык большой плитки с соседней: слева от неё, если там есть место, иначе справа; null - плитка на всю область
     * (одна плитка на всю ширину), стыка нет. Место - больше зазора [g] между плиткой и краем области.
     */
    fun seamX(bigLeft: Float, bigRight: Float, areaLeft: Float, areaRight: Float, g: Float): Float? = when {
        bigLeft - areaLeft > g -> bigLeft - g / 2f
        areaRight - bigRight > g -> bigRight + g / 2f
        else -> null
    }

    /**
     * Полоска на строке статуса не лезет на текст (Vezel 08.11: с большой плиткой часов стык плиток ушёл под дату).
     * Центр [x] полоски шириной 2*[half]; если она задевает занятый отрезок [busy] (с зазором [gap]), отодвигается
     * к той его стороне, где больше места в [minX]..[maxX]. Свободное место между отрезками ищется по очереди.
     */
    fun lineAvoiding(x: Float, half: Float, gap: Float, busy: List<ClosedFloatingPointRange<Float>>, minX: Float, maxX: Float): Float {
        var cx = x
        repeat(busy.size) {
            val hit = busy.firstOrNull { cx + half + gap > it.start && cx - half - gap < it.endInclusive } ?: return cx
            val leftRoom = hit.start - minX
            val rightRoom = maxX - hit.endInclusive
            cx = if (leftRoom >= rightRoom) hit.start - gap - half else hit.endInclusive + gap + half
        }
        return cx
    }
}
