// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.Msg
import org.junit.Assert.*
import org.junit.Test

class DesktopVideoDeliveryTest {
    private fun DesktopVideoDelivery.video(at: Long, bytes: Int = 100) =
        writeCompleted(Ch.VIDEO, Msg.VIDEO_DATA, bytes, at)

    @Test fun startingOrEnqueuingProvidesNoSuccessGrace() {
        val delivery = DesktopVideoDelivery()
        delivery.video(99)
        assertFalse(delivery.isFlowing(99))
        delivery.start(100)
        assertFalse(delivery.isFlowing(100))
        assertFalse(delivery.isFlowing(2_000))
        delivery.video(2_001)
        assertTrue(delivery.isFlowing(2_001))
    }

    @Test fun onlyCompletedNonemptyVideoDataCounts() {
        val delivery = DesktopVideoDelivery().apply { start(100) }
        delivery.writeCompleted(Ch.CMD, Msg.VIDEO_DATA, 100, 101)
        delivery.writeCompleted(Ch.MEDIA, Msg.VIDEO_DATA, 100, 102)
        delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_HEARTBEAT, 100, 103)
        delivery.video(104, bytes = 0)
        delivery.video(105, bytes = -1)
        assertFalse(delivery.isFlowing(105))
        delivery.video(106)
        assertTrue(delivery.isFlowing(106))
    }

    @Test fun ReceiverThatStopsReadingLosesSuccessUntilAnotherWriteCompletes() {
        val delivery = DesktopVideoDelivery().apply { start(0); video(100) }
        assertTrue(delivery.isFlowing(3_100))
        assertFalse(delivery.isFlowing(3_101))
        // A queued frame, command or repeated UI poll cannot renew this timestamp.
        delivery.writeCompleted(Ch.CMD, Msg.VIDEO_ENCODER_START, 100, 4_000)
        assertFalse(delivery.isFlowing(4_000))
        delivery.video(4_001)
        assertTrue(delivery.isFlowing(4_001))
    }

    @Test fun pauseAndResumeRequireANewCompletedFrame() {
        val delivery = DesktopVideoDelivery().apply { start(100); video(101) }
        delivery.pause()
        assertFalse(delivery.isFlowing(102))
        delivery.video(103) // Completion while paused is not remembered.
        delivery.start(104)
        assertFalse(delivery.isFlowing(104))
        delivery.video(105)
        assertTrue(delivery.isFlowing(105))
        delivery.start(106) // A repeated START is a fresh receiving phase, too.
        assertFalse(delivery.isFlowing(106))
    }

    @Test fun closeIsIrreversibleForLateCallbacks() {
        val delivery = DesktopVideoDelivery().apply { start(100); video(101) }
        delivery.close()
        assertFalse(delivery.isFlowing(102))
        delivery.start(103)
        delivery.video(104)
        delivery.pause()
        delivery.start(105)
        assertFalse(delivery.isFlowing(105))
    }

    @Test fun staleOrReversedClockValuesCannotRefreshDelivery() {
        val delivery = DesktopVideoDelivery().apply { start(100) }
        delivery.video(99)
        assertFalse(delivery.isFlowing(100))
        delivery.video(200)
        assertFalse(delivery.isFlowing(199))
        delivery.video(150)
        assertTrue(delivery.isFlowing(3_200))
        assertFalse(delivery.isFlowing(3_201))
    }
}
