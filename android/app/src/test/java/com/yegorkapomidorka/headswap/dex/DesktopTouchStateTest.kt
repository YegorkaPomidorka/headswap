// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopTouchStateTest {
    private fun DesktopTouchState.send(action: Int, index: Int = 0, vararg ids: Int) =
        events(action, index, ids, FloatArray(ids.size) { 40f + it }, FloatArray(ids.size) { 80f + it }, 1280, 720)

    @Test fun pinchRetainsIdsWhenFirstContactLifts() {
        val s = DesktopTouchState()
        assertEquals(1, s.send(0, 0, 3).size)
        assertEquals(listOf(3, 7), s.send(5, 1, 3, 7).single().points.map { it.id })
        assertEquals(2, s.send(2, 0, 3, 7).single().points.size)
        assertEquals(0, s.send(6, 0, 3, 7).single().actionIndex)
        assertEquals(7, s.send(2, 0, 7).single().points.single().id)
        assertEquals(1, s.send(1, 0, 7).single().action)
        assertNull(s.cancel())
    }
    @Test fun malformedGestureDoesNotPoisonActiveContacts() {
        val s = DesktopTouchState(); s.send(0, 0, 1)
        assertThrows(IllegalArgumentException::class.java) { s.send(5, 0, 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { s.send(2, 0, 2) }
        assertEquals(1, s.send(1, 0, 1).single().points.single().id)
    }
    @Test fun newDownCancelsOldTwoContactGesture() {
        val s = DesktopTouchState(); s.send(0, 0, 0); s.send(5, 1, 0, 1)
        val events = s.send(0, 0, 9)
        assertEquals(listOf(3, 0), events.map { it.action })
        assertEquals(2, events.first().points.size)
    }
    @Test fun resizeCancellationRequiresNewDown() {
        val s = DesktopTouchState(); s.send(0, 0, 0); s.send(5, 1, 0, 1)
        assertEquals(2, s.cancel()!!.points.size)
        assertTrue(s.send(2, 0, 0, 1).isEmpty())
        assertTrue(s.send(1, 0, 0).isEmpty())
    }
    @Test fun allCoordinatesAreValidatedAndClampedToOwnedDisplay() {
        val s = DesktopTouchState()
        val p = s.events(0, 0, intArrayOf(0), floatArrayOf(-1f), floatArrayOf(900f), 1280, 720).single().points.single()
        assertEquals(0f, p.x); assertEquals(719f, p.y)
        assertThrows(IllegalArgumentException::class.java) {
            s.events(5, 1, intArrayOf(0, 1), floatArrayOf(1f, Float.NaN), floatArrayOf(1f, 2f), 1280, 720)
        }
        assertEquals(1, s.cancel()!!.points.size)
    }
    @Test fun rejectDuplicateIdsAndExtraContacts() {
        assertThrows(IllegalArgumentException::class.java) { DesktopTouchState.validate(5, 1, intArrayOf(0, 0), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { DesktopTouchState.validate(2, 0, intArrayOf(0, 1, 2), FloatArray(3), FloatArray(3)) }
    }
}
