package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopMediaMetaLogicTest {
    @Test
    fun positionAdvancesOnlyWhilePlaying() {
        assertEquals(12, DesktopMediaMetaLogic.positionSeconds(10_000, 1_000, 1f, true, 3_500))
        assertEquals(10, DesktopMediaMetaLogic.positionSeconds(10_000, 1_000, 1f, false, 3_500))
        assertEquals(15, DesktopMediaMetaLogic.positionSeconds(10_000, 1_000, 2f, true, 3_500))
    }

    @Test
    fun unknownPositionAndDuration() {
        assertNull(DesktopMediaMetaLogic.positionSeconds(-1, 1_000, 1f, true, 3_500))
        assertNull(DesktopMediaMetaLogic.durationSeconds(0))
        assertNull(DesktopMediaMetaLogic.durationSeconds(-1))
        assertEquals(215, DesktopMediaMetaLogic.durationSeconds(215_400))
    }

    @Test
    fun trackKeyIgnoresNothingButText() {
        val a = DesktopMediaMetaLogic.trackKey("ru.yandex.music", "Song", "Artist", null, 200)
        assertEquals(a, DesktopMediaMetaLogic.trackKey("ru.yandex.music", "Song", "Artist", null, 200))
        org.junit.Assert.assertNotEquals(a, DesktopMediaMetaLogic.trackKey("ru.yandex.music", "Song 2", "Artist", null, 200))
        org.junit.Assert.assertNotEquals(a, DesktopMediaMetaLogic.trackKey("com.spotify.music", "Song", "Artist", null, 200))
    }
}
