package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailSwipeTest {
    private val W = 1280
    private val H = 720
    private var cancels = 0
    private val toggles = ArrayList<Boolean>()
    private val g = RailSwipe({ cancels++ }, { toggles.add(it) })

    private val down = AapMessages.TOUCH_DOWN
    private val move = AapMessages.TOUCH_MOVE
    private val up = AapMessages.TOUCH_UP
    private val ptrDown1 = AapMessages.TOUCH_POINTER_DOWN or (1 shl 8)

    private fun t(a: Int, x: Int, y: Int, px: Int = 0, py: Int = 0, hidden: Boolean = false, on: Boolean = true, w: Int = W, h: Int = H) =
        g.onTouch(a, x, y, px, py, w, h, on, hidden)

    @Test fun leftSwipeHidesVisibleRail() {
        assertFalse(t(down, 400, 360))
        assertFalse(t(move, 350, 362))
        assertTrue(t(move, 240, 365))                 // dx = -160 <= -0.12*1280 = -153.6
        assertEquals(1, cancels)
        assertEquals(listOf(true), toggles)
    }

    @Test fun rightSwipeShowsHiddenRail() {
        assertFalse(t(down, 250, 360, hidden = true))
        assertFalse(t(move, 300, 360, hidden = true))
        assertTrue(t(move, 410, 370, hidden = true))  // dx = +160
        assertEquals(listOf(false), toggles)
        assertEquals(1, cancels)
    }

    @Test fun edgeSwipeShowsHiddenRail() {
        assertFalse(t(down, 3, 340, hidden = true))
        assertTrue(t(move, 200, 345, hidden = true))
        assertEquals(listOf(false), toggles)
    }

    @Test fun edgeSwipeDoesNotHideVisibleRail() {
        assertFalse(t(down, 3, 340))
        assertFalse(t(move, 200, 345))
        assertTrue(toggles.isEmpty())
    }

    @Test fun startOutsideCorridorPassesThrough() {
        for ((x, y) in listOf(100 to 360, 500 to 360, 300 to 150, 300 to 600)) {
            assertFalse(t(down, x, y))
            assertFalse(t(move, x - 300, y))
            assertFalse(t(up, x - 300, y))
        }
        assertFalse(t(down, 300, 360, on = false))
        assertFalse(t(move, 0, 360, on = false))
        assertFalse(t(up, 0, 360, on = false))
        assertEquals(0, cancels)
        assertTrue(toggles.isEmpty())
    }

    @Test fun portraitIsIgnored() {
        assertFalse(t(down, 300, 600, w = 720, h = 1280))
        assertFalse(t(move, 0, 600, w = 720, h = 1280))
        assertTrue(toggles.isEmpty())
    }

    @Test fun verticalDriftCancelsTracking() {
        assertFalse(t(down, 400, 360))
        assertFalse(t(move, 380, 420))                // dy = 60 > 0.05*720 и > 0.5*20
        assertFalse(t(move, 200, 420))                // горизонталь уже не засчитывается
        assertFalse(t(up, 200, 420))
        assertEquals(0, cancels)
        assertTrue(toggles.isEmpty())
        assertFalse(t(down, 400, 360))                // следующий жест снова ловится
        assertTrue(t(move, 240, 360))
        assertEquals(listOf(true), toggles)
    }

    @Test fun secondFingerCancelsTracking() {
        assertFalse(t(down, 400, 360))
        assertFalse(t(ptrDown1, 400, 360, 900, 360))
        assertFalse(t(move, 240, 360, 900, 360))
        assertFalse(t(move, 200, 360))
        assertFalse(t(up, 200, 360))
        assertEquals(0, cancels)
        assertTrue(toggles.isEmpty())
    }

    @Test fun oppositeDirectionDoesNotFire() {
        assertFalse(t(down, 300, 360))
        assertFalse(t(move, 500, 360))                // рейка видна, а тянут вправо
        assertFalse(t(move, 100, 360))                // откат назад не воскрешает жест
        assertFalse(t(up, 100, 360))
        assertEquals(0, cancels)
        assertTrue(toggles.isEmpty())
    }

    @Test fun smallBackwardMoveIsTolerated() {
        assertFalse(t(down, 400, 360))
        assertFalse(t(move, 430, 360))                // +30 назад (< 0.05*1280 = 64)
        assertTrue(t(move, 240, 360))
        assertEquals(listOf(true), toggles)
    }

    @Test fun eatsEventsAfterFireUntilUpThenIdle() {
        assertFalse(t(down, 400, 360))
        assertTrue(t(move, 240, 360))
        assertTrue(t(move, 100, 360))
        assertTrue(t(move, 90, 380))
        assertTrue(t(up, 90, 380))
        assertEquals(1, cancels)
        assertEquals(1, toggles.size)
        assertFalse(t(move, 90, 380))                 // IDLE: событие без DOWN идёт дальше
        assertFalse(t(down, 100, 100))
        assertFalse(t(up, 100, 100))
    }

    @Test fun cancelEndsEatenGesture() {
        assertFalse(t(down, 400, 360))
        assertTrue(t(move, 240, 360))
        assertTrue(t(3, 240, 360))
        assertFalse(t(move, 240, 360))
        assertEquals(1, cancels)
    }

    @Test fun lostUpReleasesOnNextDown() {
        assertFalse(t(down, 400, 360))
        assertTrue(t(move, 240, 360))
        assertFalse(t(down, 400, 360))                // UP потерян: новый DOWN не съеден
        assertTrue(t(move, 240, 360))
        assertEquals(2, cancels)
        assertEquals(listOf(true, true), toggles)
    }

    @Test fun resetDropsGesture() {
        assertFalse(t(down, 400, 360))
        assertTrue(t(move, 240, 360))
        g.reset()
        assertFalse(t(move, 100, 360))
    }
}
