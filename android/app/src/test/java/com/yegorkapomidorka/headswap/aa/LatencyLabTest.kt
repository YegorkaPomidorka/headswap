package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class LatencyLabTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** SPS AA из записи pc-headunit: Baseline 1280x720, POC 2, VUI с bitstream_restriction (reorder 0, dec_buf 1). */
    private val aa720 = hex("6742801fda014016e9a8283028368509a8")
    /** Он же без VUI и с max_num_reorder_frames = 2, max_dec_frame_buffering = 3 (собраны вручную). */
    private val aa720NoVui = hex("6742801fda014016e4")
    private val aa720Reorder2 = hex("6742801fda014016e9a82830283682211648")
    private val x264Baseline1080 = hex("6742c028d900780227e5c044000003000400000300f03c60c920")

    @Test
    fun parsesAaVui() {
        val i = SpsVui.parse(aa720)
        assertEquals(66, i.profile)
        assertEquals(31, i.level)
        assertEquals(2, i.pocType)
        assertEquals(1, i.maxRef)
        assertTrue(i.vui)
        assertNull(i.timing)
        assertEquals(0, i.reorder)
        assertEquals(1, i.maxDecBuf)
    }

    @Test
    fun parsesTimingAndCrop() {
        val i = SpsVui.parse(x264Baseline1080)
        assertEquals("1/60", i.timing)
        assertEquals(3, i.maxRef)
        assertEquals(0, i.reorder)
        assertEquals(3, i.maxDecBuf)
        assertSame(x264Baseline1080, SpsVui.lowDelay(x264Baseline1080))        // и так без переупорядочивания
        assertSame(aa720, SpsVui.lowDelay(aa720))
    }

    @Test
    fun rewritesReorderInExistingRestriction() {
        assertEquals(2, SpsVui.parse(aa720Reorder2).reorder)
        val out = SpsVui.lowDelay(aa720Reorder2)
        assertArrayEquals(hex("6742801fda014016e9a82830283682211a80"), out)
        val i = SpsVui.parse(out)
        assertEquals(0, i.reorder)
        assertEquals(1, i.maxDecBuf)
        assertEquals(SpsCrop.parse(aa720Reorder2), SpsCrop.parse(out))
    }

    @Test
    fun addsVuiWhenMissing() {
        assertFalse(SpsVui.parse(aa720NoVui).vui)
        val out = SpsVui.lowDelay(aa720NoVui)
        assertArrayEquals(hex("6742801fda014016e806d0442350"), out)
        val i = SpsVui.parse(out)
        assertTrue(i.vui)
        assertEquals(0, i.reorder)
        assertEquals(1, i.maxDecBuf)
        val a = IdrSplice.parseSps(aa720NoVui)
        val b = IdrSplice.parseSps(out)
        assertEquals(a.wMbs to a.hMbs, b.wMbs to b.hMbs)
        assertEquals(a.pocType, b.pocType)
    }

    @Test
    fun queueModelGrowsOnlyWithExtraFrames() {
        val m = GuQueueModel(30)
        var t = 0L
        for (k in 0 until 900) {                                // 30 с ровно 30 fps
            t = k * 1000L / 30
            m.onFrame(t)
        }
        assertTrue("${m.delayMs(t)}", m.delayMs(t) <= 40)
        for (k in 900 until 2700) {                             // ещё 60 с, лишний кадр раз в 2 с
            t = k * 1000L / 30
            m.onFrame(t)
            if (k % 60 == 0) m.onFrame(t)
        }
        assertTrue("${m.delayMs(t)}", m.delayMs(t) in 900..1100)
        assertTrue(m.maxMs >= m.delayMs(t))
        assertEquals(0, m.delayMs(t + 1500))                    // пауза в потоке: очередь ушла
    }

    @Test
    fun stampText() {
        assertEquals("123.45", FrameStamp.text(1_700_000_123_456L))
        assertEquals("000.00", FrameStamp.text(1_000_000L))
        assertEquals(160 to 48, FrameStamp.size("123.45"))
    }

    @Test
    fun stampDrawsAlignedBox() {
        val w = 320
        val h = 96
        val pic = IdrSplice.Picture(w, h, ByteArray(w * h) { 100 }, ByteArray(w * h / 4) { 50 }, ByteArray(w * h / 4) { 60 })
        assertFalse(FrameStamp.draw(pic, 8, 16, "1.0"))         // не по макроблоку
        assertFalse(FrameStamp.draw(pic, 192, 16, "123.45"))    // не влезает
        assertTrue(FrameStamp.draw(pic, 160, 16, "123.45"))
        val y = pic.y
        assertEquals(100, y[15 * w + 160].toInt() and 0xFF)    // над плашкой не тронуто
        assertEquals(16, y[16 * w + 160].toInt() and 0xFF)     // фон плашки
        assertEquals(100, y[16 * w + 159].toInt() and 0xFF)
        val lit = (16 until 64).sumOf { yy -> (160 until 320).count { xx -> y[yy * w + xx].toInt() and 0xFF == 235 } }
        assertTrue("$lit", lit > 500)
        assertEquals(128, pic.u[8 * (w / 2) + 80].toInt() and 0xFF)
        assertEquals(50, pic.u[8 * (w / 2) + 79].toInt() and 0xFF)
    }

    @Test
    fun tuningDescribe() {
        assertTrue(SpliceTuning().isDefault)
        assertEquals("как в PR #115", SpliceTuning().describe())
        assertEquals("IDR вместо 2 кадров AA (выброс 1)", SpliceTuning(replace = true, drop = 1).describe())
        val d = SpliceTuning(replace = true, sps = SpliceSps.ALWAYS, slices = 2).describe()
        assertTrue(d, d.contains("вместо кадра AA") && d.contains("всегда") && d.contains("слайсов 2"))
    }
}

