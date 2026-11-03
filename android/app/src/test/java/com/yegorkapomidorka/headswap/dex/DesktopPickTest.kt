package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPickTest {
    private fun entry(pkg: String, cls: String, label: String = pkg) = DesktopAppCatalog.Entry(pkg, cls, label)

    @Test fun optionsNeedBothHomeAndLauncherEntries() {
        val catalog = listOf(entry("ie.claudius.cardash", "ie.claudius.cardash.HomeActivity", "CarDash"),
            entry("com.maps", "com.maps.Main"), entry("only.home", "only.home.Main"))
        val homes = setOf("ie.claudius.cardash/ie.claudius.cardash.HomeActivity", "no.launcher/no.launcher.Home", "only.home/only.home.Home")
        // карты без HOME, пакет без LAUNCHER и HOME-заглушка с отдельным LAUNCHER-входом (как «Настройки») отсеяны
        assertEquals(listOf("ie.claudius.cardash/ie.claudius.cardash.HomeActivity"),
            DesktopPick.options(catalog, homes).map { it.key })
    }

    @Test fun launchEntryPrefersTheOneAlsoUnderHome() {
        val pkg = "ru.drivedeck.launcher"
        val catalog = listOf(entry(pkg, "$pkg.AliasA"), entry(pkg, "$pkg.MainActivity"), entry(pkg, "$pkg.AliasB"))
        assertEquals("$pkg/$pkg.MainActivity", DesktopPick.options(catalog, setOf("$pkg/$pkg.MainActivity")).single().key)
        // HOME-вход не в списке LAUNCHER: не лаунчер
        assertEquals(emptyList<String>(), DesktopPick.options(catalog, setOf("$pkg/$pkg.Hidden")).map { it.key })
    }

    @Test fun componentNeedsPackageAndClass() {
        assertTrue(DesktopPick.isComponent("a.b/a.b.Home"))
        assertFalse(DesktopPick.isComponent(null))
        assertFalse(DesktopPick.isComponent("a.b"))
        assertFalse(DesktopPick.isComponent("/a.b.Home"))
        assertFalse(DesktopPick.isComponent("a.b/"))
    }

    @Test fun selectedIsMatchedByPackageAndDroppedWhenGone() {
        val options = listOf(entry("a.b", "a.b.Home"))
        assertEquals("a.b/a.b.Home", DesktopPick.selected("a.b/a.b.Old", options)?.key)
        assertNull(DesktopPick.selected("c.d/c.d.Home", options))
        assertNull(DesktopPick.selected(null, options))
        assertNull(DesktopPick.selected("garbage", options))
    }
}
