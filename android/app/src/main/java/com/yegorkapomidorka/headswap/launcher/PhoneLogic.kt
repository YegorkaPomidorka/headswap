package com.yegorkapomidorka.headswap.launcher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CallType {
    INCOMING, OUTGOING, MISSED, OTHER;

    companion object {
        /** CallLog.Calls.TYPE: 1 входящий, 2 исходящий, 3 пропущенный, 5 отклонённый (для ГУ - входящий). */
        fun fromLog(type: Int): CallType = when (type) {
            1, 5 -> INCOMING
            2 -> OUTGOING
            3 -> MISSED
            else -> OTHER
        }
    }
}

data class CallRecord(val number: String, val name: String?, val type: CallType, val timeMs: Long) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: number
}

data class ContactRecord(val id: Long, val name: String, val number: String)

/** Состояние звонка по TelephonyManager: CALL_STATE_IDLE/RINGING/OFFHOOK. */
object CallState { const val IDLE = 0; const val RINGING = 1; const val OFFHOOK = 2 }

/** Что показывает карточка «Телефон» (PhoneStates.dc.html, состояния А-Ж). */
sealed interface PhoneCardModel {
    /** А: есть пропущенные. */
    data class Missed(val count: Int, val last: CallRecord) : PhoneCardModel
    /** Б: пропущенных нет, есть последний вызов. */
    data class LastCall(val last: CallRecord) : PhoneCardModel
    /** В: звонков нет. */
    data object Empty : PhoneCardModel
    /** Г: нет доступа к журналу звонков - кнопка открывает запрос разрешений на телефоне. */
    data object NoAccess : PhoneCardModel
    /** Д: входящий. */
    data class Incoming(val title: String) : PhoneCardModel
    /** Е: разговор. */
    data class InCall(val title: String, val elapsedSec: Int) : PhoneCardModel
    /** Ж: исходящий, ещё не ответили. */
    data class Outgoing(val title: String) : PhoneCardModel
}

object PhoneLogic {
    /** Исходящий без ответа показываем первые секунды: состояния «ответили» TelephonyManager не отдаёт. */
    const val OUTGOING_PHASE_SEC = 5

    /**
     * @param callState CallState.*
     * @param callTitle имя или номер текущего звонка (может быть пустым, если неизвестен)
     * @param ringed в этом звонке было RINGING (значит это входящий, а OFFHOOK после него - разговор)
     * @param offhookSec секунд с начала OFFHOOK
     * @param calls недавние вызовы, новые первыми
     * @param missedSeenMs пропущенные не новее этого момента уже просмотрены (тап по карточке)
     */
    fun model(
        permitted: Boolean, callState: Int, callTitle: String, ringed: Boolean, offhookSec: Int,
        calls: List<CallRecord>, missedSeenMs: Long,
    ): PhoneCardModel {
        when (callState) {
            CallState.RINGING -> return PhoneCardModel.Incoming(callTitle)
            CallState.OFFHOOK -> return if (ringed || offhookSec >= OUTGOING_PHASE_SEC)
                PhoneCardModel.InCall(callTitle, if (ringed) offhookSec else offhookSec - OUTGOING_PHASE_SEC)
            else PhoneCardModel.Outgoing(callTitle)
        }
        if (!permitted) return PhoneCardModel.NoAccess
        val missed = calls.filter { it.type == CallType.MISSED && it.timeMs > missedSeenMs }
        if (missed.isNotEmpty()) return PhoneCardModel.Missed(missed.size, missed.first())
        val last = calls.firstOrNull() ?: return PhoneCardModel.Empty
        return PhoneCardModel.LastCall(last)
    }

    /** «14:02» сегодня, «вчера», иначе «31.10». */
    fun whenLabel(nowMs: Long, thenMs: Long, zone: ZoneId = ZoneId.systemDefault(), yesterday: String = "вчера"): String {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val then = Instant.ofEpochMilli(thenMs).atZone(zone)
        return when (then.toLocalDate()) {
            now -> then.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
            now.minusDays(1) -> yesterday
            else -> then.format(DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT))
        }
    }

    fun timer(sec: Int): String = "%02d:%02d".format(Locale.ROOT, sec / 60, sec % 60)
}

/** Лайк в MediaSession: custom action с именем или иконкой «нравится» (Яндекс Музыка, Spotify, YT Music). */
object MusicLogic {
    data class Action(val id: String, val name: String)

    private val LIKE = listOf("like", "love", "heart", "favorite", "favourite", "thumb", "нрав", "люб", "избран", "أعجبني", "مفضلة")
    private val UNLIKE = listOf("unlike", "unfavorite", "unfavourite", "remove", "убрать", "удалить", "إزالة", "حذف")
    private val DISLIKE = listOf("dislike", "дизлайк", "не нрав", "لم يعجبني")

    /** Действие «нравится» среди custom actions, если плеер его отдаёт. */
    fun findLike(actions: List<Action>): Action? = actions.firstOrNull { isLike(it) }

    fun isLike(a: Action): Boolean {
        val s = "${a.id} ${a.name}".lowercase()
        if (DISLIKE.any { it in s }) return false
        return LIKE.any { it in s }
    }

    /** Действие уже включено (сердечко закрашено): по имени вида unlike / «убрать из избранного». */
    fun isLiked(a: Action): Boolean {
        val s = "${a.id} ${a.name}".lowercase()
        return UNLIKE.any { it in s }
    }

    /** Подбор сессии: играющая (выбранный плеер среди играющих первым), иначе выбранный плеер, иначе первая. */
    fun <T> pickSession(sessions: List<T>, pkgOf: (T) -> String, playing: (T) -> Boolean, preferred: String?): T? {
        val live = sessions.filter(playing)
        return live.firstOrNull { pkgOf(it) == preferred } ?: live.firstOrNull()
            ?: sessions.firstOrNull { pkgOf(it) == preferred }
            ?: sessions.firstOrNull()
    }
}