/** Варианты своего IDR в мосте ([SpliceTuning]) на потоке x264 из IdrSpliceTest. */
class IdrSpliceTuningTest {
    private var now = 0L
    private val link = TranscodeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val dec = FakeRefDecoder()
    private var queueBuilds = false
    private val builds = ArrayList<Runnable>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also {
        it.transcodeEnabled = false
        it.idrSpliceMs = 2000
        it.refDecoderFactory = { _ -> dec }
        it.spliceExecutor = Executor { r -> if (queueBuilds) builds.add(r) else r.run() }
        it.attach(link)
    }
    private val split = SpliceAssets.split()
    private val cfg = split.first
    private val aus = split.second

    private fun passed(seq: Long) {
        core.wantsRefPicture(seq)
    }

    private fun decoded(seq: Long) {
        assertTrue(core.wantsRefPicture(seq))
        core.onRefPicture(seq, SpliceAssets.picture((seq - 1).toInt()))
    }

    private fun startWith(frames: Int) {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        for (k in 0 until frames) core.onFrame(aus[k], k.toLong())
    }

    /** До первого своего IDR: кадры 1-5 ушли, кадр 6 (картинка #6) пришёл в момент, когда пора. */
    private fun runToSplice(): Int {
        startWith(4)
        core.tick()                                             // начало окна статистики
        passed(4)
        now = 1990
        core.onFrame(aus[4], 4)
        passed(5)
        now = 2000
        core.onFrame(aus[5], 5)
        val before = link.sent.size
        core.onFrame(aus[6], 6)                                 // придержан до IDR
        assertEquals(before, link.sent.size)
        decoded(6)
        return before
    }

    @Test
    fun defaultSpliceAddsExtraFrame() {
        runToSplice()
        assertEquals(8, link.sent.size)                         // 7 кадров AA + свой IDR
        now = 31_000; core.tick()
        assertTrue(logs.toString(), logs.any { it.contains("поток в ГУ") && it.contains("своих IDR 1 (вместо кадра AA 0, лишних 1") })
        assertTrue(logs.toString(), logs.any { it.contains("BRIDGE SPS AA: profile 66") })
    }

