package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Поток idr_splice_in.annexb: x264 Baseline 96x64, 12 кадров, 2 слайса на кадр, IDR только первый; слева testsrc2, справа заливка
 * (в нашем IDR и I_PCM, и I16x16). idr_splice_in.yuv - его кадры из ffmpeg (I420). idr_splice_out.annexb - тот же поток со
 * своим IDR после кадров 5 и 10; проверен ffmpeg (-err_detect explode, framemd5): 14 кадров = исходные с повтором 5 и 10,
 * и с нашего IDR при потерянном начале потока - те же кадры.
 */
object SpliceAssets {
    const val W = 96
    const val H = 64
    val input: ByteArray by lazy { res("idr_splice_in.annexb") }
    val output: ByteArray by lazy { res("idr_splice_out.annexb") }
    private val yuv: ByteArray by lazy { res("idr_splice_in.yuv") }

    private fun res(name: String) = SpliceAssets::class.java.getResourceAsStream("/aa/$name")!!.readBytes()

    fun picture(k: Int): IdrSplice.Picture {
        val fs = W * H * 3 / 2
        val o = k * fs
        return IdrSplice.Picture(W, H, yuv.copyOfRange(o, o + W * H), yuv.copyOfRange(o + W * H, o + W * H * 5 / 4), yuv.copyOfRange(o + W * H * 5 / 4, o + fs))
    }

    /** (SPS+PPS, кадры как их шлёт AA: слайсы картинки одним сообщением). */
    fun split(): Pair<ByteArray, List<ByteArray>> {
        val cfg = ArrayList<ByteArray>()
        val aus = ArrayList<MutableList<ByteArray>>()
        for (n in IdrSplicer.nals(input)) {
            when (n[0].toInt() and 0x1F) {
                7, 8 -> cfg.add(n)
                1, 5 -> {
                    if (n[1].toInt() and 0x80 != 0) aus.add(ArrayList())      // first_mb_in_slice = 0 (ue "1")
                    aus.last().add(n)
                }
            }
        }
        return IdrSplicer.join(cfg) to aus.map { IdrSplicer.join(it) }
    }

    fun nalTypes(au: ByteArray) = IdrSplicer.nals(au).map { it[0].toInt() and 0x1F }
}

class IdrSpliceTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun spliceMatchesFfmpegVerifiedStream() {
        val sp = IdrSplicer()
        val out = ByteArrayOutputStream()
        val (cfg, aus) = SpliceAssets.split()
        sp.onConfig(cfg)
        out.write(cfg)
        for ((k, au) in aus.withIndex()) {
            out.write(sp.process(au))
            if (k > 0 && k % 5 == 0) {
                assertNull(sp.blocker())
                val r = sp.build(SpliceAssets.picture(k))
                assertTrue("I16x16 ${r.i16}", r.i16 > 0)
                assertTrue("PCM ${r.pcm}", r.pcm > 0)
                out.write(r.nal)
                sp.commit()
            }
        }
        assertEquals(2, sp.spliced)
        val want = SpliceAssets.output
        // SEI x264 в эталоне есть, в split() нет: сравниваем остальные NAL-ы
        fun stream(b: ByteArray) = IdrSplicer.nals(b).filter { it[0].toInt() and 0x1F != 6 }.map { it.toList() }
        assertEquals(stream(want), stream(out.toByteArray()))
    }

    @Test
    fun framesAfterOwnIdrCountFromZero() {
        val sp = IdrSplicer()
        val (cfg, aus) = SpliceAssets.split()
        sp.onConfig(cfg)
        for (k in 0..3) sp.process(aus[k])
        sp.build(SpliceAssets.picture(3))
        sp.commit()
        val sps = IdrSplice.parseSps(IdrSplicer.nals(cfg)[0])
        for (k in 4..6) {
            val out = sp.process(aus[k])
            for (nal in IdrSplicer.nals(out)) {
                val r = IdrSplice.Bits(SpsCrop.removeEmulationPrevention(nal))
                r.skip(8); r.ue(); r.ue(); r.ue()
                assertEquals(k - 3, r.u(sps.log2Fn))
            }
        }
        // IDR от AA снимает сдвиг: дальше кадры идут как есть
        val idr = aus[0]
        assertTrue(idr === sp.process(idr))
        assertTrue(aus[1] === sp.process(aus[1]))
    }

    @Test
    fun repeatedIdrOfSamePictureKeepsShift() {
        val sp = IdrSplicer()
        val (cfg, aus) = SpliceAssets.split()
        sp.onConfig(cfg)
        for (k in 0..4) sp.process(aus[k])
        sp.build(SpliceAssets.picture(4)); sp.commit()
        val once = sp.process(aus[5])
        val sp2 = IdrSplicer()
        sp2.onConfig(cfg)
        for (k in 0..4) sp2.process(aus[k])
        sp2.build(SpliceAssets.picture(4)); sp2.commit()
        sp2.build(SpliceAssets.picture(4)); sp2.commit()    // статичный экран: тот же кадр ещё раз
        assertArrayEquals(once, sp2.process(aus[5]))
    }

    @Test
    fun flatPictureIsI16AndSmall() {
        val sps = IdrSplice.parseSps(hex("6742c00ada189b0110000003001000000303c0f1226a"))
        val pps = IdrSplice.parsePps(hex("68ce0f2c80"))
        val w = sps.codedW
        val h = sps.codedH
        val flat = IdrSplice.Picture(w, h, ByteArray(w * h) { 77 }, ByteArray(w * h / 4) { 100 }, ByteArray(w * h / 4) { -100 })
        val r = IdrSplice.buildIdr(flat, sps, pps, 0)
        assertEquals(w * h / 256 - 1, r.i16)                    // у первого макроблока соседей нет: DC = 128, не 77
        assertEquals(1, r.pcm)
        assertTrue("${r.nal.size} Б", r.nal.size < 500)
        val noise = java.util.Random(1)
        val rough = IdrSplice.Picture(w, h, ByteArray(w * h).also { noise.nextBytes(it) }, ByteArray(w * h / 4).also { noise.nextBytes(it) }, ByteArray(w * h / 4).also { noise.nextBytes(it) })
        val r2 = IdrSplice.buildIdr(rough, sps, pps, 0)
        assertEquals(w * h / 256, r2.pcm)
        assertTrue(r2.nal.size >= w * h * 3 / 2)
    }

    @Test
    fun manyReferenceFramesAreRefused() {
        val sp = IdrSplicer()
        sp.onConfig(AnnexB.normalize(hex("000000016742c00adb11ec0440000003004000000f03c489b8" + "0000000168ca83cb20")))
        val sps = IdrSplice.parseSps(hex("6742c00adb11ec0440000003004000000f03c489b8"))
        assertEquals(2, sps.maxRef)
        assertNotNull(IdrSplice.unsupported(sps, IdrSplice.parsePps(hex("68ca83cb20"))))
        assertEquals("кадров AA ещё не было", sp.blocker())
    }
}

/** Декодер опорных картинок для тестов моста: только записывает, картинки отдаёт тест через onRefPicture. */
class FakeRefDecoder : RefPictureDecoder {
    val fed = CopyOnWriteArrayList<Long>()
    val configs = CopyOnWriteArrayList<ByteArray>()
    var stopped = false
    override fun feedConfig(annexB: ByteArray) {
        configs.add(annexB)
    }

    override fun feed(annexB: ByteArray, seq: Long) {
        fed.add(seq)
    }

    override fun stop() {
        stopped = true
    }
}

class IdrSpliceBridgeTest {
    private var now = 0L
    private val link = TranscodeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val dec = FakeRefDecoder()
    private var factoryCalls = 0
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also {
        it.transcodeEnabled = false
        it.idrSpliceMs = 2000
        it.refDecoderFactory = { _ -> factoryCalls++; dec }
        it.attach(link)
    }
    private val split = SpliceAssets.split()
    private val cfg = split.first
    private val aus = split.second

