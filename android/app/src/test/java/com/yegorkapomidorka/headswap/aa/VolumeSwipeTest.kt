package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeSwipeTest {
    private val W = 1280
    private val H = 720
    private var cancels = 0
    private val steps = ArrayList<Int>()
    private val g = VolumeSwipe({ cancels++ }, { steps.add(it) })

    private fun t(a: Int, x: Int, y: Int, px: Int = 0, py: Int = 0, on: Boolean = true) =
        g.onTouch(a, x, y, px, py, W, H, on)

    private val ptrDown1 = AapMessages.TOUCH_POINTER_DOWN or (1 shl 8)
    private val ptrUp1 = AapMessages.TOUCH_POINTER_UP or (1 shl 8)

    @Test fun swipeUpRaisesDownLowers() {
        assertFalse(t(AapMessages.TOUCH_DOWN, 1100, 500))
        assertTrue(t(ptrDown1, 1100, 500, 1200, 500))
        assertEquals(1, cancels)
        assertTrue(t(AapMessages.TOUCH_MOVE, 1100, 380, 1200, 380))   // 120 px = 2 шага по 60
        assertTrue(t(AapMessages.TOUCH_MOVE, 1100, 500, 1200, 500))
        assertEquals(listOf(2, -2), steps)
        assertTrue(t(ptrUp1, 1100, 500, 1200, 500))
        assertTrue(t(AapMessages.TOUCH_MOVE, 1100, 100))               // после POINTER_UP шагов нет
        assertTrue(t(AapMessages.TOUCH_UP, 1100, 100))
        assertEquals(listOf(2, -2), steps)
        assertFalse(t(AapMessages.TOUCH_DOWN, 1100, 500))              // жест закончился
    }

    @Test fun outsideZoneOrDisabledPassesThrough() {
        assertFalse(t(ptrDown1, 400, 500, 1200, 500))
        assertFalse(t(ptrDown1, 1100, 500, 1200, 500, on = false))
        assertFalse(t(AapMessages.TOUCH_MOVE, 1100, 300, 1200, 300))
        assertEquals(0, cancels)
        assertTrue(steps.isEmpty())
    }

    @Test fun lostUpReleasesOnNextDown() {
        assertTrue(t(ptrDown1, 1100, 500, 1200, 500))
        assertFalse(t(AapMessages.TOUCH_DOWN, 300, 300))
        assertFalse(t(AapMessages.TOUCH_MOVE, 300, 320))
    }
}
