package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DupDownFilterTest {
    @Test fun autoFollowsHonda() {
        assertTrue(DupDownFilter.active(DupDownFilter.AUTO, true))
        assertFalse(DupDownFilter.active(DupDownFilter.AUTO, false))
    }

    @Test fun onAndOffIgnoreHonda() {
        assertTrue(DupDownFilter.active(DupDownFilter.ON, true))
        assertTrue(DupDownFilter.active(DupDownFilter.ON, false))
        assertFalse(DupDownFilter.active(DupDownFilter.OFF, true))
        assertFalse(DupDownFilter.active(DupDownFilter.OFF, false))
    }

    @Test fun parseByNameFallsBackToDefault() {
        assertEquals(DupDownFilter.AUTO, DupDownFilter.DEFAULT)
        for (m in DupDownFilter.values()) assertEquals(m, DupDownFilter.parse(m.name))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse(null))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse(""))
        assertEquals(DupDownFilter.DEFAULT, DupDownFilter.parse("junk"))
    }
}
