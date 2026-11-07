package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DupDownFilterTest {
    @Test fun autoDropsOnlyQuickRepeat() {
        assertTrue(DupDownFilter.drop(DupDownFilter.AUTO, true, 3))
        assertTrue(DupDownFilter.drop(DupDownFilter.AUTO, true, 70))
        assertTrue(DupDownFilter.drop(DupDownFilter.AUTO, true, DupDownFilter.AUTO_WINDOW_MS))
        assertFalse(DupDownFilter.drop(DupDownFilter.AUTO, true, DupDownFilter.AUTO_WINDOW_MS + 1))
        assertFalse(DupDownFilter.drop(DupDownFilter.AUTO, true, 2_000))
        assertFalse(DupDownFilter.drop(DupDownFilter.AUTO, true, -1))
    }

    @Test fun onIgnoresTimeOffNeverDrops() {
        assertTrue(DupDownFilter.drop(DupDownFilter.ON, true, 2_000))
        assertFalse(DupDownFilter.drop(DupDownFilter.OFF, true, 3))
    }

    @Test fun differentOrReleasedPointNeverDropped() {
        for (m in DupDownFilter.values()) assertFalse(DupDownFilter.drop(m, false, 3))
    }

    @Test fun parseByNameFallsBackToDefault() {
        assertEquals(DupDownFilter.AUTO, DupDownFilter.DEFAULT)
        for (m in DupDownFilter.values()) assertEquals(m, DupDownFilter.parse(m.name))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse(null))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse(""))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse("junk"))
    }
}
