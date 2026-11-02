package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLogicTest {
    @Test fun pagerCountsAndClamps() {
        assertEquals(1, Pager.pageCount(0, 8))
        assertEquals(1, Pager.pageCount(8, 8))
        assertEquals(2, Pager.pageCount(9, 8))
        assertEquals(1, Pager.clamp(5, 9, 8))
        assertEquals(0, Pager.clamp(-3, 9, 8))
    }

    @Test fun pagerSlices() {
        val items = (1..9).toList()
        assertEquals((1..8).toList(), Pager.slice(items, 0, 8))
        assertEquals(listOf(9), Pager.slice(items, 1, 8))
        assertEquals(listOf(9), Pager.slice(items, 7, 8))
        assertEquals(emptyList<Int>(), Pager.slice(emptyList<Int>(), 0, 8))
    }

    @Test fun gridPlanFitsAtLeastOneCell() {
        assertEquals(GridPlan.Plan(1, 1), GridPlan.of(10, 10, 100, 100, 10))
        assertEquals(GridPlan.Plan(4, 2), GridPlan.of(1000, 500, 220, 230, 20))
        assertEquals(8, GridPlan.of(1000, 500, 220, 230, 20).perPage)
    }

    @Test fun longPressNeedsSixHundredMsBetweenDownAndUp() {
        assertEquals(Press.TAP, PressLogic.classify(1000, 1599, true))
        assertEquals(Press.LONG, PressLogic.classify(1000, 1600, true))
        assertEquals(Press.TAP, PressLogic.classify(1000, 5000, false))
    }

    @Test fun debounceDropsSecondTapWithin400ms() {
        val d = Debounce()
        assertTrue(d.allow(10_000))
        assertFalse(d.allow(10_399))
        assertTrue(d.allow(10_400))
        assertFalse(d.allow(10_500))
    }

    @Test fun debounceFirstTapAtZeroIsAllowed() {
        assertTrue(Debounce().allow(0))
    }

    @Test fun launchLockExpiresAfterEightSeconds() {
        val l = LaunchLock()
        assertFalse(l.isLocked(0))
        l.begin("a", 1000)
        assertTrue(l.isLocked(8999))
        assertEquals("a", l.key)
        assertFalse(l.isLocked(9000))
        assertNull(l.key)
        l.begin("b", 0)
        l.finish()
        assertFalse(l.isLocked(1))
    }

    @Test fun appListKeepsOrderWithoutDuplicates() {
        val a = AppRef("a.b", "a.b.Main")
        val b = AppRef("c.d", "c.d.Main")
        var list = AppList.add(AppList.add(emptyList(), a), b)
        list = AppList.add(list, a)
        assertEquals(listOf(a, b), list)
        assertEquals(listOf(b), AppList.remove(list, a))
        assertEquals(listOf(a), AppList.quick(list, 1))
    }

    @Test fun allAppsIsWholeCatalogInItsOrder() {
        val c = (1..30).map { AppRef("p$it", "p$it.Main") }
        assertEquals(c, CatalogLists.all(c))
        assertEquals(c.take(2), CatalogLists.all(c.take(2) + c.take(2))) // дубли компонента не показываются дважды
        assertEquals(emptyList<AppRef>(), CatalogLists.all(emptyList()))
    }

    @Test fun pickerOffersCatalogWithoutFavorites() {
        val c = (1..6).map { AppRef("p$it", "p$it.Main") }
        val favorites = listOf(c[1], c[4], AppRef("gone", "gone.Main"))
        assertEquals(listOf(c[0], c[2], c[3], c[5]), CatalogLists.pickable(c, favorites))
        assertEquals(c, CatalogLists.pickable(c, emptyList()))
        assertEquals(emptyList<AppRef>(), CatalogLists.pickable(c, c))
    }

    @Test fun allAppsPagesCoverCatalogOnce() {
        val c = (1..30).map { AppRef("p$it", "p$it.Main") }
        val perPage = 8
        val pages = Pager.pageCount(c.size, perPage)
        assertEquals(4, pages)
        val seen = (0 until pages).flatMap { Pager.slice(CatalogLists.all(c), it, perPage) }
        assertEquals(c, seen)
        // запомненная страница после удаления приложений не вылетает за конец
        assertEquals(1, Pager.clamp(Int.MAX_VALUE / 2, 9, perPage))
    }

    @Test fun appListRoundTripsAndSkipsGarbage() {
        val list = listOf(AppRef("a.b", "a.b.Main"), AppRef("c.d", "c.d.Sub\$Inner"))
        assertEquals(list, AppList.decode(AppList.encode(list)))
        assertEquals(emptyList<AppRef>(), AppList.decode(null))
        assertEquals(listOf(AppRef("x.y", "x.y.Z")), AppList.decode("garbage\n/nope\nx.y/\nx.y/x.y.Z\nx.y/x.y.Z"))
    }

    @Test fun navigatorDefaultsToYandexThenFirstKnown() {
        assertEquals("ru.yandex.yandexnavi",
            NavigatorPick.defaultFor(setOf("com.waze", "ru.yandex.yandexnavi", "other")))
        assertEquals("ru.yandex.yandexmaps",
            NavigatorPick.defaultFor(setOf("com.waze", "ru.yandex.yandexmaps")))
        assertEquals("com.google.android.apps.maps",
            NavigatorPick.defaultFor(setOf("app.organicmaps", "com.google.android.apps.maps")))
        assertNull(NavigatorPick.defaultFor(setOf("other")))
    }

    @Test fun savedNavigatorWinsOnlyWhileInstalled() {
        val installed = setOf("com.waze", "ru.yandex.yandexnavi")
        assertEquals("com.waze", NavigatorPick.effective("com.waze", installed))
        assertEquals("ru.yandex.yandexnavi", NavigatorPick.effective("gone.app", installed))
        assertNull(NavigatorPick.effective("x", emptySet()))
    }

    @Test fun quickSlotsPutPlusRightAfterLastApp() {
        val apps = (1..7).map { AppRef("p$it", "p$it.Main") }
        assertEquals(listOf<QuickSlot>(QuickSlot.Plus, QuickSlot.Empty, QuickSlot.Empty, QuickSlot.Empty, QuickSlot.Empty, QuickSlot.Empty),
            quickSlots(emptyList(), 5))
        assertEquals(listOf(QuickSlot.App(apps[0]), QuickSlot.App(apps[1]), QuickSlot.Plus),
            quickSlots(apps.take(2), 2).take(3))
        // больше пяти: плюс закреплён шестым, лишние в быстрый ряд не попадают
        val full = quickSlots(apps, 5)
        assertEquals(6, full.size)
        assertEquals(QuickSlot.App(apps[4]), full[4])
        assertEquals(QuickSlot.Plus, full[5])
    }

    @Test fun netLabels() {
        assertEquals("5G", NetLabel.cellular(20))
        assertEquals("4G", NetLabel.cellular(13))
        assertEquals("3G", NetLabel.cellular(3))
        assertEquals("2G", NetLabel.cellular(1))
        assertEquals("4G", NetLabel.cellular(null))
    }

    @Test fun cardViewCyclesAndRestores() {
        assertEquals(CardView.CLOCK, CardView.NAVIGATOR.next())
        assertEquals(CardView.NAVIGATOR, CardView.CLOCK.next())
        assertEquals(CardView.NAVIGATOR, CardView.restore("junk"))
        assertEquals(CardView.CLOCK, CardView.restore("CLOCK"))
    }

    @Test fun afterBackClosesWhenTaskIsGoneOrInBackground() {
        assertEquals(TaskCheck.AfterBack.CLOSED, TaskCheck.afterBack(0, last = false))
        assertEquals(TaskCheck.AfterBack.CLOSED, TaskCheck.afterBack(1, last = true))
        // moveTaskToBack: задача осталась первой, но не видна
        assertEquals(TaskCheck.AfterBack.CLOSED, TaskCheck.afterBack(TaskCheck.MOVED_TO_BACK, last = false))
    }

    @Test fun afterBackKeepsPollingWhileTopOrUnknown() {
        assertEquals(TaskCheck.AfterBack.STAYED_MORE, TaskCheck.afterBack(2, last = false))
        assertEquals(TaskCheck.AfterBack.STAYED, TaskCheck.afterBack(2, last = true))
        assertEquals(TaskCheck.AfterBack.UNKNOWN_MORE, TaskCheck.afterBack(-1, last = false))
        assertEquals(TaskCheck.AfterBack.UNKNOWN, TaskCheck.afterBack(-1, last = true))
    }

    @Test fun relaunchOnShowOnlyWhenTaskMissingOrStale() {
        assertTrue(TaskCheck.needsRelaunch(0, stale = false))
        assertTrue(TaskCheck.needsRelaunch(1, stale = false))
        assertFalse(TaskCheck.needsRelaunch(2, stale = true))
        assertTrue(TaskCheck.needsRelaunch(TaskCheck.MOVED_TO_BACK, stale = false))
        // -1 без Back с неизвестным итогом не мигает «Запускаем…» при каждом показе
        assertFalse(TaskCheck.needsRelaunch(-1, stale = false))
        assertTrue(TaskCheck.needsRelaunch(-1, stale = true))
    }

    @Test fun vanishedNeedsTopSeenBefore() {
        assertFalse(TaskCheck.vanished(0, seenTop = false)) // приложение ещё стартует
        assertTrue(TaskCheck.vanished(0, seenTop = true))
        assertFalse(TaskCheck.vanished(1, seenTop = true)) // диалог другого пакета в той же задаче - не закрытие
        assertFalse(TaskCheck.vanished(TaskCheck.MOVED_TO_BACK, seenTop = true))
        assertFalse(TaskCheck.vanished(-1, seenTop = true))
    }

    @Test fun playerChipAlwaysHasPhoneAndOnlyInstalledServices() {
        val none = PlayerChip.slots(emptySet(), null)
        assertEquals(listOf(PlayerChip.Slot(PlayerChip.Kind.PHONE, null, true)), none)
        val both = PlayerChip.slots(setOf(PlayerChip.YANDEX, PlayerChip.YT_MUSIC, "x"), PlayerChip.YT_MUSIC)
        assertEquals(listOf(PlayerChip.Kind.PHONE, PlayerChip.Kind.YANDEX, PlayerChip.Kind.YT_MUSIC), both.map { it.kind })
        assertEquals(listOf(false, false, true), both.map { it.selected })
        // выбран другой плеер (не из чипа): подсвечена ни одна кнопка
        assertEquals(listOf(false, false), PlayerChip.slots(setOf(PlayerChip.YANDEX), "com.spotify").map { it.selected })
    }
}
