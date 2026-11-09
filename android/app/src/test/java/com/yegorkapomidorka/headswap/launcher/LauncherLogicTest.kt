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
        // n приложений: «+» последним
        assertEquals(QuickSlot.Plus, quickSlots(apps.take(5), 5)[5])
        // n+1 и больше: «+» пропадает, на его месте приложение, лишние в ряд не попадают
        val full = quickSlots(apps, 5)
        assertEquals(6, full.size)
        assertEquals(QuickSlot.App(apps[5]), full[5])
        assertFalse(QuickSlot.Plus in full)
    }

    @Test fun netLabels() {
        assertEquals("5G", NetLabel.cellular(20))
        assertEquals("4G", NetLabel.cellular(13))
        assertEquals("3G", NetLabel.cellular(3))
        assertEquals("2G", NetLabel.cellular(1))
        assertEquals("4G", NetLabel.cellular(null))
    }

    @Test fun cardViewRestoresOldPrefs() {
        assertEquals(CardView.NAVIGATOR, CardView.restore("junk"))
        assertEquals(CardView.CLOCK, CardView.restore("CLOCK"))
        assertEquals(CardView.MUSIC, CardView.restore(null, CardView.MUSIC))
        assertEquals(HomeLayout.QUAD, HomeLayout.restore(null))
        assertEquals(HomeLayout.WIDE_RIGHT, HomeLayout.restore("WIDE_RIGHT"))
    }


    @Test fun smallTileIsCappedOnWideScreens() {
        val wide = LauncherSpecs.forSize(1920, 720)
        assertTrue(wide.tileSmall < wide.narrow)
        assertEquals(Math.round(wide.t * 4.4).toInt(), wide.tileSmall)
        val r720 = LauncherSpecs.forSize(1280, 720)
        assertEquals(r720.narrow, r720.tileSmall)
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

    @Test fun backNeverBlocksTouchesForLongAndGivesUpBeforeTwoSeconds() {
        // касания: не дольше секунды после Back, даже если состояние так и не узнали
        assertTrue(TaskCheck.blockedAfterBack(1_000, 900))
        assertTrue(TaskCheck.blockedAfterBack(1_899, 900))
        assertFalse(TaskCheck.blockedAfterBack(1_900, 900))
        assertFalse(TaskCheck.blockedAfterBack(5_000, 900))
        assertFalse(TaskCheck.blockedAfterBack(1_000, 0)) // Back не было / проверка закончилась
        assertTrue(TaskCheck.BACK_BLOCK_MS <= 1_000)
        // решение при неизвестном состоянии - на последней проверке, не позже 1,5 с от Back
        assertEquals(1_500L, TaskCheck.BACK_CHECKS.sum())
        assertTrue(TaskCheck.BACK_CHECKS.sum() > TaskCheck.BACK_BLOCK_MS)
        // -1 на всех проверках кончается UNKNOWN: EmbedHost считает приложение ушедшим и возвращает на главный
        var result = TaskCheck.afterBack(-1, last = false)
        for (i in 1 until TaskCheck.BACK_CHECKS.size) {
            assertEquals(TaskCheck.AfterBack.UNKNOWN_MORE, result)
            result = TaskCheck.afterBack(-1, last = i == TaskCheck.BACK_CHECKS.size - 1)
        }
        assertEquals(TaskCheck.AfterBack.UNKNOWN, result)
    }

    @Test fun backGoesToPreviousLauncherScreenOrHome() {
        val screens = listOf(Area.AllApps, Area.Picker, Area.Settings, Area.Calls, Area.Players)
        for (a in screens) {
            assertTrue(BackNav.enabled(a))
            assertEquals(Area.Home, BackNav.target(a, null))
            assertEquals(Area.Home, BackNav.target(a, Area.Home))
            assertEquals(a, BackNav.remembered(a))
        }
        // из выбора приложения, открытого из настроек, - обратно в настройки
        assertEquals(Area.Settings, BackNav.target(Area.Picker, Area.Settings))
        // себя же и встроенные окна не запоминаем: идём на главный
        assertEquals(Area.Home, BackNav.target(Area.Settings, Area.Settings))
        assertEquals(Area.Home, BackNav.target(Area.AllApps, Area.Navigator))
        assertNull(BackNav.remembered(Area.Home))
        assertNull(BackNav.remembered(Area.Navigator))
        assertNull(BackNav.remembered(Area.App(AppRef("a.b", "c"))))
    }

    @Test fun backInsideAboutStepsLicenseThenSystemThenLeaves() {
        // открыт текст лицензии -> список лицензий -> «Система» -> шагов внутри настроек больше нет
        assertEquals(SettingsPage.ABOUT to false, BackNav.settingsStep(SettingsPage.ABOUT, licenseOpen = true))
        assertEquals(SettingsPage.SYSTEM to false, BackNav.settingsStep(SettingsPage.ABOUT, licenseOpen = false))
        for (p in SettingsPage.entries.filter { it != SettingsPage.ABOUT && it != SettingsPage.COLORS }) {
            assertNull(BackNav.settingsStep(p, licenseOpen = false))
        }
    }

    @Test fun backFromColorsReturnsToLook() {
        assertEquals(SettingsPage.LOOK to false, BackNav.settingsStep(SettingsPage.COLORS, licenseOpen = false))
    }

    @Test fun railBackIsGreyOnlyOnHome() {
        assertFalse(BackNav.enabled(Area.Home))
        assertTrue(BackNav.enabled(Area.Navigator))
        assertTrue(BackNav.enabled(Area.App(AppRef("a.b", "c"))))
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

    @Test fun playerChipHasPhoneAndLastOnlinePlayer() {
        val none = PlayerChip.slots(emptySet(), null, null)
        assertEquals(listOf(PlayerChip.Slot(PlayerChip.Kind.PHONE, null, true)), none)
        // ничего не слушали: первый установленный онлайн-плеер
        val all = setOf(PlayerChip.YANDEX, PlayerChip.YT_MUSIC, PlayerChip.VK, "x")
        assertEquals(listOf(PlayerChip.Kind.PHONE, PlayerChip.Kind.YANDEX), PlayerChip.slots(all, null, null).map { it.kind })
        // второй слот подменяется тем, что слушали последним
        val vk = PlayerChip.slots(all, PlayerChip.VK, PlayerChip.VK)
        assertEquals(listOf(PlayerChip.Kind.PHONE, PlayerChip.Kind.VK), vk.map { it.kind })
        assertEquals(listOf(false, true), vk.map { it.selected })
        // последний удалён - снова первый установленный
        assertEquals(PlayerChip.YT_MUSIC, PlayerChip.slots(setOf(PlayerChip.YT_MUSIC), null, PlayerChip.VK)[1].pkg)
        // выбран другой плеер (не из чипа): подсвечена ни одна кнопка
        assertEquals(listOf(false, false), PlayerChip.slots(setOf(PlayerChip.YANDEX), "com.spotify", null).map { it.selected })
    }

    @Test fun playerChipSlotsFromSettings() {
        val all = setOf(PlayerChip.YANDEX, PlayerChip.YT_MUSIC, PlayerChip.VK, "com.aimp")
        // закреплённый второй слот важнее последнего услышанного
        assertEquals(PlayerChip.YT_MUSIC, PlayerChip.slots(all, null, PlayerChip.VK, fixedOnline = PlayerChip.YT_MUSIC)[1].pkg)
        // закреплённый удалён - снова последний
        assertEquals(PlayerChip.VK, PlayerChip.onlineSlot(all - PlayerChip.YT_MUSIC, PlayerChip.VK, PlayerChip.YT_MUSIC))
        // первый слот - свой плеер из настроек, выбран и когда играет он, и когда плеер не задан
        val local = PlayerChip.slots(all, "com.aimp", null, local = "com.aimp")
        assertEquals("com.aimp", local[0].pkg)
        assertTrue(local[0].selected)
        // во второй слот можно закрепить любой плеер, не только онлайн-сервис
        val aimp2 = PlayerChip.slots(all, null, PlayerChip.VK, fixedOnline = "com.aimp")[1]
        assertEquals(PlayerChip.Slot(PlayerChip.Kind.APP, "com.aimp", false), aimp2)
        // в первый - онлайн-сервис; второй тогда его не дублирует
        val ya = PlayerChip.slots(all, null, PlayerChip.YANDEX, local = PlayerChip.YANDEX)
        assertEquals(listOf(PlayerChip.YANDEX, PlayerChip.YT_MUSIC), ya.map { it.pkg })
        assertEquals(PlayerChip.YT_MUSIC, PlayerChip.slots(all, null, null, local = PlayerChip.YANDEX, fixedOnline = PlayerChip.YANDEX)[1].pkg)
        // списки настроек: найденные плееры плюс установленные онлайн; во втором онлайн первыми
        val players = PlayerChip.allPlayers(listOf("com.aimp", PlayerChip.VK), setOf(PlayerChip.YANDEX, PlayerChip.VK))
        assertEquals(listOf("com.aimp", PlayerChip.VK, PlayerChip.YANDEX), players)
        assertEquals(listOf(PlayerChip.YANDEX, PlayerChip.VK, "com.aimp"), PlayerChip.secondSlotPlayers(players))
    }

    @Test fun lastOnlineChangesOnlyOnOnlinePlayers() {
        assertEquals(PlayerChip.VK, PlayerChip.lastOnline(PlayerChip.YANDEX, PlayerChip.VK))
        assertEquals(PlayerChip.YANDEX, PlayerChip.lastOnline(PlayerChip.YANDEX, "com.spotify"))
        assertEquals(PlayerChip.YANDEX, PlayerChip.lastOnline(PlayerChip.YANDEX, null))
        assertEquals(null, PlayerChip.lastOnline(null, null))
    }

    @Test fun lastLocalKeptWhileOnlinePlays() {
        assertEquals("com.aimp", PlayerChip.lastLocal("com.aimp", PlayerChip.YANDEX))
        assertEquals("com.spotify", PlayerChip.lastLocal("com.aimp", "com.spotify"))
        assertEquals("com.aimp", PlayerChip.lastLocal("com.aimp", null))
        assertEquals(null, PlayerChip.lastLocal(null, PlayerChip.VK))
    }

    @Test fun settingsGridBordersMatchOnAllResolutions() {
        for (row in LauncherSpecs.TABLE) {
            val s = LauncherSpecs.forSize(row[0], row[1])
            val h = row[1] - s.g8 - s.statusH - s.g8 - s.m // высота сетки под статусом
            val gap = s.g8
            assertEquals(0, SettingsGrid.top(0, h, gap))
            assertEquals(h, SettingsGrid.bottom(SettingsGrid.ROWS - 1, h, gap))
            for (i in 0 until SettingsGrid.ROWS - 1) {
                // между рядами ровно зазор, ряды одной высоты +-1 px
                assertEquals(gap, SettingsGrid.top(i + 1, h, gap) - SettingsGrid.bottom(i, h, gap))
                val a = SettingsGrid.bottom(i, h, gap) - SettingsGrid.top(i, h, gap)
                val b = SettingsGrid.bottom(i + 1, h, gap) - SettingsGrid.top(i + 1, h, gap)
                assertTrue("${row[0]}x${row[1]}", kotlin.math.abs(a - b) <= 1)
            }
        }
    }

    @Test fun themeChoiceRestoresOldNamesAsDarkAndAutoFollowsHeadlights() {
        assertEquals(ThemeChoice.DARK, ThemeChoice.restore("GOLD_LIGHT"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.restore("GOLD"))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.restore("LIGHT"))
        assertFalse(ThemeChoice.light(ThemeChoice.AUTO, null)) // ГУ ещё ничего не прислала
        assertTrue(ThemeChoice.light(ThemeChoice.AUTO, 0)) // день
        assertFalse(ThemeChoice.light(ThemeChoice.AUTO, 1)) // ночь
        assertFalse(ThemeChoice.light(ThemeChoice.AUTO, 2))
        assertTrue(ThemeChoice.light(ThemeChoice.LIGHT, 1))
    }

    @Test fun fullscreenRailHidesAfterTwoReadsAndShowsOnDoubt() {
        val t = FullscreenApp.Tracker()
        assertFalse(t.update(1, 10_000))
        assertTrue(t.update(1, 10_500))
        // смена размера окна из-за спрятанной рейки: короткое «нет» не возвращает рейку
        t.resized(10_600)
        assertTrue(t.update(0, 11_000))
        assertTrue(t.update(-1, 11_500))
        assertTrue(t.update(1, 12_000))
        // выход кнопкой YouTube после успокоения - рейка сразу видна; неизвестно - тоже видна
        assertFalse(t.update(0, 12_500))
        assertTrue(FullscreenApp.watched(FullscreenApp.YOUTUBE))
        assertFalse(FullscreenApp.watched(PlayerChip.YT_MUSIC))
        val u = FullscreenApp.Tracker()
        u.update(1, 0); u.update(1, 500)
        assertFalse(u.update(-1, 5_000))
    }

    @Test fun hintsDefaultOffForAll() {
        for (pkg in NavigatorPick.KNOWN) assertFalse(pkg, NavigatorPick.hintsDefault(pkg))
    }

    @Test fun shadowAaFollowsNavigatorHints() {
        val hints = mapOf("com.google.android.apps.maps" to false, "ru.dublgis.dgismobile" to true)
        val of = { p: String -> hints[p] ?: true }
        assertFalse(NavigatorPick.shadowWanted(true, "com.google.android.apps.maps", of))
        assertTrue(NavigatorPick.shadowWanted(true, "ru.dublgis.dgismobile", of))
        assertTrue(NavigatorPick.shadowWanted(true, null, of))
        assertFalse(NavigatorPick.shadowWanted(false, "ru.dublgis.dgismobile", of))
    }

    @Test fun needsPlusOnlyYandex() {
        assertTrue(NavigatorPick.needsPlus("ru.yandex.yandexnavi"))
        assertTrue(NavigatorPick.needsPlus("ru.yandex.yandexmaps"))
        assertFalse(NavigatorPick.needsPlus("ru.dublgis.dgismobile"))
        assertFalse(NavigatorPick.needsPlus(null))
    }

    @Test fun doubleBackWithinWindow() {
        assertFalse(DoubleBack.isDouble(0L, 1000L))
        assertTrue(DoubleBack.isDouble(1000L, 2000L))
        assertTrue(DoubleBack.isDouble(1000L, 1000L + DoubleBack.WINDOW_MS))
        assertFalse(DoubleBack.isDouble(1000L, 1001L + DoubleBack.WINDOW_MS))
    }
}
