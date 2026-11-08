package com.yegorkapomidorka.headswap.launcher

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsLogicTest {
    private fun raw(
        pkg: String = "org.telegram.messenger", flags: Int = 0, clearable: Boolean = true, template: String? = null,
        category: String? = null, title: String = "Ольга", text: String = "Привет",
    ) = NotifRaw(pkg, flags, clearable, template, category, title, text)

    private fun entry(key: String = "k1", text: String = "Привет", t: Long = 100, importance: Int = 4) =
        NotifEntry(key, "org.telegram.messenger", "Ольга", text, t, importance)

    private fun accept(n: NotifRaw) = NotificationsLogic.accept(n, "com.yegorkapomidorka.headswap")

    @Test fun plainMessageIsAccepted() = assertTrue(accept(raw()))

    @Test fun ongoingNoClearAndSummaryAreSkipped() {
        assertFalse(accept(raw(flags = NotificationsLogic.FLAG_ONGOING)))
        assertFalse(accept(raw(flags = NotificationsLogic.FLAG_NO_CLEAR)))
        assertFalse(accept(raw(flags = NotificationsLogic.FLAG_GROUP_SUMMARY)))
        assertFalse(accept(raw(clearable = false)))
    }

    @Test fun mediaAndCallsAreSkipped() {
        assertFalse(accept(raw(template = "android.app.Notification\$MediaStyle")))
        assertFalse(accept(raw(template = "android.app.Notification\$DecoratedMediaCustomViewStyle")))
        assertFalse(accept(raw(category = "call")))
        assertTrue(accept(raw(template = "android.app.Notification\$MessagingStyle", category = "msg")))
    }

    @Test fun ownPackageAndEmptyAreSkipped() {
        assertFalse(accept(raw(pkg = "com.yegorkapomidorka.headswap")))
        assertFalse(accept(raw(title = " ", text = "")))
        assertTrue(accept(raw(title = "Звонок пропущен", text = "")))
    }

    @Test fun seamOfBigTile() {
        assertEquals(692f, NotifGeom.seamX(700f, 1260f, 120f, 1260f, 16f)!!, 0.01f) // карта справа - стык слева от неё
        assertEquals(708f, NotifGeom.seamX(120f, 700f, 120f, 1260f, 16f)!!, 0.01f)  // большая слева - справа от неё
        assertEquals(null, NotifGeom.seamX(120f, 1260f, 120f, 1260f, 16f))           // на всю ширину - стыка нет
    }

    @Test fun lineAvoidsStatusText() {
        val icons = 900f..1260f
        // стык левее даты - полоска не двигается
        assertEquals(700f, NotifGeom.lineAvoiding(700f, 48f, 16f, listOf(icons), 120f, 1280f), 0.01f)
        // стык под датой (большая плитка часов) - встаёт левее даты с зазором
        assertEquals(836f, NotifGeom.lineAvoiding(950f, 48f, 16f, listOf(icons), 120f, 1280f), 0.01f)
        // RTL: значки слева - отъезжает вправо
        assertEquals(484f, NotifGeom.lineAvoiding(400f, 48f, 16f, listOf(60f..420f), 0f, 1160f), 0.01f)
        // часы слева и значки справа: остаётся между ними
        assertEquals(836f, NotifGeom.lineAvoiding(950f, 48f, 16f, listOf(130f..220f, icons), 120f, 1280f), 0.01f)
    }

    @Test fun composePlainUsesTitleAndText() {
        assertEquals("Т-Банк" to "Покупка 1 240 ₽", NotificationsLogic.compose("Т-Банк", "Т-Банк", "Покупка 1 240 ₽", null, null, null))
    }

    @Test fun composeTitleOnlyBecomesTextUnderAppName() {
        assertEquals("Почта" to "Новое письмо", NotificationsLogic.compose("Почта", "Новое письмо", "", null, null, null))
    }

    @Test fun composeMessagingPrivateChatShowsSenderAndText() {
        assertEquals("Ольга" to "Забери Киру", NotificationsLogic.compose("Telegram", "Ольга", "Забери Киру", "Забери Киру", "Ольга", null))
    }

    @Test fun composeMessagingGroupShowsChatAndSenderPrefix() {
        assertEquals("Ролики" to "Паша: завтра в 11",
            NotificationsLogic.compose("WhatsApp", "Ролики", "", "завтра в 11", "Паша", "Ролики"))
    }

    @Test fun composeMessagingWithoutSenderFallsBackToTitle() {
        assertEquals("Чат" to "текст", NotificationsLogic.compose("App", "Чат", "", "текст", null, null))
        assertEquals("App" to "текст", NotificationsLogic.compose("App", " ", "", "текст", null, null))
    }

    @Test fun newNotificationHeadsUp() {
        assertTrue(NotificationsLogic.shouldHeadsUp(null, entry(), initial = false, shadeOpen = false))
    }

    @Test fun changedTextHeadsUpSameTextDoesNot() {
        assertTrue(NotificationsLogic.shouldHeadsUp(entry(text = "a"), entry(text = "b"), initial = false, shadeOpen = false))
        assertFalse(NotificationsLogic.shouldHeadsUp(entry(text = "a"), entry(text = "a"), initial = false, shadeOpen = false))
    }

    @Test fun quietChannelDoesNotHeadsUp() {
        assertFalse(NotificationsLogic.shouldHeadsUp(null, entry(importance = 2), initial = false, shadeOpen = false))
        assertTrue(NotificationsLogic.shouldHeadsUp(null, entry(importance = 3), initial = false, shadeOpen = false))
    }

    @Test fun initialFillNeverHeadsUp() {
        assertFalse(NotificationsLogic.shouldHeadsUp(null, entry(), initial = true, shadeOpen = false))
    }

    @Test fun openShadeSuppressesHeadsUp() {
        assertFalse(NotificationsLogic.shouldHeadsUp(null, entry(), initial = false, shadeOpen = true))
    }

    @Test fun noHeadsUpWhileLauncherIsNotOnScreen() {
        assertFalse(NotificationsLogic.shouldHeadsUp(null, entry(), initial = false, shadeOpen = false, onScreen = false))
        assertTrue(NotificationsLogic.shouldHeadsUp(null, entry(), initial = false, shadeOpen = false, onScreen = true))
    }

    @Test fun showTimeGrowsByTextAndIsCapped() {
        assertEquals(4000L, NotificationsLogic.showMs(""))
        assertEquals(4000L, NotificationsLogic.showMs("x".repeat(39)))
        assertEquals(5000L, NotificationsLogic.showMs("x".repeat(40)))
        assertEquals(7000L, NotificationsLogic.showMs("x".repeat(125)))
        assertEquals(10_000L, NotificationsLogic.showMs("x".repeat(240)))
        assertEquals(10_000L, NotificationsLogic.showMs("x".repeat(5000)))
    }

    @Test fun newestFirstThenByKey() {
        val sorted = NotificationsLogic.sorted(listOf(entry("b", t = 5), entry("c", t = 9), entry("a", t = 5)))
        assertEquals(listOf("c", "a", "b"), sorted.map { it.key })
    }

    @Test fun upsertReplacesSameKeyAndReorders() {
        val list = listOf(entry("a", t = 9), entry("b", t = 5))
        val next = NotificationsLogic.upsert(list, entry("b", text = "новый", t = 12))
        assertEquals(listOf("b", "a"), next.map { it.key })
        assertEquals("новый", next.first().text)
        assertEquals(2, next.size)
    }

    @Test fun upsertKeepsLimitDroppingOldest() {
        var list = emptyList<NotifEntry>()
        for (i in 1..NotificationsLogic.MAX_ITEMS + 5) list = NotificationsLogic.upsert(list, entry("k$i", t = i.toLong()))
        assertEquals(NotificationsLogic.MAX_ITEMS, list.size)
        assertEquals("k${NotificationsLogic.MAX_ITEMS + 5}", list.first().key)
        assertFalse(list.any { it.key == "k1" })
    }

    @Test fun timeLabelIsHoursMinutes() {
        assertEquals("09:35", NotificationsLogic.time(9 * 3600_000L + 35 * 60_000L, ZoneId.of("UTC")))
    }

    @Test fun tongueWidthScalesWithT() {
        // 1280x720: T 96, область справа от рейки 1136, «ухо» 32
        assertEquals(480 to 900, NotifGeom.tongueWidth(96, 1136, 32, 20))
        // 800x480: T 62 - пропорционально
        val (min, max) = NotifGeom.tongueWidth(62, 707, 21, 14)
        assertEquals(310, min)
        assertEquals(581, max)
    }

    @Test fun tongueWidthWiderOnWideArea() {
        // 1920x836: область ~1700, 900T/96 вышло бы ~880
        assertEquals(1020, NotifGeom.tongueWidth(94, 1700, 32, 20).second)
    }

    @Test fun tongueWidthNeverExceedsArea() {
        val (min, max) = NotifGeom.tongueWidth(96, 600, 32, 20)
        assertEquals(496, max)
        assertTrue(min <= max)
    }

    @Test fun listCapIsThreeQuartersOfScreen() {
        assertEquals(540 - 57 - 96 - 8, NotifGeom.listMaxHeight(720, 57, 96, 8))
        assertEquals(0, NotifGeom.listMaxHeight(100, 57, 96, 8))
    }
}
