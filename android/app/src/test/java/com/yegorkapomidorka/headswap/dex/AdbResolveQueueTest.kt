// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class AdbResolveQueueTest {
    @Test fun resolvesOneAtATimeAndBoundsPendingWork() {
        val queue = AdbResolveQueue<Int>(2)
        assertTrue(queue.offer("pair", 1))
        assertFalse(queue.offer("pair", 2))
        assertTrue(queue.offer("connect", 3))
        assertFalse(queue.offer("extra", 4))
        val first = queue.take()!!
        assertEquals(1, first.value)
        assertNull(queue.take())
        assertSame(first, queue.complete(first))
        val second = queue.take()!!
        assertEquals("connect", second.key)
        assertNull(queue.complete(first)) // Duplicate old callback cannot free the new slot.
        assertNull(queue.take())
        assertSame(second, queue.complete(second))
    }
    @Test fun timeoutNeverStartsAnOverlappingResolveAndIgnoresLateResult() {
        val queue = AdbResolveQueue<Int>(2)
        queue.offer("pair", 1); queue.offer("connect", 2)
        val first = queue.take()!!
        assertTrue(queue.expire(first))
        assertFalse(queue.expire(first))
        assertNull(queue.take())
        assertNull(queue.complete(first))
        assertEquals(2, queue.take()!!.value)
    }
    @Test fun closeRestartRejectsOldSessionButDrainsItsUncancellableOperation() {
        val queue = AdbResolveQueue<Int>(2)
        queue.offer("same", 1); queue.offer("oldPending", 2)
        val old = queue.take()!!
        queue.clear()
        assertFalse(queue.expire(old))
        assertTrue(queue.offer("same", 3))
        assertNull(queue.take())
        assertNull(queue.complete(old))
        val fresh = queue.take()!!
        assertEquals(3, fresh.value)
        assertSame(fresh, queue.complete(fresh))
        assertNull(queue.take())
    }
    @Test fun lostServiceCanBeRediscoveredWithoutOldCallbackRemovingTheNewRequest() {
        val queue = AdbResolveQueue<Int>(2)
        queue.offer("same", 1)
        val old = queue.take()!!
        queue.remove("same")
        queue.offer("same", 2)
        assertNull(queue.complete(old))
        val fresh = queue.take()!!
        assertEquals(2, fresh.value)
        assertSame(fresh, queue.complete(fresh))
        queue.offer("queued", 3)
        queue.remove("queued")
        assertNull(queue.take())
    }
}
