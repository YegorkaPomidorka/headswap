package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavChannelTest {
    @Test
    fun parseKnownNamesAndFallbackToTts() {
        assertEquals(NavChannel.MIX, NavChannel.parse("MIX"))
        assertEquals(NavChannel.VR, NavChannel.parse("VR"))
        assertEquals(NavChannel.TTS, NavChannel.parse("TTS"))
        assertEquals(NavChannel.TTS, NavChannel.parse(null))
        assertEquals(NavChannel.TTS, NavChannel.parse("garbage"))
    }

    @Test
    fun onlyMixKeepsNavVoiceInMedia() {
        assertFalse(NavChannel.MIX.separateNavMix)
        assertTrue(NavChannel.VR.separateNavMix)
        assertTrue(NavChannel.TTS.separateNavMix)
    }
}
