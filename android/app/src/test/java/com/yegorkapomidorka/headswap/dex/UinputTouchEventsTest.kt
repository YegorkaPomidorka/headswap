package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UinputTouchEventsTest {
    private val abs = UinputHostMain.EV_ABS
    private val slot = UinputHostMain.ABS_MT_SLOT
    private val track = UinputHostMain.ABS_MT_TRACKING_ID
    private val px = UinputHostMain.ABS_MT_POSITION_X
    private val py = UinputHostMain.ABS_MT_POSITION_Y
    private val key = UinputHostMain.EV_KEY
    private val btn = UinputHostMain.BTN_TOUCH

    @Test fun tapIsDownThenUpWithSyn() {
        val c = UinputTouchEvents()
        val down = c.convert(0, 0, intArrayOf(0), floatArrayOf(382.6f), floatArrayOf(315f), 1280, 640)
        assertArrayEquals(intArrayOf(abs, slot, 0, abs, track, 1, abs, slot, 0, abs, px, 382, abs, py, 315, key, btn, 1, 0, 0, 0), down)
        assertTrue(c.touching)
        val up = c.convert(1, 0, intArrayOf(0), floatArrayOf(384f), floatArrayOf(315f), 1280, 640)
        assertArrayEquals(intArrayOf(abs, slot, 0, abs, track, -1, key, btn, 0, 0, 0, 0), up)
        assertFalse(c.touching)
    }

    @Test fun coordinatesClampedToDisplay() {
        val down = UinputTouchEvents().convert(0, 0, intArrayOf(0), floatArrayOf(5000f), floatArrayOf(-3f), 1280, 640)
        assertArrayEquals(intArrayOf(abs, px, 1279), down.copyOfRange(9, 12))
        assertArrayEquals(intArrayOf(abs, py, 0), down.copyOfRange(12, 15))
    }

    @Test fun secondFingerKeepsTouchAndReleasesOnlyItsSlot() {
        val c = UinputTouchEvents()
        c.convert(0, 0, intArrayOf(0), floatArrayOf(10f), floatArrayOf(10f), 100, 100)
        val second = c.convert(5, 1, intArrayOf(0, 1), floatArrayOf(10f, 50f), floatArrayOf(10f, 50f), 100, 100)
        assertFalse(second.toList().windowed(3, 3).contains(listOf(key, btn, 1)))
        val pointerUp = c.convert(6, 1, intArrayOf(0, 1), floatArrayOf(10f, 50f), floatArrayOf(10f, 50f), 100, 100)
        assertTrue(pointerUp.toList().windowed(3, 3).containsAll(listOf(listOf(abs, slot, 1), listOf(abs, track, -1))))
        assertTrue(c.touching)
    }

    @Test fun lostUpIsClosedBeforeNewDown() {
        val c = UinputTouchEvents()
        c.convert(0, 0, intArrayOf(0), floatArrayOf(10f), floatArrayOf(10f), 100, 100)
        val down = c.convert(0, 0, intArrayOf(0), floatArrayOf(20f), floatArrayOf(20f), 100, 100)
        assertArrayEquals(intArrayOf(abs, slot, 0, abs, track, -1, key, btn, 0, 0, 0, 0), down.copyOfRange(0, 12))
    }
}
