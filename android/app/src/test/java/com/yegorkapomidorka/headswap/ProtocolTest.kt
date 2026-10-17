package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.FpsSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Байтовые примеры взяты из docs/carlife-protocol.md §9.2. */
class ProtocolTest {

    private fun hex(s: String): ByteArray {
        val t = s.replace(" ", "")
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun frame(chan: Int, inner: ByteArray) = Wire.outerHeader(chan, inner.size) + inner

    @Test
    fun huProtocolVersion() {
        val body = Msgs.protocolVersion(1, 0)
        assertArrayEquals(hex("08 01 10 00"), body)
        val inner = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, body)
        assertArrayEquals(hex("00 04 00 00 00 01 80 01 08 01 10 00"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 0C"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun matchStatus() {
        val inner = Wire.cmdInner(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1))
        assertArrayEquals(hex("00 02 00 00 00 01 00 02 08 01"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 0A"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun videoEncoderInit() {
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_INIT, Msgs.videoEncoderInfo(1280, 720, 0))
        assertArrayEquals(hex("00 08 00 00 00 01 80 07 08 80 0A 10 D0 05 18 00"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 10"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun videoEncoderInitDone() {
        // спека §9.1 шаг 8: 08 80 0A 10 D0 05 18 1E (1280x720, 30)
        assertArrayEquals(hex("08 80 0A 10 D0 05 18 1E"), Msgs.videoEncoderInfo(1280, 720, 30))
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(1280, 720, 30))
        assertArrayEquals(hex("00 08 00 00 00 01 00 08 08 80 0A 10 D0 05 18 1E"), inner)
    }

    @Test
    fun videoEncoderStartEmptyBody() {
        val inner = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        assertArrayEquals(hex("00 00 00 00 00 01 80 09"), inner)
        assertArrayEquals(hex("00 00 00 01 00 00 00 08"), Wire.outerHeader(Ch.CMD, inner.size))
    }

    @Test
    fun foregroundIsEightBytes() {
        assertArrayEquals(hex("00 00 00 00 00 01 00 1B"), Wire.cmdInner(Msg.FOREGROUND, null))
    }

    @Test
    fun authenMessages() {
        assertArrayEquals(hex("08 01"), Msgs.authenResult(true))
        assertArrayEquals(hex("08 00"), Msgs.authenResult(false))
        assertArrayEquals(hex("00 02 00 00 00 01 00 4B 08 01"), Wire.cmdInner(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        // пустой encryptValue: тег 0x0A, длина 0
        assertArrayEquals(hex("0A 00"), Msgs.authenResponse(""))
        assertArrayEquals(hex("0A 03 61 62 63"), Msgs.authenResponse("abc"))
        val req = PbReader.parse(Msgs.authenRequest("CarLife_1.0.0"))!!
        assertEquals("CarLife_1.0.0", PbReader.str(req, 1))
        assertEquals(
            "{randomValue=\"CarLife_1.0.0\"}",
            PbText.render(Msgs.authenRequest("CarLife_1.0.0"), Schemas.authenRequest)
        )
    }

    @Test
    fun touchActionDown() {
        val body = hex("08 00 10 80 05 18 E8 02")
        assertArrayEquals(body, Msgs.touchAction(0, 640, 360))
        val inner = Wire.cmdInner(Msg.TOUCH_ACTION, body)
        assertArrayEquals(hex("00 08 00 00 00 06 80 01 08 00 10 80 05 18 E8 02"), inner)
        assertArrayEquals(hex("00 00 00 06 00 00 00 10"), Wire.outerHeader(Ch.TOUCH, inner.size))

        val m = Wire.parseCmd(inner)!!
        assertEquals(Msg.TOUCH_ACTION, m.type)
        val f = PbReader.parse(m.body)!!
        assertEquals(0, PbReader.int(f, 1))
        assertEquals(640, PbReader.int(f, 2))
        assertEquals(360, PbReader.int(f, 3))
        assertEquals("{action=0 x=640 y=360}", PbText.render(m.body, Schemas.touchAction))
    }

    @Test
    fun touchActionParse() {
        // один палец: тело 8 байт, pointer-полей нет
        val one = CarlifeTouch.parse(Msg.TOUCH_ACTION, Msgs.touchAction(2, 640, 360))!!
        assertEquals(CarlifeTouch(2, 640, 360), one)
        assertEquals(1, one.fingers)

        // POINTER_DOWN индекса 1 (261) со вторым пальцем в полях 4,5
        val body = Msgs.touchAction(261, 100, 200, 300, 400)
        assertArrayEquals(hex("08 85 02 10 64 18 C8 01 20 AC 02 28 90 03"), body)
        val two = CarlifeTouch.parse(Msg.TOUCH_ACTION, body)!!
        assertEquals(CarlifeTouch(261, 100, 200, 300, 400), two)
        assertEquals(5, two.masked)
        assertEquals(2, two.fingers)
        assertEquals("{action=261 x=100 y=200 pointerx=300 pointery=400}", PbText.render(body, Schemas.touchAction))

        // MOVE двумя пальцами
        assertEquals(2, CarlifeTouch.parse(Msg.TOUCH_ACTION, Msgs.touchAction(2, 1, 2, 3, 4))!!.fingers)
        // POINTER_UP индекса 0 без координат второго: всё равно два пальца
        val up0 = CarlifeTouch.parse(Msg.TOUCH_ACTION, Msgs.touchAction(6, 1, 2))!!
        assertEquals(6, up0.action)
        assertEquals(2, up0.fingers)
    }

    @Test
    fun touchGestureParse() {
        val p = PbWriter().int32(1, 10).int32(2, 20).toByteArray()
        assertEquals(CarlifeTouch(0, 10, 20), CarlifeTouch.parse(Msg.TOUCH_ACTION_DOWN, p))
        assertEquals(CarlifeTouch(3, 10, 20), CarlifeTouch.parse(Msg.TOUCH_SINGLE_CLICK, p))
        assertEquals(CarlifeTouch(261, 10, 20), CarlifeTouch.parse(Msg.TOUCH_ACTION_POINTER_DOWN, p))
        assertEquals(CarlifeTouch(6, 10, 20), CarlifeTouch.parse(Msg.TOUCH_ACTION_POINTER_UP, p))
        assertEquals(CarlifeTouch(262, 10, 20), CarlifeTouch.parse(Msg.TOUCH_ACTION_OTHERPOINTER_UP, p))
        val move2 = PbWriter().int32(1, 10).int32(2, 20).int32(3, 30).int32(4, 40).toByteArray()
        assertEquals(CarlifeTouch(2, 10, 20, 30, 40), CarlifeTouch.parse(Msg.TOUCH_ACTION_MOVE, move2))
        assertNull(CarlifeTouch.parse(Msg.TOUCH_CAR_HARD_KEY_CODE, p))
    }

    @Test
    fun videoDataHeader() {
        val h264 = hex("00 00 00 01 67 AA 00 00 00 01 68 BB 00 00 00 01 65 CC DD")
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 0x11223344, h264)
        assertArrayEquals(hex("00 00 00 13  11 22 33 44  00 02 00 01") + h264, inner)
        val m = Wire.parseData(inner)!!
        assertEquals(Msg.VIDEO_DATA, m.type)
        assertEquals(0x11223344, m.time)
        assertEquals(h264.size, m.declaredLen)
        assertArrayEquals(h264, m.data)
        assertFalse(m.truncated)
        // heartbeat: len 0, тип 0x00020002
        assertArrayEquals(hex("00 00 00 00  00 00 00 01  00 02 00 02"), Wire.dataInner(Msg.VIDEO_HEARTBEAT, 1, null))
    }

    @Test
    fun parserHandlesAnySplit() {
        val a = frame(Ch.CMD, Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0)))
        val b = frame(Ch.TOUCH, Wire.cmdInner(Msg.TOUCH_ACTION, Msgs.touchAction(0, 640, 360)))
        val c = frame(Ch.CMD, Wire.cmdInner(Msg.VIDEO_ENCODER_START, null))
        val all = a + b + c

        for (step in listOf(1, 2, 3, 7, 8, 9, 16, 1000)) {
            val p = FrameParser()
            val got = ArrayList<Pair<Int, ByteArray>>()
            var o = 0
            while (o < all.size) {
                val n = minOf(step, all.size - o)
                p.feed(all, o, n) { ch, inner -> got.add(ch to inner) }
                o += n
            }
            assertEquals("step=$step", 3, got.size)
            assertEquals(Ch.CMD, got[0].first)
            assertEquals(Ch.TOUCH, got[1].first)
            assertEquals(Msg.TOUCH_ACTION, Wire.parseCmd(got[1].second)!!.type)
            assertEquals(Msg.VIDEO_ENCODER_START, Wire.parseCmd(got[2].second)!!.type)
            assertEquals(0, p.pending())
        }
    }

    @Test
    fun parserDetectsDesync() {
        val p = FrameParser()
        var thrown = false
        try {
            // chan = 0xFFFFFFFF
            p.feed(hex("FF FF FF FF 00 00 00 04 00 00 00 00"), 0, 12) { _, _ -> }
        } catch (e: ProtocolDesync) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun writeSequenceSplitsHeaderAndChunks() {
        val small = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val s = Wire.writeSequence(Ch.CMD, small)
        assertEquals(2, s.size)
        assertEquals(8, s[0].size)
        assertArrayEquals(small, s[1])

        val big = Wire.dataInner(Msg.VIDEO_DATA, 0, ByteArray(40000) { 1 })
        val seq = Wire.writeSequence(Ch.VIDEO, big)
        assertEquals(8, seq[0].size)
        assertEquals(big.size, Wire.u32(seq[0], 4))
        assertEquals(Ch.VIDEO, Wire.u32(seq[0], 0))
        assertTrue(seq.drop(1).all { it.size <= 16384 })
        assertEquals(4, seq.size) // 8 + 3 куска: 16384 + 16384 + 7256
        val joined = seq.drop(1).fold(ByteArray(0)) { acc, x -> acc + x }
        assertArrayEquals(big, joined)
    }

    @Test
    fun cmdTruncationAndShort() {
        assertNull(Wire.parseCmd(hex("00 00 00")))
        val m = Wire.parseCmd(hex("00 05 00 00 00 01 80 01 08 01"))!!
        assertTrue(m.truncated)
        assertEquals(2, m.body.size)
        assertEquals(5, m.declaredLen)
    }

    @Test
    fun deviceInfoRoundTrip() {
        val info = Msgs.deviceInfo(DeviceInfo(os = "Android", brand = "Xiaomi", sdk = "36", sdkInt = 36, serial = "unknown"))
        val f = PbReader.parse(info)!!
        assertEquals("Android", PbReader.str(f, 1))
        assertEquals("Xiaomi", PbReader.str(f, 4))
        assertEquals("unknown", PbReader.str(f, 16))
        assertEquals("36", PbReader.str(f, 20))
        assertEquals(36, PbReader.int(f, 21))
        assertNull(PbReader.str(f, 2)) // пустые поля не пишутся
    }

    @Test
    fun negativeInt32IsTenBytes() {
        val b = PbWriter().int32(1, -1).toByteArray()
        assertArrayEquals(hex("08 FF FF FF FF FF FF FF FF FF 01"), b)
        assertEquals(-1, PbReader.int(PbReader.parse(b)!!, 1))
    }

    @Test
    fun featureConfigNestedRender() {
        // cnt=1, featureConfig{key="VOICE_MIC", value=1}
        val inner = PbWriter().string(1, "VOICE_MIC").int32(2, 1).toByteArray()
        val body = PbWriter().int32(1, 1).bytes(2, inner).toByteArray()
        assertEquals(
            "{cnt=1 featureConfig={key=\"VOICE_MIC\" value=1}}",
            PbText.render(body, Schemas.featureConfigList)
        )
    }

    @Test
    fun annexBFixSpsExynos() {
        // c2.exynos 1280x720: 67 42 00 34 -> подпись c2.qti 67 42 80 1F, PPS и IDR не трогаем
        val exynos = hex("00 00 00 01 67 42 00 34 AB CD 00 00 00 01 68 CE 3C 80 00 00 00 01 65 88 84")
        val fixed = AnnexB.fixSps(exynos, 1280, 720)
        assertArrayEquals(hex("00 00 00 01 67 42 80 1F AB CD 00 00 00 01 68 CE 3C 80 00 00 00 01 65 88 84"), fixed)
        assertArrayEquals(hex("00 00 00 01 67 42 00 34 AB CD 00 00 00 01 68 CE 3C 80 00 00 00 01 65 88 84"), exynos)
        // 1728x648 и 1920x720 больше 3600 макроблоков: level 4.0
        assertEquals(0x28, AnnexB.fixSps(exynos, 1728, 648)[7].toInt())
        assertEquals(0x28, AnnexB.fixSps(exynos, 1920, 720)[7].toInt())
        // 2240x976 (8540 MB) > MaxFS level 4.0: level 4.2; 2560x1440 (14400 MB): level 5.0
        assertEquals(0x2A, AnnexB.fixSps(exynos, 2240, 976)[7].toInt())
        assertEquals(0x32, AnnexB.fixSps(exynos, 2560, 1440)[7].toInt())
        assertEquals(0x28, AnnexB.fixSps(exynos, 1920, 1080)[7].toInt())
        // qti (80/31), mtk (level 40), AA passthrough (00/31) и кадры без SPS - тот же массив
        for (s in listOf("00 00 00 01 67 42 80 1F AB", "00 00 00 01 67 42 C0 28 AB", "00 00 00 01 67 42 00 1F AB", "00 00 00 01 41 9A 00 34")) {
            val d = hex(s)
            assertTrue(AnnexB.fixSps(d, 1280, 720) === d)
        }
        // c2.qti на 2240x976 уже пишет 80/42: менять нечего, тот же массив (h57: копия без отличий валила заглушку)
        val qti976 = hex("00 00 00 01 67 42 80 2A AB")
        assertTrue(AnnexB.fixSps(qti976, 2240, 976) === qti976)
        // Huawei Pura 70 на 2560x1440: 67 42 C0 32 уже совпадает с целевой подписью - тот же массив (h61: копия без отличий
        // роняла withFixedSps на first{})
        val kirin = hex("00 00 00 01 67 42 C0 32 AB 00 00 00 01 68 CE")
        assertTrue(AnnexB.fixSps(kirin, 2560, 1440) === kirin)
        // тот же SPS на 1280x720: level понижается до 3.1
        assertArrayEquals(hex("00 00 00 01 67 42 C0 1F AB 00 00 00 01 68 CE"), AnnexB.fixSps(kirin, 1280, 720))
    }

    @Test
    fun annexBNormalizeAndGlue() {
        val three = hex("00 00 01 67 AA 00 00 01 68 BB 00 00 00 01 65 CC")
        val norm = AnnexB.normalize(three)
        assertArrayEquals(hex("00 00 00 01 67 AA 00 00 00 01 68 BB 00 00 00 01 65 CC"), norm)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(norm))
        assertTrue(AnnexB.startsWithSps(norm))
        // уже 4-байтные не трогаем
        val four = hex("00 00 00 01 41 11 22")
        assertTrue(AnnexB.normalize(four) === four)
        assertFalse(AnnexB.startsWithSps(four))

        val csd = hex("00 00 00 01 67 AA 00 00 00 01 68 BB")
        val idr = hex("00 00 00 01 65 CC DD")
        val glued = AnnexB.glue(csd, idr)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(glued))
        assertEquals(csd.size + idr.size, glued.size)
    }

    @Test
    fun annexBNalTypesFromOffsetInCarlifeMessage() {
        // тело VIDEO_DATA внутри inner: заголовок [len][time][type 00 02 00 01] не должен дать ложный старт-код
        val frame = hex("00 00 00 01 67 AA 00 00 00 01 68 BB 00 00 01 65 CC")
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 1, frame)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(inner, 12))
        assertEquals(AnnexB.nalTypes(frame), AnnexB.nalTypes(inner, 12))
        // первый код 3-байтный сразу на границе: ноль заголовка перед ним не превращает его в 4-байтный за пределами тела
        val p = hex("00 00 01 41 11")
        assertEquals(listOf(1), AnnexB.nalTypes(Wire.dataInner(Msg.VIDEO_DATA, 0, p), 12))
        assertEquals(emptyList<Int>(), AnnexB.nalTypes(Wire.dataInner(Msg.VIDEO_DATA, 0, ByteArray(0)), 12))
    }

    @Test
    fun mdInfoLiesSdk29OnAndroid13Plus() {
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(36))
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(33))
        assertEquals(Pair("32", 32), DeviceInfo.reportedSdk(32))
    }

    private fun mdInfo(): DeviceInfo {
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(36)
        return DeviceInfo(
            os = "Android", brand = "Xiaomi", model = "TestPhone-1", manufacturer = "Xiaomi", release = "16",
            sdk = sdk, sdkInt = sdkInt, serial = "unknown", btaddress = "unknown",
            carlifeVersion = Handshake.MD_VERSION, carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    @Test
    fun mdInfoBody() {
        val body = Msgs.deviceInfo(mdInfo())
        assertTrue(body.isNotEmpty())
        val f = PbReader.parse(body)!!
        assertEquals("Android", PbReader.str(f, 1))
        assertEquals("29", PbReader.str(f, 20))
        assertEquals(29, PbReader.int(f, 21))
        assertEquals("unknown", PbReader.str(f, 23))
        assertEquals("8.6.7", PbReader.str(f, 24))
        assertEquals(1, PbReader.int(f, 25))
        assertEquals("Xiaomi", PbReader.str(f, 4))
        val txt = PbText.render(body, Schemas.deviceInfo)
        assertTrue(txt.contains("carlifeversion=\"8.6.7\""))
        assertTrue(txt.contains("carlifeType=1"))
    }

    @Test
    fun moduleStatusIs44BytesForHuV2() {
        val body = Msgs.moduleStatusList(2)
        assertEquals(44, body.size)
        val f = PbReader.parse(body)!!
        assertEquals(7, PbReader.int(f, 1))
        val pairs = PbReader.all(f, 2).map { PbReader.parse(it.data!!)!! }.map { PbReader.int(it, 1) to PbReader.int(it, 2) }
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 9).map { it to 0 }, pairs)
        // первая запись целиком: moduleStatus{1,0}
        assertArrayEquals(hex("08 07 12 04 08 01 10 00"), body.copyOf(8))
        // ГУ major 1: без 8 и 9
        assertEquals(5, PbReader.int(PbReader.parse(Msgs.moduleStatusList(1))!!, 1))
        val inner = Wire.cmdInner(Msg.MODULE_STATUS, body)
        assertEquals(0x2C, Wire.u16(inner, 0))
        assertEquals(0x00010026, Wire.u32(inner, 4))
    }

    @Test
    fun subscribeDoneMirrorsRequest() {
        // запрос из живого лога: cnt=1 {moduleID=0}
        assertArrayEquals(hex("08 01 12 04 08 00 10 01"), Msgs.subscribeDone(hex("08 01 12 02 08 00")))
        // 0 -> true, 1 -> false, 2 -> true, 3 -> false
        val req = PbWriter().int32(1, 4)
            .bytes(2, PbWriter().int32(1, 0).toByteArray())
            .bytes(2, PbWriter().int32(1, 1).toByteArray())
            .bytes(2, PbWriter().int32(1, 2).toByteArray())
            .bytes(2, PbWriter().int32(1, 3).toByteArray())
            .toByteArray()
        val done = Msgs.subscribeDone(req)!!
        val f = PbReader.parse(done)!!
        assertEquals(4, PbReader.int(f, 1))
        val flags = PbReader.all(f, 2).map { PbReader.parse(it.data!!)!! }.map { PbReader.int(it, 1) to PbReader.int(it, 2) }
        assertEquals(listOf(0 to 1, 1 to 0, 2 to 1, 3 to 0), flags)
        // на мусор не отвечаем
        assertNull(Msgs.subscribeDone(hex("12 05 01")))
        assertEquals(listOf(0), Msgs.subscribeIds(hex("08 01 12 02 08 00")))
        val inner = Wire.cmdInner(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
        assertEquals(0x00010044, Wire.u32(inner, 4))
    }

    @Test
    fun startBatchOrder() {
        val batch = Handshake.startBatch(true, mdInfo())
        assertEquals(
            listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.SCREEN_ON, Msg.MD_INFO),
            batch.map { it.type }
        )
        assertEquals(listOf(0x10002, 0x1001B, 0x10018, 0x10004), batch.map { it.type })
        assertArrayEquals(hex("08 01"), batch[0].body)
        assertNull(batch[1].body)
        assertNull(batch[2].body)
        assertTrue(batch[3].body!!.isNotEmpty())
        // экран выключен: SCREEN_ON не шлём
        assertEquals(
            listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.MD_INFO),
            Handshake.startBatch(false, mdInfo()).map { it.type }
        )
        // пустые тела: 8 байт, len=0, resv=0
        assertArrayEquals(hex("00 00 00 00 00 01 00 18"), Wire.cmdInner(batch[2].type, batch[2].body))
        // MODULE_STATUS потом, через 500 мс
        assertEquals(500L, Handshake.MODULE_STATUS_DELAY_MS)
        assertEquals(Msg.MODULE_STATUS, Handshake.moduleStatus(2).type)
    }

    @Test
    fun statisticInfoReplyOrder() {
        val r = Handshake.onStatisticInfo(true)
        assertEquals(listOf(0x1004B, 0x10051, 0x10031), r.map { it.type })
        assertArrayEquals(hex("08 01"), r[0].body)
        assertNull(r[1].body)
        assertNull(r[2].body)
        assertEquals(listOf(0x1004B, 0x10031), Handshake.onStatisticInfo(false).map { it.type })
    }

    @Test
    fun authV2Marker() {
        assertTrue(Handshake.isAuthV2("2.0;abcdef"))
        assertFalse(Handshake.isAuthV2("CarLife_1.0.0"))
        assertFalse(Handshake.isAuthV2(null))
    }

    // ---------------- сборка inner по каналам ----------------

    private class Got(val chan: Int, val msg: CmdMsg)

    private fun runAssembler(all: ByteArray, step: Int): List<Got> {
        val p = FrameParser()
        val a = InnerAssembler()
        val got = ArrayList<Got>()
        var o = 0
        while (o < all.size) {
            val n = minOf(step, all.size - o)
            p.feed(all, o, n) { ch, inner -> a.feed(ch, inner) { c, i -> got.add(Got(c, Wire.parseCmd(i)!!)) } }
            o += n
        }
        assertEquals("step=$step pending", 0, p.pending())
        assertEquals("step=$step waiting", 0, a.waiting())
        return got
    }

    @Test
    fun splitVersionGivesMajor2AndFullModuleList() {
        // VW Tharu XR 2026: HU_PROTOCOL_VERSION заголовком и телом в разных кадрах; раньше читалось null.null -> модули как для 1.x
        val got = runAssembler(hex("00 00 00 01 00 00 00 08 00 04 00 64 00 01 80 01 00 00 00 01 00 00 00 04 08 02 10 01"), 3)
        assertEquals(1, got.size)
        val f = PbReader.parse(got[0].msg.body)!!
        assertEquals(2, PbReader.int(f, 1))
        assertEquals(1, PbReader.int(f, 2))
        val ids = PbReader.parse(Msgs.moduleStatusList(PbReader.int(f, 1)!!))!!
            .filter { it.number == 2 }.map { PbReader.int(PbReader.parse(it.data!!)!!, 1) }
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 9), ids)
    }

    @Test
    fun assemblerJoinsSplitHeaderAndBody() {
        val rnd = "txGaIEnzYVCaFOEArS9O_0"
        val all = hex("00 00 00 01 00 00 00 08  00 04 00 64 00 01 80 01") +
            hex("00 00 00 01 00 00 00 04  08 02 10 01") +
            hex("00 00 00 01 00 00 00 08  00 18 00 64 00 01 80 48") +
            hex("00 00 00 01 00 00 00 18  0A 16") + rnd.toByteArray(Charsets.UTF_8)
        for (step in listOf(1, 2, 3, 7, 8, 9, 12, 16, 1000)) {
            val got = runAssembler(all, step)
            assertEquals("step=$step", 2, got.size)
            assertEquals(Ch.CMD, got[0].chan)
            assertEquals(Msg.HU_PROTOCOL_VERSION, got[0].msg.type)
            assertEquals(100, got[0].msg.resv)
            assertFalse(got[0].msg.truncated)
            assertArrayEquals(hex("08 02 10 01"), got[0].msg.body)
            assertEquals(Msg.HU_AUTHEN_REQUEST, got[1].msg.type)
            assertFalse(got[1].msg.truncated)
            assertEquals(24, got[1].msg.body.size)
            assertEquals(rnd, PbReader.str(PbReader.parse(got[1].msg.body)!!, 1))
        }
    }

    @Test
    fun assemblerSplitsTailOfBodyWithFollowingMessages() {
        // VW Tharu XR 2026, лог 2026-10-11 15:02:46: заголовок MODULE_CONTROL отдельно, следом 154 байта =
        // тело MODULE_CONTROL + HU_AUTHEN_RESULT + STATISTIC_INFO + HU_INFO; раньше всё после тела терялось
        val rest = hex(
            "08 06 10 00 00 02 00 68 00 01 80 4A 08 01 00 31 00 68 00 01 80 27 0A 14 " +
                "56 57 58 39 47 45 30 31 30 32 34 36 31 20 20 20 20 20 20 20 12 03 32 2E 31 18 02 22 08 32 30 30 " +
                "38 32 31 32 32 28 01 30 01 38 F1 AC 01 42 00 00 4B 00 68 00 01 80 03 0A 05 4C 69 6E 75 78 12 00 " +
                "1A 00 22 00 2A 00 32 00 42 00 4A 00 52 00 5A 00 62 00 6A 0A 56 6F 6C 6B 73 77 61 67 65 6E 72 04 " +
                "43 4E 53 33 7A 00 82 01 00 8A 01 00 92 01 00 9A 01 04 30 38 37 35 A2 01 00 A8 01 08 B2 01 00 BA 01 00",
        )
        assertEquals(154, rest.size)
        val all = frame(Ch.CMD, hex("00 04 00 68 00 01 80 28")) + frame(Ch.CMD, rest)
        for (step in listOf(1, 7, 16, 1000)) {
            val got = runAssembler(all, step)
            assertEquals(
                "step=$step",
                listOf(Msg.MODULE_CONTROL, Msg.HU_AUTHEN_RESULT, Msg.STATISTIC_INFO, Msg.HU_INFO),
                got.map { it.msg.type },
            )
            assertTrue(got.none { it.msg.truncated })
            assertArrayEquals(hex("08 01"), got[1].msg.body)
            assertEquals("2.1", PbReader.str(PbReader.parse(got[2].msg.body)!!, 2))
        }
    }

    @Test
    fun assemblerSplitsWholeMessageWithTrailingHeader() {
        // тот же ГУ, 15:03:06: кадр = MODULE_CONTROL целиком + заголовок HU_AUTHEN_RESULT, тело следующим кадром
        val all = frame(Ch.CMD, hex("00 04 00 68 00 01 80 28 08 06 10 00 00 02 00 68 00 01 80 4A")) +
            frame(Ch.CMD, hex("08 01")) +
            frame(Ch.CMD, Wire.cmdInner(Msg.STATISTIC_INFO, null))
        val got = runAssembler(all, 1000)
        assertEquals(listOf(Msg.MODULE_CONTROL, Msg.HU_AUTHEN_RESULT, Msg.STATISTIC_INFO), got.map { it.msg.type })
        assertArrayEquals(hex("08 01"), got[1].msg.body)
    }

    @Test
    fun assemblerKeepsFrameWithForeignTailWhole() {
        // хвост после первого inner не похож на заголовок этого канала -> кадр как есть, ничего не удерживается
        val whole = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(2, 0)) + hex("01 02 03 04 05 06 07 08 09")
        val got = runAssembler(frame(Ch.CMD, whole), 1000)
        assertEquals(1, got.size)
        assertEquals(Msg.HU_PROTOCOL_VERSION, got[0].msg.type)
    }

    @Test
    fun assemblerJoinsBodyInSeveralFramesAndKeepsChannelsApart() {
        val body = ByteArray(10) { (it + 1).toByte() }
        val hdr = hex("00 0A 00 00 00 01 80 01")
        val touch = Wire.cmdInner(Msg.TOUCH_ACTION, Msgs.touchAction(0, 640, 360))
        val all = frame(Ch.CMD, hdr) +
            frame(Ch.CMD, body.copyOfRange(0, 3)) +
            frame(Ch.TOUCH, touch) + // другой канал не мешает
            frame(Ch.CMD, body.copyOfRange(3, 4)) +
            frame(Ch.CMD, body.copyOfRange(4, 10))
        val got = runAssembler(all, 5)
        assertEquals(listOf(Ch.TOUCH, Ch.CMD), got.map { it.chan })
        assertEquals(Msg.TOUCH_ACTION, got[0].msg.type)
        assertArrayEquals(body, got[1].msg.body)
        assertFalse(got[1].msg.truncated)
    }

    @Test
    fun assemblerJoinsDataChannelHeaderAndBody() {
        val h264 = hex("00 00 00 01 67 AA BB")
        val full = Wire.dataInner(Msg.VIDEO_DATA, 5, h264)
        val all = frame(Ch.VIDEO, full.copyOfRange(0, 12)) + frame(Ch.VIDEO, h264)
        val p = FrameParser()
        val a = InnerAssembler()
        val got = ArrayList<ByteArray>()
        p.feed(all, 0, all.size) { c, i -> a.feed(c, i) { _, x -> got.add(x) } }
        assertEquals(1, got.size)
        assertArrayEquals(full, got[0])
        assertFalse(Wire.parseData(got[0])!!.truncated)
    }

    @Test
    fun assemblerPassesWholeFramesAsBefore() {
        // сообщение одним кадром; тело короче declared -> TRUNCATED как раньше; len=0 -> как есть
        val whole = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0))
        val bad = hex("00 05 00 00 00 01 80 01 08 01")
        val empty = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val all = frame(Ch.CMD, whole) + frame(Ch.CMD, bad) + frame(Ch.CMD, empty)
        val got = runAssembler(all, 1000)
        assertEquals(3, got.size)
        assertFalse(got[0].msg.truncated)
        assertTrue(got[1].msg.truncated)
        assertEquals(5, got[1].msg.declaredLen)
        assertEquals(Msg.VIDEO_ENCODER_START, got[2].msg.type)
        // чужой inner (меньше заголовка) тоже проходит без изменений
        val a = InnerAssembler()
        val seen = ArrayList<ByteArray>()
        a.feed(Ch.CMD, hex("00 01 02"), { _, i -> seen.add(i) })
        assertEquals(1, seen.size)
    }

    @Test
    fun assemblerDoesNotHoldAbsurdDeclaredLen() {
        val a = InnerAssembler()
        val seen = ArrayList<ByteArray>()
        // data: len = 0x7FFFFFFF > MAX_OUTER_LEN, и отрицательный (>= 2^31)
        a.feed(Ch.VIDEO, hex("7F FF FF FF 00 00 00 01 00 02 00 01"), { _, i -> seen.add(i) })
        a.feed(Ch.VIDEO, hex("FF FF FF FF 00 00 00 01 00 02 00 01"), { _, i -> seen.add(i) })
        assertEquals(2, seen.size)
        assertEquals(0, a.waiting())
    }

    // ---------------- ответ на HU_AUTHEN_REQUEST ----------------

    @Test
    fun versionVerifyLikeBaidu() {
        assertEquals("txGaIEnzYVCaFOEArS9O_00.00.00", HuAuth.versionVerify("txGaIEnzYVCaFOEArS9O_0"))
        // длины равны (3 и 3): версия ГУ больше нашей -> обнуляется
        assertEquals("abc_00.00.00", HuAuth.versionVerify("abc_9.0.0"))
        assertEquals("abc_00.00.00", HuAuth.versionVerify("abc_8.6.8"))
        // равна или меньше -> строка как есть
        assertEquals("abc_8.6.7", HuAuth.versionVerify("abc_8.6.7"))
        assertEquals("abc_8.6.6", HuAuth.versionVerify("abc_8.6.6"))
        assertEquals("abc_1.9.9", HuAuth.versionVerify("abc_1.9.9"))
        // старшая часть меньше решает, младшие не смотрим
        assertEquals("abc_8.5.99", HuAuth.versionVerify("abc_8.5.99"))
        // нечисловая часть пропускается (у Baidu исключение гасится)
        assertEquals("abc_x.6.7", HuAuth.versionVerify("abc_x.6.7"))
        // разная длина
        assertEquals("abc_00.00.00", HuAuth.versionVerify("abc_1.0"))
        // replace по всем вхождениям суффикса
        assertEquals("a_00.00.00", HuAuth.versionVerify("a_1.0.0b_1.0.0"))
        // без "_" подписывается как есть
        assertEquals("plain", HuAuth.versionVerify("plain"))
    }

    @Test
    fun v2BranchDetection() {
        assertTrue(HuAuth.isV2Branch("2.0;abcdef"))
        assertFalse(HuAuth.isV2Branch("txGaIEnzYVCaFOEArS9O_0"))
        assertFalse(HuAuth.isV2Branch("1.0;abc"))
        assertFalse(HuAuth.isV2Branch("2.0;")) // split даёт одну часть
    }

    @Test
    fun v2VerifyCodeLikeLibEncryption() {
        // md5("CARLIFE" + str) строчным hex
        assertEquals("2fce147802726d1aeae532116434339d", HuAuth.verifyCode("HUAWEIADY-LX9312026-10-14-12-01-33"))
        // запрос Honda CR-V 2026 (h61): ключ brand+model+sdk из MD_INFO + часть после ";"
        val key = HuAuth.mdInfoKey(DeviceInfo(brand = "HUAWEI", model = "ADY-LX9", sdk = "31", sdkInt = 31))
        assertEquals("HUAWEIADY-LX931", key)
        assertEquals("2fce147802726d1aeae532116434339d", HuAuth.encryptValue("2.0;2026-10-14-12-01-33", key))
        // третья часть после ";" в код не идёт (у Baidu strArrSplit[1])
        assertEquals(HuAuth.verifyCode("ab"), HuAuth.encryptValue("2.0;b;c", "a"))
    }

    @Test
    fun authSignatureDecryptsWithPublicKey() {
        val rnd = "txGaIEnzYVCaFOEArS9O_0"
        val enc = HuAuth.encryptValue(rnd)
        assertFalse(enc.contains("\n") || enc.contains("\r"))
        val sig = java.util.Base64.getDecoder().decode(enc)
        assertEquals(128, sig.size)
        val priv = HuAuth.privateKey as java.security.interfaces.RSAPrivateCrtKey
        val pub = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(priv.modulus, priv.publicExponent))
        val c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, pub)
        assertEquals("txGaIEnzYVCaFOEArS9O_00.00.00", String(c.doFinal(sig), Charsets.UTF_8))
        // тело ответа: поле 1, string
        val body = Msgs.authenResponse(enc)
        assertEquals(enc, PbReader.str(PbReader.parse(body)!!, 1))
        assertEquals(0x00010049, Msg.MD_AUTHEN_RESPONSE)
    }

    @Test
    fun featureConfigParse() {
        fun item(k: String, v: Int) = PbWriter().string(1, k).int32(2, v).toByteArray()
        val body = PbWriter().int32(1, 3)
            .bytes(2, item("CONTENT_ENCRYPTION", 1))
            .bytes(2, item("CONNECT_TYPE", 0))
            .bytes(2, item("BLUETOOTH_AUTO_PAIR", 1))
            .bool(3, true).string(4, "HU-BT").string(5, "AA:BB")
            .toByteArray()
        val fc = FeatureConfig.parse(body)!!
        assertEquals(3, fc.items.size)
        assertEquals(1, fc.value("CONTENT_ENCRYPTION"))
        assertEquals(0, fc.value("CONNECT_TYPE"))
        assertNull(fc.value("NOPE"))
        assertEquals(true, fc.huBtAudioSupport)
        assertEquals("HU-BT", fc.huBtName)
        assertEquals("AA:BB", fc.huBtMac)
    }

    @Test
    fun themeJpegAndVideoKeepalive() {
        assertArrayEquals(hex("08 00"), Msgs.themeState(0))
        assertEquals("RESPONSE_MD_THEME_STATE", Msg.name(0x10080))
        assertEquals("REQUEST_MD_THEME_STATE", Msg.name(0x18079))
        // keepalive после START: VIDEO_DATA len=0, time = unix-секунды
        assertArrayEquals(
            hex("00 00 00 00  65 00 00 00  00 02 00 01"),
            Wire.dataInner(Msg.VIDEO_DATA, 0x65000000, null)
        )
        assertTrue(Handshake.frameRateChangeAllowed(30))
        assertTrue(Handshake.frameRateChangeAllowed(3))
        assertFalse(Handshake.frameRateChangeAllowed(2))
        assertFalse(Handshake.frameRateChangeAllowed(31))
    }

    @Test
    fun chooseFpsAutoFollowsRequestInRange() {
        // Авто: как Baidu - fps из INIT в 1..30, иначе 30 (Honda шлёт 0, Kia 15, Mazda 30)
        val auto = FpsSetting.AUTO
        assertEquals(30, Handshake.chooseFps(auto, 0))
        assertEquals(15, Handshake.chooseFps(auto, 15))
        assertEquals(30, Handshake.chooseFps(auto, 30))
        assertEquals(30, Handshake.chooseFps(auto, 31))
        assertEquals(30, Handshake.chooseFps(auto, 60))
        assertEquals(1, Handshake.chooseFps(auto, 1))
        assertEquals(30, Handshake.chooseFps(auto, -1))
    }

    @Test
    fun chooseFpsFixedIgnoresRequest() {
        for (req in listOf(0, 15, 30, 31, 60)) {
            assertEquals(15, Handshake.chooseFps(FpsSetting.FPS15, req))
            assertEquals(30, Handshake.chooseFps(FpsSetting.FPS30, req))
        }
        assertEquals(FpsSetting.AUTO, FpsSetting.DEFAULT)
        assertEquals(FpsSetting.FPS15, FpsSetting.parse("FPS15"))
        assertEquals(FpsSetting.AUTO, FpsSetting.parse(null))
        assertEquals(FpsSetting.AUTO, FpsSetting.parse("мусор"))
    }

    @Test
    fun hexDump() {
        val d = Hex.dump(ByteArray(300) { it.toByte() }, 256)
        assertTrue(d.startsWith("0000  00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |"))
        assertTrue(d.contains("еще 44 байт"))
        assertEquals("00 01 02 ...(+3)", Hex.compact(ByteArray(6) { it.toByte() }, 3))
    }
}
