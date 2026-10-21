package com.yegorkapomidorka.headswap.autosrv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Телефон без Android: виртуальные часы, список действий и точка доступа, которая меняется от клика. */
private class FakeHotspotUi : HotspotUi {
    var clock = 0L
    var up = false
    var ready = true
    var clickFlipsHotspot = true
    var intents = listOf("i1", "i2", "i3")
    /** интенты, которые система не запускает (startActivity бросает) */
    var failingIntents = emptySet<String>()
    /** интенты, на экране которых сразу виден свитч */
    var toggleOn = setOf("i2")
    /** интенты, на экране которых только пункт меню; свитч появляется после его нажатия */
    var entryOn = emptySet<String>()
    var toggleChecked: Boolean? = false
    var tileChecked: Boolean? = false
    /** плитка видна начиная с N-го вызова openQuickSettings; 0 = никогда */
    var tileFromOpen = 1

    var opened: String? = null
    var entryClicked = false
    var qsOpens = 0
    val actions = ArrayList<String>()
    var dumps = 0

    override fun prepareScreen() = if (ready) Pair(true, "готов") else Pair(false, "телефон заблокирован")
    override fun hotspotUp() = up
    override fun ifaces() = if (up) "swlan0 192.168.43.1/24" else "нет"
    override fun tetherIntents() = intents

    override fun openTetherSettings(label: String): String? {
        actions.add("open:$label")
        if (label in failingIntents) return null
        opened = label
        entryClicked = false
        return "com.android.settings"
    }

    override fun findToggle(): HotspotToggle? {
        val o = opened ?: return null
        val visible = o in toggleOn || (o in entryOn && entryClicked)
        return if (visible) HotspotToggle("Точка доступа Wi-Fi", toggleChecked, "label") else null
    }

    override fun findMenuEntry(): String? = if (opened in entryOn && !entryClicked) "Точка доступа Wi-Fi" else null

    override fun clickMenuEntry(): Boolean {
        actions.add("entry")
        entryClicked = true
        return true
    }

    override fun clickToggle(): Boolean {
        actions.add("toggle")
        if (clickFlipsHotspot) up = !up
        return true
    }

    override fun openQuickSettings(): Boolean {
        qsOpens++
        actions.add("qs-open")
        return true
    }

    override fun findTile() = if (tileFromOpen in 1..qsOpens) HotspotToggle("Точка доступа", tileChecked, "tile") else null

    override fun clickTile(): Boolean {
        actions.add("tile")
        if (clickFlipsHotspot) up = !up
        return true
    }

    override fun dump(maxChars: Int): List<String> {
        dumps++
        return listOf("окно pkg=com.android.settings")
    }

    override fun back() {
        actions.add("back")
    }

    override fun returnToApp() {
        actions.add("return")
    }

    override fun now() = clock
    override fun sleep(ms: Long) {
        clock += ms
    }

    val clicks get() = actions.filter { it == "toggle" || it == "tile" || it == "entry" }
}

class HotspotAutomationTest {
    private val log = ArrayList<String>()
    private lateinit var ui: FakeHotspotUi

    @Before
    fun setUp() {
        HotspotAutomation.weEnabled = false
        log.clear()
        ui = FakeHotspotUi()
    }

    private fun run(on: Boolean, force: Boolean = false) = HotspotAutomation(ui, { log.add(it) }).run(on, force)

    @Test
    fun alreadyUpMeansNoClickAndNoScreenWork() {
        ui.up = true
        val o = run(true)
        assertEquals(HotspotResult.ALREADY, o.result)
        assertTrue(ui.actions.isEmpty())
        assertFalse(HotspotAutomation.weEnabled)
        assertTrue(log.any { it.contains("уже включена") })
    }

    @Test
    fun settingsClickRaisesHotspotAndMarksWeEnabled() {
        ui.failingIntents = setOf("i1")
        val o = run(true)
        assertEquals(HotspotResult.OK, o.result)
        assertEquals("settings:i2", o.path)
        assertEquals(listOf("toggle"), ui.clicks)
        assertTrue(HotspotAutomation.weEnabled)
        assertEquals("open:i1,open:i2,toggle,return", ui.actions.joinToString(","))
    }

    @Test
    fun menuEntryWithoutSwitchIsEnteredOneLevelDeep() {
        ui.toggleOn = emptySet()
        ui.entryOn = setOf("i1")
        val o = run(true)
        assertEquals(HotspotResult.OK, o.result)
        assertEquals(listOf("entry", "toggle"), ui.clicks)
        assertEquals("settings:i1", o.path)
    }

    @Test
    fun clickWithoutEffectTimesOutWithDumpAndDoesNotTryShadeToo() {
        ui.clickFlipsHotspot = false
        val o = run(true)
        assertEquals(HotspotResult.TIMEOUT, o.result)
        assertTrue(ui.dumps > 0)
        assertFalse(HotspotAutomation.weEnabled)
        assertFalse(ui.actions.contains("qs-open"))
        assertEquals(listOf("toggle"), ui.clicks)
        assertTrue(ui.clock >= 8_000)
    }

