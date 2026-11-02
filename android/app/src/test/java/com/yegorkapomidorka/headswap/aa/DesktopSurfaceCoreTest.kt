// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DesktopSurfaceCoreTest {
    // Real x264 Baseline output: coded 1600x912, cropped at the bottom to visible 1600x900.
    private val cfg = Golden.hex("00000001 6742c028d90064073f9f011000000300100000030280f1832480 00000001 68cb83cb20")
    private val cfgUncropped = Golden.hex("00000001 6742c028d90064073b011000000300100000030280f1832480 00000001 68cb83cb20")
    private val cfg720 = Golden.hex("00000001 6742801fda014016e9a8283028368509a8 00000001 68ce3c80")
    private val cfg1080 = Golden.hex("00000001 6742c028d900780227e5c044000003000400000300f03c60c920 00000001 68ce3c80")
    private val idr = Golden.hex("00000001 6580")
    private val p = Golden.hex("00000001 4180")

    private class Encoder(val output: TranscodeOutput, val journal: MutableList<String>) : VideoTranscoder {
        val fake = FakeTranscoder()
        var appliedBitrate = 0
        var fps = 0
        override val surfaceInput = object : SurfaceVideoInput {
            // Core must pass the handle without invoking Android; only the actual producer uses it.
            override val surface: Surface get() = error("JVM core must not dereference Surface")
        }
        override fun feedConfig(annexB: ByteArray) = fake.feedConfig(annexB)
        override fun feedFrame(annexB: ByteArray, ptsUs: Long) = fake.feedFrame(annexB, ptsUs)
        override fun resetDecoder() = fake.resetDecoder()
        override fun requestSyncFrame() = fake.requestSyncFrame()
        override fun setBitrate(bps: Int) { appliedBitrate = bps }
        override fun setTargetFps(fps: Int) { this.fps = fps }
        override fun statsLine() = "surface"
        override fun stop() { journal.add("encoder-stop"); fake.stop() }
    }

    private class SurfaceLink(val journal: MutableList<String>) : CarlifeLink by TranscodeLink() {
        val transport = TranscodeLink()
        val encoders = CopyOnWriteArrayList<Encoder>()
        val compressedFactories = AtomicInteger()
        var createGate: CountDownLatch? = null
        val createEntered = CountDownLatch(1)
        var failCreation = false
        override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? {
            compressedFactories.incrementAndGet()
            return null
        }
        override fun createSurfaceEncoder(out: TranscodeOutput): VideoTranscoder? {
            createEntered.countDown()
            createGate?.await(3, TimeUnit.SECONDS)
            if (failCreation) return null
            journal.add("encoder-create")
            return Encoder(out, journal).also { encoders.add(it) }
        }
        override fun sendVideo(payload: ByteArray) = transport.sendVideo(payload)
        override fun sendVideoTakeover(payload: ByteArray) = transport.sendVideoTakeover(payload)
    }

    private data class Event(val kind: String, val generation: Long, val heldMonitor: Boolean)
    private class Fixture : AutoCloseable {
        val journal = CopyOnWriteArrayList<String>()
        val events = LinkedBlockingQueue<Event>()
        val inputs = LinkedBlockingQueue<SurfaceVideoInput>()
        val failure = CountDownLatch(1)
        val core = AaBridgeCore({ message, _ -> if (message.startsWith("DEX video stopped:")) failure.countDown() })
            .apply { videoMode = DesktopVideoGeometry }
        val link = SurfaceLink(journal)
        var closeGate: CountDownLatch? = null
        var failClose = false
        val closeEntered = CountDownLatch(1)
        val listener = object : DesktopSurfaceListener {
            override fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput) {
                inputs.put(input)
                journal.add("available:$generation")
                events.put(Event("available", generation, Thread.holdsLock(core)))
            }
            override fun onSurfaceClosing(generation: Long) {
                closeEntered.countDown()
                closeGate?.await(3, TimeUnit.SECONDS)
                if (failClose) throw java.io.IOException("No producer death acknowledgement")
                journal.add("source-closed:$generation")
                events.put(Event("closed", generation, Thread.holdsLock(core)))
            }
            override fun onFirstFrame(generation: Long) {
                events.put(Event("first", generation, Thread.holdsLock(core)))
            }
        }
        init { core.enableDesktopSurface(listener); core.attach(link) }
        fun event(kind: String): Event {
            val event = events.poll(3, TimeUnit.SECONDS) ?: error("Missing $kind callback: $journal")
            assertEquals(kind, event.kind)
            assertFalse("Source callback under core monitor", event.heldMonitor)
            return event
        }
        fun start(): Long { core.onCarlifeStart(); return event("available").generation }
        override fun close() {
            link.createGate?.countDown()
            closeGate?.countDown()
            val done = CountDownLatch(1)
            core.stopDesktopSurface { done.countDown() }
            assertTrue("Source/encoder stop timed out", done.await(4, TimeUnit.SECONDS))
        }
    }

    @Test fun nativeSurfaceStartsOnlyAtCarlifeStartAndNeverUsesCompressedInput() = Fixture().use { f ->
        val sps = SpsCrop.parse(IdrSplicer.nals(cfg).first())
        assertEquals(1600, sps.codedW)
        assertEquals(912, sps.codedH)
        assertEquals(900, sps.visibleH)
        assertEquals(12, sps.bottom)
        assertTrue(f.link.encoders.isEmpty())
        val generation = f.start()
        val encoder = f.link.encoders.single()
        assertEquals(0, f.link.compressedFactories.get())
        encoder.output.onEncoderConfig(cfg)
        encoder.output.onEncoderFrame(idr, true)
        assertTrue(f.link.transport.sent.isEmpty()) // Producer not verified yet.
        assertTrue(f.core.desktopSurfaceReady(generation))
        f.core.onCodecConfig(cfg)
        f.core.onFrame(idr, 1)
        assertTrue(encoder.fake.configs.isEmpty())
        assertTrue(encoder.fake.frames.isEmpty())
        encoder.output.onEncoderFrame(p, false)
        assertTrue(f.link.transport.sent.isEmpty())
        encoder.output.onEncoderFrame(idr, true)
        assertArrayEquals(cfg + idr, f.link.transport.sent.single())
        assertEquals(generation, f.event("first").generation)
        encoder.output.onEncoderFrame(idr, true)
        assertNull(f.events.poll(50, TimeUnit.MILLISECONDS))
    }

    @Test fun pauseAndRestartKeepSurfaceButRequireFreshEncoderIdr() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        f.core.desktopSurfaceReady(generation)
        encoder.output.onEncoderConfig(cfg)
        encoder.output.onEncoderFrame(idr, true)
        f.event("first")
        f.core.onCarlifePause()
        encoder.output.onEncoderFrame(p, false)
        assertEquals(1, f.link.transport.sent.size)
        val syncs = encoder.fake.syncs.get()
        f.core.onCarlifeStart()
        assertTrue(encoder.fake.syncs.get() > syncs)
        assertEquals(1, f.link.encoders.size)
        encoder.output.onEncoderFrame(p, false)
        assertEquals(1, f.link.transport.sent.size)
        encoder.output.onEncoderFrame(idr, true)
        assertEquals(2, f.link.transport.sent.size)
    }

    @Test fun usbReconnectClosesProducerBeforeEncoderAndBeforeCreatingNextEncoder() = Fixture().use { f ->
        val oldGeneration = f.start()
        val old = f.link.encoders.single()
        f.closeGate = CountDownLatch(1)
        f.core.onCarlifeClosed(f.link)
        assertTrue(f.closeEntered.await(3, TimeUnit.SECONDS))
        f.core.attach(f.link)
        f.core.onCarlifeStart()
        assertEquals(0, old.fake.stops.get())
        assertEquals(1, f.link.encoders.size)
        assertFalse(f.core.desktopSurfaceReady(oldGeneration))
        f.closeGate!!.countDown()
        f.event("closed")
        val newGeneration = f.event("available").generation
        assertTrue(newGeneration > oldGeneration)
        assertTrue(f.journal.indexOf("source-closed:$oldGeneration") < f.journal.indexOf("encoder-stop"))
        assertEquals(1, old.fake.stops.get())
        val new = f.link.encoders.last()
        f.core.desktopSurfaceReady(newGeneration)
        new.output.onEncoderConfig(cfg)
        old.output.onEncoderConfig(cfg1080)
        old.output.onTranscodeError("old codec")
        old.output.onEncoderFrame(idr, true)
        f.core.desktopSurfaceFailed(oldGeneration, "old source")
        assertNull(f.core.desktopVideoError)
        assertTrue(f.link.transport.sent.isEmpty())
        new.output.onEncoderFrame(idr, true)
        assertArrayEquals(cfg + idr, f.link.transport.sent.single())
    }

    @Test fun cancellationDuringCodecCreationDisposesUnpublishedSurfaceBeforeStopCompletes() = Fixture().use { f ->
        f.link.createGate = CountDownLatch(1)
        f.core.onCarlifeStart()
        assertTrue(f.link.createEntered.await(3, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        f.core.stopDesktopSurface { f.journal.add("done"); done.countDown() }
        assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        f.link.createGate!!.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertFalse(f.journal.any { it.startsWith("available:") })
        assertEquals(1, f.link.encoders.single().fake.stops.get())
        assertTrue(f.journal.indexOf("encoder-stop") < f.journal.indexOf("done"))
    }

    @Test fun failedSurfaceEncoderCannotFallBackToCompressedVideo() = Fixture().use { f ->
        f.link.failCreation = true
        f.core.onCarlifeStart()
        assertTrue(f.link.createEntered.await(3, TimeUnit.SECONDS))
        assertTrue(f.failure.await(3, TimeUnit.SECONDS))
        f.close()
        assertNotNull(f.core.desktopVideoError)
        f.core.onCodecConfig(cfg)
        f.core.onFrame(idr, 1)
        assertTrue(f.link.transport.sent.isEmpty())
        assertEquals(0, f.link.compressedFactories.get())
    }

    @Test fun unexpectedOutputGeometryStopsProducerBeforeDisposingEncoder() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        f.core.desktopSurfaceReady(generation)
        encoder.output.onEncoderConfig(cfg1080)
        encoder.output.onEncoderFrame(idr, true)
        assertTrue(f.core.desktopVideoError!!.contains("1920×1080"))
        assertEquals(generation, f.event("closed").generation)
        f.close()
        assertTrue(f.link.transport.sent.isEmpty())
        assertTrue(f.journal.indexOf("source-closed:$generation") < f.journal.indexOf("encoder-stop"))
    }

    @Test fun bitratePacingAndOverflowRecoveryUseTheSameSingleEncoder() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        assertTrue(f.core.setEncoderBitrate(2_000_000))
        assertTrue(f.core.setTargetFps(20))
        assertEquals(2_000_000, encoder.appliedBitrate)
        assertEquals(20, encoder.fps)
        f.core.desktopSurfaceReady(generation)
        encoder.output.onEncoderConfig(cfg)
        encoder.output.onEncoderFrame(idr, true)
        f.event("first")
        val syncs = encoder.fake.syncs.get()
        f.link.transport.accept = false
        encoder.output.onEncoderFrame(p, false)
        assertTrue(encoder.fake.syncs.get() > syncs)
        f.link.transport.accept = true
        encoder.output.onEncoderFrame(p, false)
        assertEquals(1, f.link.transport.sent.size)
        encoder.output.onEncoderFrame(idr, true)
        assertEquals(2, f.link.transport.sent.size)
    }

    @Test fun missingSpsCannotAuthorizeNativeOutput() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        f.core.desktopSurfaceReady(generation)
        encoder.output.onEncoderConfig(Golden.hex("00000001 68ce3c80"))
        encoder.output.onEncoderFrame(idr, true)
        assertNotNull(f.core.desktopVideoError)
        assertTrue(f.link.transport.sent.isEmpty())
    }

    @Test fun incompatibleOutputModeFailsBeforeCreatingAnySurface() = Fixture().use { f ->
        f.core.videoMode = VideoMode.R1080
        f.core.onCarlifeStart()
        assertTrue(f.core.desktopVideoError!!.contains("1600×900"))
        assertTrue(f.link.encoders.isEmpty())
        assertEquals(0, f.link.compressedFactories.get())
    }

    @Test fun old720OutputCannotSilentlyDownscaleNativeDesktop() = Fixture().use { f ->
        val generation = f.start()
        f.core.desktopSurfaceReady(generation)
        f.link.encoders.single().output.onEncoderConfig(cfg720)
        f.link.encoders.single().output.onEncoderFrame(idr, true)
        assertTrue(f.core.desktopVideoError!!.contains("1280×720 вместо 1600×900"))
        assertTrue(f.link.transport.sent.isEmpty())
    }

    @Test fun aligned912BufferWithoutVisible900CropIsRejected() = Fixture().use { f ->
        val generation = f.start()
        f.core.desktopSurfaceReady(generation)
        f.link.encoders.single().output.onEncoderConfig(cfgUncropped)
        f.link.encoders.single().output.onEncoderFrame(idr, true)
        assertTrue(f.core.desktopVideoError!!.contains("1600×912 вместо 1600×900"))
        assertTrue(f.link.transport.sent.isEmpty())
    }

    @Test fun failedProducerCloseRetainsConsumerAndStopCompletionUntilActualDeathProof() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        f.failClose = true
        val done = CountDownLatch(1)
        f.core.stopDesktopSurface { done.countDown() }
        assertTrue(f.failure.await(3, TimeUnit.SECONDS))
        assertEquals(0, encoder.fake.stops.get())
        assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        assertFalse(f.core.desktopSurfaceReady(generation))
        // The source's death recipient, not a timeout, confirms that the producer is gone.
        f.core.desktopSurfaceProducerClosed(generation)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, encoder.fake.stops.get())
        f.core.desktopSurfaceProducerClosed(generation) // Duplicate acknowledgement is harmless.
        assertEquals(1, encoder.fake.stops.get())
    }

    @Test fun earlyBinderDeathAcknowledgementSurvivesLaterCloseException() = Fixture().use { f ->
        val generation = f.start()
        val encoder = f.link.encoders.single()
        f.core.desktopSurfaceProducerClosed(generation)
        f.failClose = true
        val done = CountDownLatch(1)
        f.core.stopDesktopSurface { done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, encoder.fake.stops.get())
        assertNull(f.core.desktopVideoError)
    }

    @Test fun liveCarlifeRecoveryWaitsForProducerDeathAndIgnoresOldEncoderCallbacks() = Fixture().use { f ->
        val oldGeneration = f.start()
        val oldEncoder = f.link.encoders.single()
        f.failClose = true
        val stopped = CountDownLatch(1)
        f.core.stopDesktopSurface { stopped.countDown() }
        assertTrue(f.failure.await(3, TimeUnit.SECONDS))
        assertFalse("unacknowledged producer must keep its consumer", f.core.rearmDesktopSurfaceAfterStop())
        assertFalse(stopped.await(30, TimeUnit.MILLISECONDS))
        f.core.desktopSurfaceProducerClosed(oldGeneration)
        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        assertEquals(1, oldEncoder.fake.stops.get())
        assertTrue(f.core.rearmDesktopSurfaceAfterStop())
        assertFalse("a second retry cannot rearm the same stopped generation", f.core.rearmDesktopSurfaceAfterStop())
        f.failClose = false
        f.core.enableDesktopSurface(object : DesktopSurfaceListener by f.listener {})
        // No attach(), CarLife START, or protocol handshake occurs during this recovery.
        val newGeneration = f.event("available").generation
        assertTrue(newGeneration > oldGeneration)
        val newEncoder = f.link.encoders.last()
        assertEquals(2, f.link.encoders.size)
        assertTrue(f.core.desktopSurfaceReady(newGeneration))
        oldEncoder.output.onEncoderConfig(cfg720)
        oldEncoder.output.onTranscodeError("late retired encoder error")
        oldEncoder.output.onEncoderFrame(idr, true)
        f.core.desktopSurfaceProducerClosed(oldGeneration)
        assertNull(f.core.desktopVideoError)
        assertEquals(0, newEncoder.fake.stops.get())
        assertTrue(f.link.transport.sent.isEmpty())
        newEncoder.output.onEncoderConfig(cfg)
        newEncoder.output.onEncoderFrame(idr, true)
        assertEquals(newGeneration, f.event("first").generation)
        assertEquals(1, f.link.transport.sent.size)
    }

    @Test fun recoveryInsideStopCallbackCannotShutDownTheReplacementWorker() = Fixture().use { f ->
        val old = f.start()
        val result = AtomicReference<Throwable?>()
        val callback = CountDownLatch(1)
        f.core.stopDesktopSurface {
            result.set(runCatching {
                check(f.core.rearmDesktopSurfaceAfterStop())
                f.core.enableDesktopSurface(object : DesktopSurfaceListener by f.listener {})
            }.exceptionOrNull())
            callback.countDown()
        }
        assertTrue(callback.await(3, TimeUnit.SECONDS))
        assertNull(result.get())
        assertEquals(old, f.event("closed").generation)
        val replacement = f.event("available").generation
        assertTrue(replacement > old)
        assertTrue(f.core.desktopSurfaceReady(replacement))
    }

    @Test fun detachedCarlifeLinkCannotBeRearmedByLateRecoveryCallback() = Fixture().use { f ->
        f.start()
        val done = CountDownLatch(1)
        f.core.stopDesktopSurface { done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        f.core.onCarlifeClosed(f.link)
        assertFalse(f.core.rearmDesktopSurfaceAfterStop())
    }
    @Test fun transportReconfigurationCarriesExactNativeGeometryAndKeepsPreviousLeaseSnapshot() = Fixture().use { f ->
        f.core.configureDesktopVideo(f.link, NativeDesktopGeometry(1024, 768), 137)
        f.start()
        val oldInput = f.inputs.poll(1, TimeUnit.SECONDS)!!
        assertEquals(listOf(1024, 768, 137), listOf(oldInput.width, oldInput.height, oldInput.density))
        try { f.core.configureDesktopVideo(f.link, NativeDesktopGeometry(1280, 720), 128); fail("Resized live producer") }
        catch (_: IllegalStateException) { }
        val next = SurfaceLink(f.journal)
        f.core.attach(next)
        try { f.core.configureDesktopVideo(f.link, NativeDesktopGeometry(1280, 720), 128); fail("Retired session changed geometry") }
        catch (_: IllegalStateException) { }
        f.core.configureDesktopVideo(next, NativeDesktopGeometry(1280, 720), 128)
        f.event("closed")
        f.core.onCarlifeStart()
        f.event("available")
        val newInput = f.inputs.poll(1, TimeUnit.SECONDS)!!
        assertEquals(listOf(1280, 720, 128), listOf(newInput.width, newInput.height, newInput.density))
        assertEquals(listOf(1024, 768, 137), listOf(oldInput.width, oldInput.height, oldInput.density))
    }

}