    @Test
    fun replaceSendsIdrInPlaceOfAaFrame() {
        core.tuning = SpliceTuning(replace = true)
        val before = runToSplice()
        assertEquals(5, before)                                 // кадр 6 не ушёл: его место займёт IDR
        assertEquals(7, link.sent.size)                         // кадров в ГУ столько же, сколько от AA
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[5]))
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent[6]))
        assertFalse(aus[6].contentEquals(link.sent[6]))         // frame_num сдвинут от IDR
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR #1") && it.contains("вместо кадра AA") })
        now = 31_000; core.tick()
        assertTrue(logs.toString(), logs.any { it.contains("своих IDR 1 (вместо кадра AA 1, лишних 0") })
    }

    /** IDR не успел: кадр, вместо которого его ждали, уходит первым, за ним придержанные. */
    @Test
    fun replaceReleasesReplacedFrameFirstWhenIdrIsLate() {
        core.tuning = SpliceTuning(replace = true)
        startWith(3)
        passed(3)
        now = 1990; core.onFrame(aus[3], 3)
        passed(4)
        queueBuilds = true
        now = 2000; core.onFrame(aus[4], 4)                     // заменяемый
        core.onFrame(aus[5], 5)                                 // придержан
        assertEquals(4, link.sent.size)
        decoded(5)
        assertEquals(1, builds.size)
        now = 2300; core.tick()
        assertEquals(6, link.sent.size)
        assertArrayEquals(aus[4], link.sent[4])
        assertArrayEquals(aus[5], link.sent[5])
        builds.removeAt(0).run()
        assertEquals(6, link.sent.size)                         // опоздавший IDR не уходит
    }

    /** Выброс 1: кадр перед картинкой IDR не уходит, IDR встаёт вместо двух кадров AA - в ГУ на кадр меньше. */
    @Test
    fun dropSendsIdrInPlaceOfTwoAaFrames() {
        core.tuning = SpliceTuning(drop = 1)
        startWith(4)
        core.tick()
        passed(4)
        now = 1990; core.onFrame(aus[4], 4)
        passed(5)
        now = 2000; core.onFrame(aus[5], 5)                     // выброшен
        assertEquals(5, link.sent.size)
        passed(6)
        core.onFrame(aus[6], 6)                                 // заменяемый
        core.onFrame(aus[7], 7)                                 // придержан
        assertEquals(5, link.sent.size)
        decoded(7)
        assertEquals(7, link.sent.size)                         // 8 кадров AA, в ГУ 7: IDR вместо кадров 6 и 7
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[5]))
        assertEquals(listOf(1, 1), SpliceAssets.nalTypes(link.sent[6]))
        assertTrue(logs.toString(), logs.any { it.contains("свой IDR #1") && it.contains("вместо 2 кадров AA") })
        now = 31_000; core.tick()
        assertTrue(logs.toString(), logs.any { it.contains("выброшено кадров AA 1 (за сессию 1), выброс отменён 0") })
    }

    /** Выброс начат, а AA замолчал: выброшенный кадр уходит как был. */
    @Test
    fun dropFlushesWhenAaStops() {
        core.tuning = SpliceTuning(drop = 1)
        startWith(4)
        passed(4)
        now = 1990; core.onFrame(aus[4], 4)
        passed(5)
        now = 2000; core.onFrame(aus[5], 5)
        assertEquals(5, link.sent.size)
        now = 2300; core.tick()
        assertEquals(6, link.sent.size)
        assertArrayEquals(aus[5], link.sent[5])
    }

    /** Статика (кадр после паузы) - сборка в фоне, кадр уходит сразу и в режиме замены. */
    @Test
    fun replaceKeepsBackgroundBuildAfterPause() {
        core.tuning = SpliceTuning(replace = true)
        startWith(3)
        passed(3)
        queueBuilds = true
        now = 2500
        core.onFrame(aus[3], 3)
        assertEquals(4, link.sent.size)
        assertArrayEquals(aus[3], link.sent.last())
    }

    @Test
    fun spsNeverSendsBareIdr() {
        core.tuning = SpliceTuning(sps = SpliceSps.NEVER)
        runToSplice()
        assertEquals(listOf(5), SpliceAssets.nalTypes(link.sent[6]))
    }

    @Test
    fun spsAlwaysGluesEveryIdr() {
        core.tuning = SpliceTuning(sps = SpliceSps.ALWAYS)
        runToSplice()
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent[6]))
        passed(7)
        now = 3990
        core.onFrame(aus[7], 7)
        passed(8)
        now = 4000
        core.onFrame(aus[8], 8)
        decoded(9)
        assertEquals(listOf(7, 8, 5), SpliceAssets.nalTypes(link.sent.last()))
    }

    /** Метка в кадре 96x64 не помещается: IDR уходит без неё, сборка не падает. */
    @Test
    fun stampSkippedOnTinyPicture() {
        core.tuning = SpliceTuning(stamp = true)
        core.wallMs = { 123_450L }
        runToSplice()
        assertEquals(8, link.sent.size)
        assertFalse(logs.toString(), logs.any { it.contains("BRIDGE метка") })
    }

    @Test
    fun lightAndSlicesStillSplice() {
        core.tuning = SpliceTuning(light = true, slices = 1, lowDelayVui = true)
        runToSplice()
        assertEquals(8, link.sent.size)
        assertEquals(5, SpliceAssets.nalTypes(link.sent[6]).last())
    }
}
