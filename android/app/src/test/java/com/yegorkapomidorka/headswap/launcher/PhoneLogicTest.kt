package com.yegorkapomidorka.headswap.launcher

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLogicTest {
    private val utc = ZoneId.of("UTC")
    private fun rec(type: CallType, t: Long, name: String? = "Ольга") = CallRecord("+7900", name, type, t)

    private fun model(
        state: Int = CallState.IDLE, permitted: Boolean = true, calls: List<CallRecord> = emptyList(),
        seen: Long = 0, ringed: Boolean = false, offhook: Int = 0,
    ) = PhoneLogic.model(permitted, state, "Сергей", ringed, offhook, calls, seen)

    @Test fun noPermissionIsStateV() {
        assertEquals(PhoneCardModel.NoAccess, model(permitted = false, calls = listOf(rec(CallType.MISSED, 5))))
    }

    @Test fun noCallsIsStateVWithoutPermissionHint() {
        assertEquals(PhoneCardModel.Empty, model())
    }

    @Test fun missedCallsAreStateA() {
        val calls = listOf(rec(CallType.MISSED, 30), rec(CallType.INCOMING, 20), rec(CallType.MISSED, 10))
        val m = model(calls = calls) as PhoneCardModel.Missed
        assertEquals(2, m.count)
        assertEquals(30L, m.last.timeMs)
    }

    @Test fun seenMissedAreNotCounted() {
        val calls = listOf(rec(CallType.MISSED, 30), rec(CallType.MISSED, 10))
        assertEquals(1, (model(calls = calls, seen = 20) as PhoneCardModel.Missed).count)
        assertTrue(model(calls = calls, seen = 30) is PhoneCardModel.LastCall)
    }

    @Test fun lastCallIsStateB() {
        val m = model(calls = listOf(rec(CallType.OUTGOING, 9))) as PhoneCardModel.LastCall
        assertEquals(CallType.OUTGOING, m.last.type)
    }

    @Test fun ringingIsStateG() {
        assertEquals(PhoneCardModel.Incoming("Сергей"), model(state = CallState.RINGING))
        // звонок идёт и без разрешений на журнал
        assertEquals(PhoneCardModel.Incoming("Сергей"), model(state = CallState.RINGING, permitted = false))
    }

    @Test fun answeredIncomingIsStateD() {
        assertEquals(PhoneCardModel.InCall("Сергей", 12),
            model(state = CallState.OFFHOOK, ringed = true, offhook = 12))
    }

    @Test fun outgoingShowsStateEThenTimer() {
        assertEquals(PhoneCardModel.Outgoing("Сергей"), model(state = CallState.OFFHOOK, offhook = 2))
        assertEquals(PhoneCardModel.InCall("Сергей", 3), model(state = CallState.OFFHOOK, offhook = 8))
    }

    @Test fun callBarReplacesBottomRowOnlyDuringCall() {
        // в портрете звонок (входящий, разговор, исходящий) занимает нижний ряд, остальные состояния - карточка в ряду
        assertTrue(model(state = CallState.RINGING).ongoing)
        assertTrue(model(state = CallState.OFFHOOK, ringed = true, offhook = 12).ongoing)
        assertTrue(model(state = CallState.OFFHOOK, offhook = 2).ongoing)
        assertFalse(model(permitted = false).ongoing)
        assertFalse(model().ongoing)
        assertFalse(model(calls = listOf(rec(CallType.MISSED, 5))).ongoing)
        assertFalse(model(calls = listOf(rec(CallType.INCOMING, 5))).ongoing)
    }

    @Test fun whenLabelTodayYesterdayDate() {
        val now = 1_762_041_600_000L + 15 * 3600_000L // 2025-11-02 15:00 UTC
        assertEquals("14:02", PhoneLogic.whenLabel(now, now - 58 * 60_000, utc))
        assertEquals("вчера", PhoneLogic.whenLabel(now, now - 24 * 3600_000, utc))
        assertEquals("30.10", PhoneLogic.whenLabel(now, now - 3 * 24 * 3600_000, utc))
    }

    @Test fun callLogTypesMap() {
        assertEquals(CallType.INCOMING, CallType.fromLog(1))
        assertEquals(CallType.OUTGOING, CallType.fromLog(2))
        assertEquals(CallType.MISSED, CallType.fromLog(3))
        assertEquals(CallType.INCOMING, CallType.fromLog(5))
        assertEquals(CallType.OTHER, CallType.fromLog(4))
        assertEquals(CallType.OTHER, CallType.fromLog(6))
    }

    @Test fun timerFormat() {
        assertEquals("03:21", PhoneLogic.timer(201))
        assertEquals("00:00", PhoneLogic.timer(0))
    }

    @Test fun likeActionFoundByNameOrId() {
        val acts = listOf(MusicLogic.Action("shuffle", "Перемешать"), MusicLogic.Action("com.x.LIKE", "Нравится"))
        assertEquals("com.x.LIKE", MusicLogic.findLike(acts)?.id)
        assertEquals("heart", MusicLogic.findLike(listOf(MusicLogic.Action("heart", "")))?.id)
        assertEquals("fav", MusicLogic.findLike(listOf(MusicLogic.Action("fav", "В избранное")))?.id)
    }

    @Test fun dislikeIsNotLike() {
        assertNull(MusicLogic.findLike(listOf(MusicLogic.Action("dislike", "Dislike"))))
        assertNull(MusicLogic.findLike(listOf(MusicLogic.Action("repeat", "Повтор"))))
    }

    @Test fun arabicLikeActions() {
        assertEquals("a", MusicLogic.findLike(listOf(MusicLogic.Action("a", "أعجبني")))?.id)
        assertEquals("b", MusicLogic.findLike(listOf(MusicLogic.Action("b", "إضافة إلى المفضلة")))?.id)
        assertNull(MusicLogic.findLike(listOf(MusicLogic.Action("c", "لم يعجبني"))))
        assertTrue(MusicLogic.isLiked(MusicLogic.Action("d", "إزالة من المفضلة")))
        assertTrue(!MusicLogic.isLiked(MusicLogic.Action("e", "أعجبني")))
    }

    @Test fun frenchLikeActions() {
        assertEquals("a", MusicLogic.findLike(listOf(MusicLogic.Action("a", "J’aime")))?.id)
        assertEquals("b", MusicLogic.findLike(listOf(MusicLogic.Action("b", "Ajouter aux favoris")))?.id)
        assertNull(MusicLogic.findLike(listOf(MusicLogic.Action("c", "Je n'aime pas"))))
        assertTrue(MusicLogic.isLiked(MusicLogic.Action("d", "Retirer des favoris")))
        assertTrue(!MusicLogic.isLiked(MusicLogic.Action("e", "J'aime")))
    }

    @Test fun likedStateFromUnlikeName() {
        assertTrue(MusicLogic.isLiked(MusicLogic.Action("unlike", "Unlike")))
        assertTrue(!MusicLogic.isLiked(MusicLogic.Action("like", "Like")))
    }

    @Test fun trackProgress() {
        // пауза: позиция как есть
        assertEquals(0.5f, MusicLogic.progress(30_000, 0, 0f, false, 60_000, 99_999)!!, 1e-4f)
        // играет: +6 с с момента отметки
        assertEquals(0.6f, MusicLogic.progress(30_000, 1_000, 1f, true, 60_000, 7_000)!!, 1e-4f)
        // убежала за конец - упор в 1
        assertEquals(1f, MusicLogic.progress(59_000, 0, 1f, true, 60_000, 10_000)!!, 1e-4f)
        // нет длительности или позиции - полосы нет
        assertNull(MusicLogic.progress(30_000, 0, 1f, true, 0, 0))
        assertNull(MusicLogic.progress(-1, 0, 1f, true, 60_000, 0))
    }

    @Test fun sessionPickPrefersPlayingThenChosen() {
        data class S(val pkg: String, val playing: Boolean)
        val list = listOf(S("a", false), S("b", true), S("c", false))
        // играет b, выбран c: показываем играющий
        assertEquals("b", MusicLogic.pickSession(list, { it.pkg }, { it.playing }, "c")?.pkg)
        assertEquals("b", MusicLogic.pickSession(list, { it.pkg }, { it.playing }, "zzz")?.pkg)
        // среди играющих выбранный первым
        assertEquals("c", MusicLogic.pickSession(listOf(S("b", true), S("c", true)), { it.pkg }, { it.playing }, "c")?.pkg)
        // ничего не играет: выбранный
        assertEquals("c", MusicLogic.pickSession(list.map { it.copy(playing = false) }, { it.pkg }, { it.playing }, "c")?.pkg)
        assertEquals("a", MusicLogic.pickSession(list.map { it.copy(playing = false) }, { it.pkg }, { it.playing }, null)?.pkg)
        assertNull(MusicLogic.pickSession(emptyList<S>(), { it.pkg }, { it.playing }, "a"))
    }

    @Test fun chosenPlayerNotReplacedByIdleForeignSession() {
        data class S(val pkg: String, val playing: Boolean)
        val ya = "ru.yandex.music"
        // поездки 16-17.11: Я.Музыка не запущена, у Telegram и Teams сессии на паузе - карточка брала их
        val idle = listOf(S("org.telegram.messenger", false), S("com.microsoft.teams", false))
        assertNull(MusicLogic.pickSession(idle, { it.pkg }, { it.playing }, ya, chosenOnly = true))
        assertEquals("org.telegram.messenger", MusicLogic.pickSession(idle, { it.pkg }, { it.playing }, ya)?.pkg)
        // выбранный на паузе среди чужих на паузе - выбранный
        assertEquals(ya, MusicLogic.pickSession(idle + S(ya, false), { it.pkg }, { it.playing }, ya, chosenOnly = true)?.pkg)
        // чужая даже играет (голосовое) - карточка всё равно у выбранного
        assertEquals(ya,
            MusicLogic.pickSession(listOf(S("org.telegram.messenger", true), S(ya, false)), { it.pkg }, { it.playing }, ya, chosenOnly = true)?.pkg)
        assertNull(MusicLogic.pickSession(listOf(S("org.telegram.messenger", true)), { it.pkg }, { it.playing }, ya, chosenOnly = true))
        // выбрана Музыка Xiaomi - Яндекс её не подменяет
        assertEquals("com.miui.player",
            MusicLogic.pickSession(listOf(S(ya, true), S("com.miui.player", false)), { it.pkg }, { it.playing }, "com.miui.player", chosenOnly = true)?.pkg)
        // ничего не выбрано - чужие всё равно не берём
        assertNull(MusicLogic.pickSession(idle, { it.pkg }, { it.playing }, null, chosenOnly = true))
        // выбрана «нота» (Музыка Xiaomi), а на телефоне заиграла Я.Музыка со второго слота - карточка её
        val chip = setOf("com.miui.player", ya)
        assertEquals(ya, MusicLogic.pickSession(listOf(S("com.miui.player", false), S(ya, true)), { it.pkg }, { it.playing },
            "com.miui.player", chosenOnly = true, chip = chip)?.pkg)
        // играющий Telegram не с чипа - нет
        assertEquals("com.miui.player", MusicLogic.pickSession(listOf(S("com.miui.player", false), S("org.telegram.messenger", true)),
            { it.pkg }, { it.playing }, "com.miui.player", chosenOnly = true, chip = chip)?.pkg)
    }
}