    @Test
    fun switchAlreadyInWantedStateIsNotClicked() {
        ui.toggleChecked = true          // тумблер «включено», интерфейса точки нет
        val o = run(true)
        assertEquals(HotspotResult.MISMATCH, o.result)
        assertTrue(ui.clicks.isEmpty())
        assertTrue(ui.dumps > 0)
        assertFalse(HotspotAutomation.weEnabled)
    }

    @Test
    fun lockedPhoneGoesStraightToQuickSettingsTile() {
        ui.ready = false
        val o = run(true)
        assertEquals(HotspotResult.OK, o.result)
        assertEquals("qs", o.path)
        assertEquals("qs-open,tile,back", ui.actions.joinToString(","))      // ни одного интента, возврата в приложение нет
        assertTrue(HotspotAutomation.weEnabled)
    }

    @Test
    fun tileSearchRetriesWithSecondQuickSettingsOpen() {
        ui.ready = false
        ui.tileFromOpen = 2
        val o = run(true)
        assertEquals(HotspotResult.OK, o.result)
        assertEquals(2, ui.qsOpens)
    }

    @Test
    fun noTileAnywhereGivesNotFoundWithDumpAndClosesShade() {
        ui.ready = false
        ui.tileFromOpen = 0
        val o = run(true)
        assertEquals(HotspotResult.NOT_FOUND, o.result)
        assertTrue(ui.dumps > 0)
        assertEquals(2, ui.qsOpens)
        assertEquals("back", ui.actions.last())
        assertTrue(ui.clicks.isEmpty())
    }

    @Test
    fun settingsWithoutSwitchFallsBackToShadeAndReturnsToApp() {
        ui.toggleOn = emptySet()
        val o = run(true)
        assertEquals(HotspotResult.OK, o.result)
        assertEquals("qs", o.path)
        assertTrue(ui.dumps > 0)            // дамп по каждому интенту, где ничего не нашлось
        assertEquals("return", ui.actions.last())
    }

    @Test
    fun offWithoutWeEnabledDoesNothingUnlessForced() {
        ui.up = true
        val o = run(false)
        assertEquals(HotspotResult.SKIPPED, o.result)
        assertTrue(ui.actions.isEmpty())
        assertTrue(ui.up)

        ui.toggleChecked = true
        val forced = run(false, force = true)
        assertEquals(HotspotResult.OK, forced.result)
        assertFalse(ui.up)
        assertEquals(listOf("toggle"), ui.clicks)
    }

    @Test
    fun offAfterOurOwnEnableClearsFlag() {
        ui.up = true
        ui.toggleChecked = true
        HotspotAutomation.weEnabled = true
        val o = run(false)
        assertEquals(HotspotResult.OK, o.result)
        assertFalse(ui.up)
        assertFalse(HotspotAutomation.weEnabled)
    }

    @Test
    fun offWhenAlreadyDownJustClearsFlag() {
        HotspotAutomation.weEnabled = true
        val o = run(false)
        assertEquals(HotspotResult.ALREADY, o.result)
        assertFalse(HotspotAutomation.weEnabled)
        assertTrue(ui.actions.isEmpty())
    }
}

class HotspotTextTest {
    @Test
    fun hotspotLabelsRuAndEn() {
        for (s in listOf("Точка доступа Wi-Fi", "Точка доступа", "Мобильная точка доступа", "Режим модема", "Переносная точка доступа", "Wi-Fi hotspot", "Hotspot", "Portable hotspot", "Tethering & portable hotspot", "ТОЧКА ДОСТУПА")) {
            assertTrue(s, HotspotText.isTarget(s))
        }
    }

    @Test
    fun neighbourSwitchesAreNotTheHotspot() {
        for (s in listOf("USB-модем", "USB tethering", "Bluetooth tethering", "Bluetooth-модем", "Отключать точку доступа автоматически", "Turn off hotspot automatically", "Совместимость с 5 ГГц", "Wi-Fi", "Bluetooth", "")) {
            assertFalse(s, HotspotText.isTarget(s))
        }
        assertFalse(HotspotText.isTarget(null))
        assertTrue(HotspotText.isExcluded("Bluetooth tethering"))
    }

    @Test
    fun tileStateFromDescriptions() {
        assertEquals(false, HotspotText.stateOf(listOf(null, "Hotspot, Off.")))
        assertEquals(true, HotspotText.stateOf(listOf("On", "Hotspot")))
        assertEquals(false, HotspotText.stateOf(listOf("Выключено")))
        assertEquals(true, HotspotText.stateOf(listOf("Включено")))
        assertEquals(true, HotspotText.stateOf(listOf("Точка доступа, вкл.")))
        assertNull(HotspotText.stateOf(listOf("Точка доступа", null)))
        assertNull(HotspotText.stateOf(emptyList()))
    }
}
