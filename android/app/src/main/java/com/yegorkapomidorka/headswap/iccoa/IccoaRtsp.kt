// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaRtsp.kt.
// Изменено для Headswap: без сокета и таймера — сообщения на вход, строки на выход; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Locale

/** Экран машины и порт RTP из ответа на M3. Дефолты — как у XDL, если машина чего-то не прислала. */
data class IccoaVideoConfig(
    val host: String, val width: Int = 2764, val height: Int = 1426,
    val fps: Int = 30, val rtpPort: Int = 19000, val dpi: Int = 0
)

/**
 * WFD-источник (телефон) на 7236 как машина состояний: M1–M7, M16, wfd_idr_request.
 * Сокет держит сессия: читает [read], отдаёт в [onMessage], пишет то, что пришло в [send] (ISO-8859-1).
 * Нарушение протокола — IllegalArgumentException/IllegalStateException, сессия закрывает RTSP.
 * Не потокобезопасен: [onMessage] и [tick] звать под одним замком.
 */
class IccoaRtsp(
    carHost: String,
    private val localHost: String,
    private val send: (String) -> Unit,
    private val onPlay: (IccoaVideoConfig) -> Unit,
    private val onPause: () -> Unit = {},
    private val onKeyframe: () -> Unit = {},
    private val onConfig: (IccoaVideoConfig) -> Unit = {},
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val pending = HashMap<Int, String>()
    private var sequence = 1
    private var asked = false
    private var setup = false
    private var keepaliveSent = 0L
    private var keepaliveDue = Long.MAX_VALUE

    var config = IccoaVideoConfig(carHost)
        private set

    /** Машина прислала TEARDOWN: сессии пора закрыть сокет. */
    var tornDown = false
        private set

    /** M1. Первым после accept говорит телефон. */
    fun start() = request("OPTIONS * RTSP/1.0", "M1", extra = "Require: org.wfa.wfd1.0\r\n")

    /** Звать раз в секунду. false — машина 10 с не отвечает на M16, соединение мертво. */
    fun tick(): Boolean {
        val now = clockMs()
        if (keepaliveSent > 0 && now - keepaliveSent > KEEPALIVE_TIMEOUT_MS) return false
        if (now >= keepaliveDue && keepaliveSent == 0L) {
            keepaliveSent = now
            request("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M16", extra = "Session: 1\r\n")
        }
        return true
    }

    fun onMessage(message: Message) {
        val cseq = message.headers["cseq"]?.toIntOrNull() ?: error("Missing RTSP CSeq")
        if (message.first.startsWith("RTSP/")) {
            val kind = pending.remove(cseq) ?: return
            require(message.first.split(' ').getOrNull(1) == "200") { "RTSP rejected $kind" }
            when (kind) {
                "M3" -> {
                    config = capabilities(config, message.body)
                    onConfig(config)
                    request("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M4", parameters())
                }
                "M4" -> request("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M5", "wfd_trigger_method: SETUP\r\n")
                "M16" -> { keepaliveSent = 0; keepaliveDue = clockMs() + KEEPALIVE_PERIOD_MS }
            }
            return
        }
        when (message.first.substringBefore(' ')) {
            "OPTIONS" -> {
                response(cseq, extra = "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n")
                if (!asked) {
                    asked = true
                    request("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", "M3",
                        listOf("wfd_video_formats", "wfd_audio_codecs", "wfd_client_rtp_ports", "wfd_content_protection",
                            "wfd_display_edid", "wfd_uibc_capability", "wfd_coupled_sink", "wfd_standby_resume_capability",
                            "wfd_connector_type", "wfd_car_display_mode", "iccoa_support_hevc").joinToString("\r\n", postfix = "\r\n"))
                }
            }
            "SETUP" -> {
                val transport = message.headers["transport"] ?: "RTP/AVP/TCP;unicast;client_port=${config.rtpPort}"
                require(transport.contains("RTP/AVP/TCP", true)) { "Only RTP/TCP is supported" }
                Regex("(?:client_port=|port=)(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    require(it in 1..65535); config = config.copy(rtpPort = it)
                }
                setup = true
                response(cseq, extra = "Transport: $transport\r\nSession: 1;timeout=30\r\n")
            }
            "PLAY" -> {
                check(setup) { "PLAY before SETUP" }
                response(cseq, extra = "Session: 1\r\n")
                keepaliveDue = clockMs() + KEEPALIVE_PERIOD_MS
                onPlay(config)
            }
            "PAUSE" -> { response(cseq, extra = "Session: 1\r\n"); onPause() }
            "TEARDOWN" -> { response(cseq, extra = "Session: 1\r\n"); tornDown = true }
            "GET_PARAMETER" -> response(cseq, message.body.takeIf { it.isNotBlank() }?.let { wanted ->
                parameters().lineSequence().filter { line -> line.isNotBlank() && wanted.contains(line.substringBefore(':')) }.joinToString("\r\n", postfix = "\r\n")
            })
            // ovm_control_capability (камеры машины) не поддерживаем: отвечаем 200, порт не выдумываем.
            "SET_PARAMETER" -> {
                response(cseq)
                if (message.body.contains("wfd_idr_request")) onKeyframe()
            }
            else -> response(cseq, status = "405 Method Not Allowed")
        }
    }

    private fun parameters(): String = "$VIDEO\r\n$AUDIO\r\n" +
        "wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${config.rtpPort} 0 mode=play\r\n" +
        "wfd_presentation_URL: rtsp://$localHost/wfd1.0/streamid=0 none\r\n" +
        "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;hidc_cap_list=none;port=$UIBC_PORT;uibc_encrypted=true\r\n"

    private fun request(first: String, kind: String, body: String? = null, extra: String = "") {
        check(pending.size < 16) { "Too many pending RTSP requests" }
        val cseq = sequence++
        pending[cseq] = kind
        write(first, cseq, body, extra)
    }

    private fun response(cseq: Int, body: String? = null, extra: String = "", status: String = "200 OK") =
        write("RTSP/1.0 $status", cseq, body, extra)

    private fun write(first: String, cseq: Int, body: String?, extra: String) {
        // Content-Length в байтах ISO-8859-1 = в символах.
        send("$first\r\nCSeq: $cseq\r\n$extra" +
            (if (body != null) "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n" else "") +
            "\r\n" + (body ?: ""))
    }

    data class Message(val first: String, val headers: Map<String, String>, val body: String)

    companion object {
        const val PORT = 7236
        const val UIBC_PORT = 4321
        const val KEEPALIVE_PERIOD_MS = 20_000L
        const val KEEPALIVE_TIMEOUT_MS = 10_000L
        private const val VIDEO = "wfd_video_formats: 28 00 01 01 ffffffff ffffffff ffffffff 00 0000 0000 00 none none"
        private const val AUDIO = "wfd_audio_codecs: AAC 00000001 00"
        // Только эти заголовки — списки; повтор остальных с другим значением — ошибка.
        private val LIST_HEADERS = setOf("public", "allow", "require", "proxy-require", "supported", "unsupported")

        fun capabilities(base: IccoaVideoConfig, text: String): IccoaVideoConfig {
            var config = base
            Regex("display=(\\d+):(\\d+);dpi=(\\d+);fps=(\\d+)").find(text)?.groupValues?.let {
                val width = it[1].toInt(); val height = it[2].toInt(); val fps = it[4].toInt()
                require(width in 320..4096 && height in 240..2160 && fps in 1..120) { "Unsupported car display" }
                config = config.copy(width = width, height = height, fps = fps.coerceAtMost(60),
                    dpi = it[3].toIntOrNull()?.coerceIn(0, 2000) ?: 0)
            }
            Regex("RTP/AVP/TCP;[^\\r\\n]*?\\s(\\d+)\\s+\\d+\\s+mode=play", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toInt()?.let { require(it in 1..65535); config = config.copy(rtpPort = it) }
            return config
        }

        /** Одно RTSP-сообщение из потока. Обрыв — EOFException, мусор — IllegalArgumentException. */
        fun read(input: DataInputStream): Message {
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val byte = input.readUnsignedByte()
                    if (byte == 10) break
                    require(bytes.size() < 8192) { "RTSP line too long" }
                    if (byte != 13) bytes.write(byte)
                }
                return bytes.toString("ISO-8859-1")
            }
            val first = line()
            require(first.isNotBlank()) { "Empty RTSP message" }
            val headers = linkedMapOf<String, String>()
            repeat(65) { index ->
                val line = line()
                if (line.isEmpty()) {
                    val size = headers["content-length"]?.let { requireNotNull(it.toIntOrNull()) } ?: 0
                    require(size in 0..65536) { "RTSP body too large" }
                    val body = ByteArray(size); input.readFully(body)
                    return Message(first, headers, body.toString(Charsets.ISO_8859_1))
                }
                require(index < 64 && ':' in line) { "Invalid RTSP headers" }
                val name = line.substringBefore(':').trim().lowercase(Locale.ROOT)
                require(name.isNotEmpty()) { "Empty RTSP header name" }
                val value = line.substringAfter(':').trim()
                val previous = headers[name]
                headers[name] = when {
                    previous == null -> value
                    name in LIST_HEADERS -> "$previous, $value"
                    else -> {
                        require(previous == value) { "Conflicting RTSP header: $name" }
                        previous
                    }
                }
            }
            error("RTSP headers too long")
        }
    }
}
