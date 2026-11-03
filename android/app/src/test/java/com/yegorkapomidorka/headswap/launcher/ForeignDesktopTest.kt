package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.launcher.ForeignDesktop.Failed
import com.yegorkapomidorka.headswap.launcher.ForeignDesktop.NavKey
import com.yegorkapomidorka.headswap.launcher.ForeignDesktop.NavStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignDesktopTest {
    private val cardash = AppRef("ie.claudius.cardash", "ie.claudius.cardash.HomeActivity")

    private fun entry(pkg: String, cls: String = "$pkg.Main", label: String = pkg) = DesktopAppCatalog.Entry(pkg, cls, label)

    @Test fun backGoesToTheWindowUnlessRecentsAreOpen() {
        assertEquals(NavStep.BACK_IN_WINDOW, ForeignDesktop.press(NavKey.BACK, recentsOpen = false))
        assertEquals(NavStep.CLOSE_RECENTS, ForeignDesktop.press(NavKey.BACK, recentsOpen = true))
    }

    @Test fun homeAlwaysRaisesTheLauncher() {
        assertEquals(NavStep.RAISE_LAUNCHER, ForeignDesktop.press(NavKey.HOME, recentsOpen = false))
        assertEquals(NavStep.RAISE_LAUNCHER, ForeignDesktop.press(NavKey.HOME, recentsOpen = true))
    }

    @Test fun recentsButtonTogglesTheOverlay() {
        assertEquals(NavStep.OPEN_RECENTS, ForeignDesktop.press(NavKey.RECENTS, recentsOpen = false))
        assertEquals(NavStep.CLOSE_RECENTS, ForeignDesktop.press(NavKey.RECENTS, recentsOpen = true))
    }

    @Test fun launcherIsUsedOnlyWhileUsableAndNotRolledBackOnThisDisplay() {
        assertEquals(cardash, ForeignDesktop.resolve(cardash.key, true, null, 5))
        assertNull("not chosen", ForeignDesktop.resolve(null, true, null, 5))
        assertNull("package removed or disabled", ForeignDesktop.resolve(cardash.key, false, null, 5))
        assertNull("garbage in prefs", ForeignDesktop.resolve("garbage", true, null, 5))
    }

    @Test fun rollbackLastsOneDisplayAndOneChoice() {
        val broken = ForeignDesktop.brokenKey(cardash, 5)
        assertNull("same launcher, same display", ForeignDesktop.resolve(cardash.key, true, broken, 5))
        assertEquals("new session, new display", cardash, ForeignDesktop.resolve(cardash.key, true, broken, 6))
        val other = AppRef("com.driver.deck", "com.driver.deck.Home")
        assertEquals("another launcher", other, ForeignDesktop.resolve(other.key, true, broken, 5))
    }

    @Test fun onlyALauncherThatNeverStartedTriggersTheRollback() {
        assertEquals(Failed.FALLBACK, ForeignDesktop.afterWindowFailed(trackedIsLauncher = true))
        assertEquals("an app opened from Recents failed: the launcher comes back", Failed.RAISE_LAUNCHER,
            ForeignDesktop.afterWindowFailed(trackedIsLauncher = false))
    }

    @Test fun historyKeepsTheFreshestSnapshotFirstWithoutRepeats() {
        assertEquals(listOf("b", "a", "c"), ForeignDesktop.mergeHistory(listOf("a", "b", "c"), listOf("b", "a")))
        assertEquals("unknown snapshot keeps the history", listOf("a", "b"), ForeignDesktop.mergeHistory(listOf("a", "b"), null))
        assertEquals(listOf("x", "a"), ForeignDesktop.mergeHistory(listOf("a"), listOf("x")))
        val long = (1..50).map { "p$it" }
        assertEquals(ForeignDesktop.HISTORY_MAX, ForeignDesktop.mergeHistory(long, null).size)
    }

    @Test fun recentsAreSessionAppsWithoutTheLauncherOrUnlaunchablePackages() {
        val catalog = listOf(entry("ie.claudius.cardash"), entry("ru.dublgis.dgismobile"), entry("ru.yandex.music"))
        val history = listOf("ie.claudius.cardash", "com.google.android.permissioncontroller", "ru.yandex.music", "ru.dublgis.dgismobile")
        val list = ForeignDesktop.recents(history, known = true, launcherPkg = "ie.claudius.cardash", catalog = catalog)
        assertEquals(listOf("ru.yandex.music", "ru.dublgis.dgismobile"), list.map { it.pkg })
        assertEquals("ru.yandex.music.Main", list.first().cls)
    }

    @Test fun recentsUseTheFirstEntryOfAPackage() {
        val catalog = listOf(entry("a.b", "a.b.One"), entry("a.b", "a.b.Two"), entry("x.y"))
        val list = ForeignDesktop.recents(listOf("a.b"), known = true, launcherPkg = "x.y", catalog = catalog)
        assertEquals(listOf(AppRef("a.b", "a.b.One")), list)
    }

    @Test fun knownEmptyDisplayGivesAnEmptyList() {
        val catalog = listOf(entry("a.b"), entry("c.d"))
        assertTrue(ForeignDesktop.recents(emptyList(), known = true, launcherPkg = "c.d", catalog = catalog).isEmpty())
    }

    @Test fun unknownTaskListWithoutHistoryFallsBackToTheCatalogStub() {
        val catalog = listOf(entry("a.b"), entry("c.d"), entry("e.f"))
        val stub = ForeignDesktop.recents(emptyList(), known = false, launcherPkg = "c.d", catalog = catalog)
        assertEquals(listOf("a.b", "e.f"), stub.map { it.pkg })
        // с историей заглушка не нужна
        val withHistory = ForeignDesktop.recents(listOf("e.f"), known = false, launcherPkg = "c.d", catalog = catalog)
        assertEquals(listOf("e.f"), withHistory.map { it.pkg })
    }
}