    /** Декодер дошёл до картинки seq, но не копировал её (она уже не последняя или не нужна). */
    private fun passed(seq: Long) {
        core.wantsRefPicture(seq)
    }

    /** Декодер «выдал» картинку seq (номер кадра AA с 1) = кадр seq-1 потока. */
    private fun decoded(seq: Long) {
        assertTrue(core.wantsRefPicture(seq))
        core.onRefPicture(seq, SpliceAssets.picture((seq - 1).toInt()))
    }

    private fun startWith(frames: Int) {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        for (k in 0 until frames) core.onFrame(aus[k], k.toLong())
    }

    @Test
    fun motionHoldsNextFrameUntilOwnIdrAndShiftsIt() {
        startWith(4)
        assertEquals(4, link.sent.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), dec.fed)
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR: включён") })
        passed(4)
        now += 2000
        core.onFrame(aus[4], 4)                                 // пора: картинка #5 станет нашим IDR
        assertEquals(5, link.sent.size)
        core.onFrame(aus[5], 5)                                 // придержан до IDR
        assertEquals(5, link.sent.size)
        assertEquals(5, dec.fed.size)                           // и в декодер пока не ушёл
        decoded(5)
        assertEquals(7, link.sent.size)
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[5]))     // первый свой IDR - с SPS/PPS
        assertFalse(aus[5].contentEquals(link.sent[6]))         // frame_num сдвинут
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent[6]))
        assertEquals(6, dec.fed.size)
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR #1") })
        // следующий - через 2 с, голый
        now += 2000
        core.onFrame(aus[6], 6)
        decoded(7)
        assertEquals(listOf(5), SpliceAssets.nalTypes(link.sent.last()))
    }

    @Test
    fun staticScreenGetsIdrFromTick() {
        startWith(3)
        decoded(3)                                              // картинка последнего кадра уже есть
        now += 1000; core.tick()
        assertEquals(3, link.sent.size)
        now += 1000; core.tick()
        assertEquals(4, link.sent.size)
        assertEquals(5, SpliceAssets.nalTypes(link.sent.last()).last())
        now += 2000; core.tick()                                // экран всё ещё статичный: снова тот же кадр
        assertEquals(5, link.sent.size)
        core.onFrame(aus[3], 3)
        assertEquals(6, link.sent.size)
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent.last()))
    }

    @Test
    fun lostFrameRecoversWithOwnIdrWithoutAaCycle() {
        startWith(3)
        link.accept = false
        core.onFrame(aus[3], 3)                                 // ГУ кадр не приняла: P-кадры дальше нельзя
        link.accept = true
        val requests = core.keyframeRequests
        passed(4)
        now += 100
        core.onFrame(aus[4], 4)
        decoded(5)
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent.last()))
        assertTrue(logs.toString(), logs.any { it.contains("ГУ была без IDR") })
        core.onFrame(aus[5], 5)
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent.last()))
        now += 1000; core.tick()
        assertEquals(requests, core.keyframeRequests)            // цикл VideoFocus к AA не понадобился
    }

    @Test
    fun missingPictureReleasesHeldFrames() {
        startWith(3)
        passed(3)
        now += 2000
        core.onFrame(aus[3], 3)
        core.onFrame(aus[4], 4)
        core.onFrame(aus[5], 5)
        assertEquals(4, link.sent.size)
        now += 400; core.tick()
        assertEquals(6, link.sent.size)
        assertArrayEquals(aus[5], link.sent.last())              // нашего IDR не было: кадры как есть
        assertTrue(logs.toString(), logs.any { it.contains("не пришла из декодера") })
    }

    @Test
    fun transcodeModeDoesNotSplice() {
        core.transcodeEnabled = true
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(aus[0], 0)
        assertEquals(0, factoryCalls)
        assertTrue(dec.fed.isEmpty())
    }

    @Test
    fun aaReconnectStopsDecoder() {
        startWith(2)
        core.onAaEnded(true)
        assertTrue(dec.stopped)
    }
}
