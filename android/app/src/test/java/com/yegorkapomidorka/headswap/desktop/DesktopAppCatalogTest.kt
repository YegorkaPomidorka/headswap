// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.desktop

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DesktopAppCatalogTest {
    @Test fun catalogKeepsDistinctLauncherAliasesButRemovesDuplicateResults() {
        val first = DesktopAppCatalog.Entry("maps", "maps.Main", "Maps")
        val second = DesktopAppCatalog.Entry("maps", "maps.Transit", "Transit")
        assertEquals(listOf(first, second), DesktopAppCatalog.select(listOf(second, first, first), "headswap", Locale.US))
    }

    @Test fun inaccessibleAndOwnSetupEntriesAreNotPresentedAsWorkingApps() {
        val good = DesktopAppCatalog.Entry("music", "music.Main", "Music")
        val candidates = listOf(good,
            DesktopAppCatalog.Entry("headswap", "headswap.Setup", "HeadSwap"),
            DesktopAppCatalog.Entry("disabled", "disabled.Main", "Disabled", enabled = false),
            DesktopAppCatalog.Entry("private", "private.Main", "Private", exported = false),
            DesktopAppCatalog.Entry("", "Missing", "Missing package"),
            DesktopAppCatalog.Entry("missing", "", "Missing class"))
        assertEquals(listOf(good), DesktopAppCatalog.select(candidates, "headswap", Locale.US))
    }

    @Test fun sameNamedAppsRemainDistinctAndEmptyLabelsHaveReadableFallback() {
        val result = DesktopAppCatalog.select(listOf(
            DesktopAppCatalog.Entry("second", "second.Main", "Maps"),
            DesktopAppCatalog.Entry("first", "first.Main", " Maps "),
            DesktopAppCatalog.Entry("browser", "browser.Main", " ")), "headswap", Locale.US)
        assertEquals(listOf("browser", "first", "second"), result.map { it.packageName })
        assertEquals(listOf("browser", "Maps", "Maps"), result.map { it.label })
    }

    @Test fun launchGuardRejectsPhoneUnrelatedAndRetiredDisplays() {
        assertFalse(DesktopAppCatalog.ownsDisplay(0, 0))
        assertFalse(DesktopAppCatalog.ownsDisplay(0, 42))
        assertFalse(DesktopAppCatalog.ownsDisplay(42, -1))
        assertFalse(DesktopAppCatalog.ownsDisplay(42, 43))
        assertTrue(DesktopAppCatalog.ownsDisplay(42, 42))
    }
}
