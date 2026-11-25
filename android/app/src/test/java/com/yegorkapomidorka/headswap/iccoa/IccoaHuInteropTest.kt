package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CustomVideoMode
import com.yegorkapomidorka.headswap.aa.FakePeer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Шов двух независимых реализаций: настоящая [IccoaSession] против эмулятора машины pc-headunit/iccoa_hu.py
 * (отдельный процесс python) по localhost. Нет python или пакета cryptography — тест пропускается.
 */
class IccoaHuInteropTest {
    private val loop = InetAddress.getByName("127.0.0.1")
    private val logs = CopyOnWriteArrayList<String>()
    private val sessions = ArrayList<IccoaSession>()
    private val out: File = Files.createTempDirectory("iccoa-interop").toFile()
    private var hu: Process? = null
    // auth, rtp — порты эмулятора; дальше порты телефона: control, audio, sensor, rtsp, uibc
    private val ports = (1..7).map { ServerSocket(0, 1, loop) }.map { it.use { s -> s.localPort } }

    // level_idc 31: AnnexB.fixSps такой SPS не трогает, в файле эмулятора те же байты
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xc0.toByte(), 0x1f, 0xda.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(3000) { (it * 7 + 1).toByte() }
    private fun p(n: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(300 + n) { (it * 5 + n).toByte() }

    @After fun tearDown() {
        sessions.forEach { runCatching { it.close("конец теста") } }
        hu?.let { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
        out.deleteRecursively()
    }

    private fun huLog() = File(out, "iccoa_hu.log").takeIf { it.isFile }?.readText() ?: ""

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("не дождались: $what\n--- телефон\n" + logs.joinToString("\n") +
                "\n--- машина\n" + huLog() + File(out, "stderr.txt").takeIf { it.isFile }?.readText().orEmpty())
            Thread.sleep(20)
        }
    }

    private fun startHu(pin: String) {
        val python = listOf("python", "python3").firstOrNull {
            runCatching {
                ProcessBuilder(it, "-c", "import cryptography").redirectErrorStream(true)
                    .redirectOutput(File(out, "probe.txt")).start().waitFor() == 0
            }.getOrDefault(false)
        }
        assumeTrue("нужен python с пакетом cryptography", python != null)
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "pc-headunit/iccoa_hu.py").isFile) dir = dir.parentFile
        val flags = listOf("--auth-port", "--rtp-port", "--control-port", "--audio-port", "--sensor-port", "--rtsp-port", "--uibc-port")
        hu = ProcessBuilder(listOf(python!!, "-X", "utf8", "iccoa_hu.py", "--no-view", "--quiet", "--pin", pin,
            "--screen", "2422x1272", "--out", out.path) + flags.zip(ports).flatMap { listOf(it.first, it.second.toString()) })
            .directory(File(requireNotNull(dir) { "не найден pc-headunit" }, "pc-headunit"))
            .redirectErrorStream(true).redirectOutput(File(out, "stderr.txt")).start()
        await("эмулятор слушает") { "ICCOA HU: auth" in huLog() }
    }

    private fun command(line: String) = hu!!.outputStream.run { write("$line\n".toByteArray()); flush() }

    private class Phone(val session: IccoaSession, val screen: AtomicReference<IccoaVideoConfig>, val closed: CopyOnWriteArrayList<String>)

    private fun phone(trust: IccoaTrust, pin: String?): Phone {
        val cfg = IccoaConfig("127.0.0.1", pin, "127.0.0.1", "test-model", bindHost = "127.0.0.1", authPort = ports[0],
            controlPort = ports[2], audioPort = ports[3], sensorPort = ports[4], rtspPort = ports[5], uibcPort = ports[6])
        val screen = AtomicReference<IccoaVideoConfig>()
        val closed = CopyOnWriteArrayList<String>()
        val platform = IccoaPlatform(log = { logs += it }, stub = { _, _, _, _, _, _, _ -> null }, aac = { _, _ -> null })
        val session = IccoaSession(cfg, trust, platform, onScreen = { screen.set(it) }) { closed += it }
        sessions += session
        session.start()
        return Phone(session, screen, closed)
    }

    @Test fun pinSignInStreamsVideoTakesInputAndNextSignInNeedsNoPin() {
        startHu("123456")
        val trust = IccoaSessionTest.MemoryTrust()
        val phone = phone(trust, "123456")
        await("экран машины из M3") { phone.screen.get() != null }
        assertEquals(2422 to 1272, phone.screen.get().width to phone.screen.get().height)
        assertEquals(ports[1], phone.screen.get().rtpPort)
        // Сохранён после шифрованного кадра машины: ключ сессии у сторон общий.
        await("ключ машины") { trust.carKey("127.0.0.1") != null }

        val core = AaBridgeCore({ line, _ -> logs += line })
        core.videoMode = CustomVideoMode.fitCar(2422, 1272)
        val peer = FakePeer()
        core.peer = peer
        phone.session.bind(core)
        await("START ядру после PLAY и bind") { core.isCarlifeStarted }

        core.onCodecConfig(sps + pps)
        core.onFrame(idr, 1)
        for (n in 1..3) core.onFrame(p(n), 1L + n)
        val sent = sps + pps + idr + p(1) + p(2) + p(3)
        val video = File(out, "iccoa_video.h264")
        await("H.264 в файле эмулятора") { video.length() >= sent.size }
        assertArrayEquals(sent, video.readBytes())

        // Тач в пикселях экрана машины масштабируется в кадр ГУ.
        await("UIBC подключён") { "UIBC: connected" in huLog() }
        val mode = core.videoMode
        command("touch 0 1211 636")
        command("touch 1 1211 636")
        command("key 4")
        command("wheel 87")
        await("тач") { peer.touches.firstOrNull() == Triple(0, 1211 * mode.outW / 2422, 636 * mode.outH / 1272) }
        await("кнопка UIBC") { 4 in peer.keys }
        await("кнопка руля") { 87 in peer.keys }
        // ядро без видео AA запрос только пишет в лог, до peer он не доходит
        fun idrAsked() = logs.count { "ГУ просит ключевой кадр" in it }
        val before = idrAsked()
        command("idr")
        await("wfd_idr_request") { idrAsked() > before }
        // m24 через 3 с после PLAY, m25 эмулятора читается разбором телефона
        await("конфиг машины") { logs.any { it.startsWith("IC control config") && "unreadable" !in it } }

        phone.session.close("стоп")
        assertEquals(listOf("стоп"), phone.closed.toList())

        // Повторный вход: без PIN, по ключу машины; порты те же — эмулятор знает только их.
        val again = phone(trust, null)
        await("экран машины при быстром входе") { again.screen.get() != null }
        await("control быстрого входа") { Regex("get_port_rsp").findAll(huLog()).count() == 2 }
        assertTrue(huLog(), "quick (no PIN)" in huLog())
        assertEquals(emptyList<String>(), again.closed.toList())
        // ValueError эмулятора = кадр не разобрался или не расшифровался; сброс сокета при закрытии сессии не в счёт
        assertFalse(huLog(), "ValueError" in huLog())
        assertTrue((logs + huLog()).none { "123456" in it })
    }

    @Test fun wrongPinIsRefused() {
        startHu("123456")
        val phone = phone(IccoaSessionTest.MemoryTrust(), "999999")
        await("отказ входа") { phone.closed.isNotEmpty() }
        assertEquals(listOf("вход ICCOA не удался: RESPONSE_RESULT result=1"), phone.closed.toList())
        assertTrue("sign-in rejected, result=1" in huLog())
    }
}
