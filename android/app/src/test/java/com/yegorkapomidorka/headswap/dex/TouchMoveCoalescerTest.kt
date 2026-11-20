package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class TouchMoveCoalescerTest {
    @Test fun movesWhileQueuedCollapseToLatest() {
        val c = TouchMoveCoalescer<Int>()
        val slot = c.offerMove(1, 10)!!
        repeat(300) { assertNull(c.offerMove(1, 11 + it)) }
        assertEquals(310, c.take(slot))
        assertNull(c.take(slot))
    }

    @Test fun moveAfterExecutionGetsNewSlot() {
        val c = TouchMoveCoalescer<Int>()
        val first = c.offerMove(1, 1)!!
        assertEquals(1, c.take(first))
        val second = c.offerMove(1, 2)!!
        assertNotSame(first, second)
        assertEquals(2, c.take(second))
    }

    @Test fun barrierKeepsMoveFromJumpingAhead() {
        val c = TouchMoveCoalescer<Int>()
        val beforeUp = c.offerMove(1, 1)!!
        c.barrier() // UP, затем DOWN нового жеста
        val afterDown = c.offerMove(1, 2)!!
        assertEquals(1, c.take(beforeUp))
        assertEquals(2, c.take(afterDown))
    }

    @Test fun newGenerationDoesNotMergeIntoStaleSlot() {
        val c = TouchMoveCoalescer<Int>()
        c.offerMove(1, 1)!! // задача старого поколения отброшена, take не будет
        val fresh = c.offerMove(2, 2)
        assertNotNull(fresh)
        assertEquals(2, c.take(fresh!!))
    }
}
