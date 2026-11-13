package com.yegorkapomidorka.headswap

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimeTest {
    private val now = 1_794_571_200_123L   // 2026-11-13, младшие 32 бита дают отрицательный int

    @Test
    fun secondsByDefault() {
        assertEquals((now / 1000).toInt(), CarLifeSession.videoTime(now, ms = false))
    }

    @Test
    fun millisLow32BitsLikeMedia() {
        assertEquals((now and 0xFFFFFFFFL).toInt(), CarLifeSession.videoTime(now, ms = true))
    }

    @Test
    fun toggleOffByDefault() {
        assertEquals(false, CarLifeSession.VIDEO_TIME_MS)
    }
}
