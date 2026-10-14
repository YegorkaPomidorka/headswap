package com.yegorkapomidorka.headswap.link

import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.InnerAssembler
import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.Wire
import com.yegorkapomidorka.headswap.aa.DrainMeter
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Порты беспроводного CarLife (Baidu MD 8.6.7, CommonParams): ГУ подключается к телефону как TCP-клиент. */
object Tcp6Ports {
    val DEFAULT: Map<Int, Int> = linkedMapOf(
        Ch.CMD to 7240, Ch.VIDEO to 8240, Ch.MEDIA to 9240, Ch.TTS to 9241, Ch.VR to 9242, Ch.TOUCH to 9340,
    )

    /** Канал Data: принимаем и игнорируем, в готовность 6/6 не входит. */
    const val DATA = 9440
}

class BadInnerHeader(msg: String) : IOException(msg)

/**
 * Inner-сообщения из сокета одного канала, без внешнего заголовка: заголовок 8 байт (CMD, TOUCH: длина u16)
 * или 12 байт (VIDEO, MEDIA, TTS, VR: длина u32), затем тело по declared len (как ConnectSocket.readData у Baidu).
 * Резинка по типам нет: неверный заголовок = разрыв с причиной (U5 в плане, решается по H4).
 */
object InnerCodec {
    fun hdrLen(chan: Int) = if (Ch.isCmdStyle(chan)) 8 else 12

    /** Следующее inner целиком; null = чистый EOF на границе сообщения. EOFException = обрыв посреди сообщения. */
    fun read(chan: Int, din: DataInputStream): ByteArray? {
        val hl = hdrLen(chan)
        val hdr = ByteArray(hl)
        val first = din.read()
        if (first < 0) return null
        hdr[0] = first.toByte()
        din.readFully(hdr, 1, hl - 1)
        val cmd = Ch.isCmdStyle(chan)
        val declared = if (cmd) Wire.u16(hdr, 0) else Wire.u32(hdr, 0)
        val type = Wire.u32(hdr, if (cmd) 4 else 8)
        val limit = if (cmd) InnerAssembler.MAX_CMD_DECLARED else Wire.MAX_OUTER_LEN
        if (declared !in 0..limit) throw BadInnerHeader("длина $declared вне 0..$limit (тип ${Msg.hex(type)}, заголовок ${hexBytes(hdr)})")
        // VW CNS3 (Tiguan L Pro, h129) шлёт VIDEO_HEARTBEAT в CMD-сокет в CMD-кадре: 8 байт, длина 0
        val cmdHb = chan == Ch.CMD && type == Msg.VIDEO_HEARTBEAT && declared == 0
        if (!cmdHb && type ushr 16 != chan) {
            throw BadInnerHeader("тип ${Msg.hex(type)} не канала ${Ch.name(chan)}, длина $declared, заголовок ${hexBytes(hdr)}")
        }
        val inner = ByteArray(hl + declared)
        System.arraycopy(hdr, 0, inner, 0, hl)
        din.readFully(inner, hl, declared)
        if (cmdHb) skipHeartbeatTail(din)
        return inner
    }

    /**
     * Heartbeat в CMD-сокете мог прийти и 12-байтным видео-заголовком [длина u32][тип u32][метка u32]: тогда после
     * прочитанных 8 байт висят 4 байта метки. Смотрим вперёд, только если 12 байт уже в буфере (не блокируемся):
     * следующий заголовок с CMD-типом через 4 байта, а не сразу, значит хвост есть, пропускаем.
     */
    private fun skipHeartbeatTail(din: DataInputStream) {
        if (!din.markSupported() || din.available() < 12) return
        val p = ByteArray(12)
        din.mark(12)
        din.readFully(p)
        din.reset()
        fun cmdType(t: Int) = t ushr 16 == Ch.CMD || t == Msg.VIDEO_HEARTBEAT
        if (!cmdType(Wire.u32(p, 4)) && cmdType(Wire.u32(p, 8))) {
            din.skipBytes(4)
            WLog.ev(WLog.TCP, "hb-cmd-tail", "skipped" to 4)
        }
    }

    private fun hexBytes(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

class LinkSetupException(msg: String) : Exception(msg)

/**
 * Серверная сторона беспроводного CarLife: слушает 6 портов каналов (+ Data 9440) на одном адресе и ждёт ГУ.
 * Правила приёма: первое подключение на порт побеждает, повторное на тот же порт закрывается (у Baidu наоборот,
 * побеждает последнее); все сокеты должны прийти с адреса первого принятого. Слушатели живут до закрытия транспорта,
 * чтобы отбивать лишние подключения.
 */
class Tcp6Listener(
    private val bind: InetAddress,
    private val ports: Map<Int, Int> = Tcp6Ports.DEFAULT,
    private val dataPort: Int? = Tcp6Ports.DATA,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Кого пускать при bind на 0.0.0.0: ветка B пускает только адреса из подсетей, куда идёт маяк. */
    private val peerOk: (InetAddress) -> Boolean = { true },
) {
    private val lock = Object()
    private val servers = LinkedHashMap<Int, ServerSocket>()   // канал -> сервер; DATA_CHAN = порт Data
    private val accepted = LinkedHashMap<Int, Socket>()
    private var dataSocket: Socket? = null
    private var peer: InetAddress? = null
    private var closed = false

    /** Открыть все порты. Не открылся любой из шести: исключение с причиной, всё уже закрыто. */
    fun open(): Tcp6Listener {
        val all = LinkedHashMap(ports)
        if (dataPort != null) all[DATA_CHAN] = dataPort
        try {
            for ((chan, port) in all) {
                val s = ServerSocket()
                s.reuseAddress = true
                try {
                    s.bind(InetSocketAddress(bind, port), 4)
                } catch (e: IOException) {
                    s.close()
                    WLog.ev(WLog.TCP, "bind-fail", "chan" to chanName(chan), "port" to port, "err" to e.message)
                    if (chan == DATA_CHAN) continue   // без Data жить можно
                    throw LinkSetupException("tcp6: порт ${chanName(chan)} $port не открылся: ${e.message}")
                }
                synchronized(lock) { servers[chan] = s }
                WLog.ev(WLog.TCP, "bind", "chan" to chanName(chan), "port" to s.localPort, "addr" to bind.hostAddress)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        for ((chan, s) in synchronized(lock) { servers.toMap() }) {
            Thread({ acceptLoop(chan, s) }, "tcp6-accept-${chanName(chan)}").apply { isDaemon = true }.start()
        }
        return this
    }

    /** Адрес первого принятого пира (после 6/6 - ГУ); до первого подключения null. */
    val peerAddress: InetAddress? get() = synchronized(lock) { peer }

    /** Фактический порт канала (в тестах порты 0). */
    fun boundPort(chan: Int): Int = synchronized(lock) { servers[chan]?.localPort ?: -1 }

    /** Ждать все 6 сокетов. Не дождались: всё закрыто, исключение с причиной k/6 и списком недостающих. */
    fun await(timeoutMs: Long, label: String): MultiSocketTransport {
        val start = clock()
        synchronized(lock) {
            while (!closed && accepted.size < ports.size) {
                val left = timeoutMs - (clock() - start)
                if (left <= 0) break
                lock.wait(minOf(left, 250L))
            }
            if (closed) {
                dropAccepted()
                throw LinkSetupException("tcp6: слушатель закрыт до 6/6 (${accepted.size}/${ports.size})")
            }
            if (accepted.size < ports.size) {
                val missing = ports.filterKeys { it !in accepted }.map { (c, p) -> "${Ch.name(c)}($p)" }
                val k = accepted.size
                WLog.ev(WLog.TCP, "timeout", "ready" to "$k/${ports.size}", "waitedMs" to clock() - start, "missing" to missing.joinToString(","))
                dropAccepted()
                closeLocked()
                throw LinkSetupException("tcp6: за ${timeoutMs / 1000} с подключилось $k/${ports.size}, нет ${missing.joinToString(", ")}")
            }
            WLog.ev(WLog.TCP, "ready", "ready" to "${ports.size}/${ports.size}", "peer" to peer?.hostAddress, "ms" to clock() - start)
            return MultiSocketTransport(LinkedHashMap(accepted), ports, "$label, ГУ ${peer?.hostAddress}", { close() }, clock)
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        for (s in servers.values) quiet { s.close() }
        // принятые, но не переданные транспорту сокеты закрываются здесь; переданные закрывает транспорт сам
        dataSocket?.let { quiet { it.close() } }
        lock.notifyAll()
    }

    /** Закрыть и сокеты каналов (подъём не состоялся). */
    private fun dropAccepted() {
        for (s in accepted.values) quiet { s.close() }
    }

    private fun acceptLoop(chan: Int, server: ServerSocket) {
        while (true) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return   // сервер закрыт
            }
            onAccept(chan, s)
        }
    }

    private fun onAccept(chan: Int, s: Socket) {
        val addr = s.inetAddress
        val port = s.localPort
        synchronized(lock) {
            val reject = when {
                closed -> "closed"
                chan == DATA_CHAN && dataSocket != null -> "dup"
                chan != DATA_CHAN && accepted.containsKey(chan) -> "dup"
                peer != null && addr != peer -> "peer"
                peer == null && !peerOk(addr) -> "subnet"
                else -> null
            }
            if (reject != null) {
                WLog.ev(WLog.TCP, "reject", "chan" to chanName(chan), "port" to port, "peer" to addr.hostAddress, "why" to reject)
                quiet { s.close() }
                return
            }
            if (peer == null) peer = addr
            setOptions(s)
            if (chan == Ch.VIDEO) {
                // малый буфер отправки: иначе очередь прячется в ядре (до МиБ), запись не блокируется и слив не меряется,
                // политика битрейта на воздухе слепнет (лог VW 15.10: слив 6-19 МиБ/с, битрейт ни разу не сдвинулся)
                s.sendBufferSize = VIDEO_SNDBUF
                WLog.ev(WLog.TCP, "sndbuf", "chan" to "VIDEO", "asked" to VIDEO_SNDBUF, "got" to s.sendBufferSize)
            }
            if (chan == DATA_CHAN) {
                dataSocket = s
                WLog.ev(WLog.TCP, "accept", "chan" to "DATA", "port" to port, "peer" to addr.hostAddress)
                Thread({ drainData(s) }, "tcp6-data").apply { isDaemon = true }.start()
                return
            }
            accepted[chan] = s
            WLog.ev(WLog.TCP, "accept", "chan" to chanName(chan), "port" to port, "peer" to addr.hostAddress, "ready" to "${accepted.size}/${ports.size}")
            if (accepted.size == ports.size) lock.notifyAll()
        }
    }

    /** Data-канал Baidu (9440) нам не нужен: вычитываем, чтобы не забился буфер приёма ГУ. */
    private fun drainData(s: Socket) {
        val buf = ByteArray(4096)
        var total = 0L
        try {
            val inp = s.getInputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
        WLog.ev(WLog.TCP, "data-closed", "rx" to total)
    }

    /** Отмена подъёма до 6/6 (сервис остановлен): закрыть всё, включая принятые сокеты. */
    fun abort() {
        synchronized(lock) {
            dropAccepted()
            closeLocked()
        }
    }

    private fun chanName(chan: Int) = if (chan == DATA_CHAN) "DATA" else Ch.name(chan)

    companion object {
        private const val DATA_CHAN = 0

        /** SO_SNDBUF видео-сокета (ядро удваивает): ~250 мс при 4 Мбит/с. */
        const val VIDEO_SNDBUF = 64 * 1024

        fun setOptions(s: Socket) {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.setSoLinger(true, 0)
        }

        internal inline fun quiet(f: () -> Unit) {
            try {
                f()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Шесть TCP-сокетов в формате Baidu: каждый канал свой сокет, внешнего заголовка нет. Читатель и писатель на канал.
 * На video-сокет ГУ может слать VIDEO_HEARTBEAT (0x20002, 12 байт), VW CNS3 шлёт его в CMD-сокет (8 байт):
 * вычитываем и считаем, в протокол не отдаём.
 * Обрыв любого сокета закрывает все (ровно один onClosed с именем канала в причине).
 */
class MultiSocketTransport internal constructor(
    private val sockets: Map<Int, Socket>,
    private val ports: Map<Int, Int>,
    override val label: String,
    private val closeListener: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    override val caps: TransportCaps = TransportCaps.TCP6,
) : CarTransport {

    private val queues = sockets.keys.associateWith { LinkedBlockingQueue<ByteArray>() }
    private val videoBytes = AtomicLong()
    private val videoFrames = AtomicInteger()
    private val drain = DrainMeter()
    private val closed = AtomicBoolean(false)
    private val threads = ArrayList<Thread>()
    private val dispatchLock = Any()
    private val txLock = Any()
    private val rx = AtomicLong()
    private val t0 = clock()
    @Volatile private var onClosedCb: ((String) -> Unit)? = null

    // heartbeat ГУ на video-сокете (VW CNS3 шлёт его в CMD-сокет, тогда считаем ещё и hbCmd)
    @Volatile private var hbCount = 0L
    @Volatile private var hbCmd = 0L
    private var hbFirstMs = 0L
    private var hbLastMs = 0L
    @Volatile private var hbMaxGapMs = 0L

    override val rxBytes: Long get() = rx.get()
    override val queuedVideoBytes: Long get() = videoBytes.get()
    override val queuedVideoFrames: Int get() = videoFrames.get()
    override val drainBytesPerSec: Double get() = drain.bytesPerSec
    override fun queueSize(chan: Int): Int = queues[chan]?.size ?: 0

    val heartbeats: Long get() = hbCount

    override fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    ) {
        onClosedCb = onClosed
        for ((chan, s) in sockets) {
            val n = Ch.name(chan)
            threads += Thread({ writeLoop(chan, s, onWritten) }, "cl-w-$n")
            threads += Thread({ readLoop(chan, s, sink) }, "cl-r-$n")
        }
        for (t in threads) t.start()
        WLog.ev(WLog.TCP, "start", "sockets" to sockets.size)
    }

    override fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        val q = queues[chan] ?: return
        if (chan == Ch.VIDEO) {
            videoBytes.addAndGet(inner.size.toLong())
            videoFrames.incrementAndGet()
        }
        q.put(inner)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        WLog.ev(WLog.TCP, "close", "by" to "session", *endStats())
        closeAll()
    }

    val isClosed: Boolean get() = closed.get()

    /**
     * Закрыть только сокеты, не помечая транспорт закрытым: нити чтения получат ошибку и закроют его сами,
     * ровно один onClosed (ADB: повторный accept у ГУ сбрасывает набор, сессия должна узнать об этом).
     */
    fun severSockets() {
        for (s in sockets.values) Tcp6Listener.quiet { s.close() }
    }

    private fun fail(chan: Int, why: String) {
        if (!closed.compareAndSet(false, true)) return
        val reason = "${caps.kind} ${Ch.name(chan)}(${ports[chan]}): $why"
        WLog.ev(WLog.TCP, "close", "by" to "link", "chan" to Ch.name(chan), "why" to why, *endStats())
        closeAll()
        onClosedCb?.invoke(reason)
    }

    private fun endStats(): Array<Pair<String, Any?>> = arrayOf(
        "aliveMs" to clock() - t0, "rx" to rx.get(), "hb" to hbCount, "hbCmd" to hbCmd, "hbMaxGapMs" to hbMaxGapMs,
    )

    private fun closeAll() {
        Tcp6Listener.quiet { closeListener() }
        for (s in sockets.values) Tcp6Listener.quiet { s.close() }
        // свою нить не прерываем: после fail() на ней идёт весь CarLifeSession.close() (остановка видео, onClosed)
        val me = Thread.currentThread()
        for (t in threads) if (t !== me) t.interrupt()
    }

    private fun readLoop(chan: Int, s: Socket, sink: (Int, ByteArray) -> Unit) {
        try {
            val din = DataInputStream(BufferedInputStream(s.getInputStream(), READ_BUF))
            while (!closed.get()) {
                val inner = InnerCodec.read(chan, din)
                if (inner == null) {
                    fail(chan, "EOF (ГУ закрыла сокет)")
                    return
                }
                rx.addAndGet(inner.size.toLong())
                if ((chan == Ch.VIDEO || chan == Ch.CMD) && Wire.u32(inner, InnerCodec.hdrLen(chan) - 4) == Msg.VIDEO_HEARTBEAT) {
                    onHeartbeat(chan)
                    continue
                }
                synchronized(dispatchLock) { sink(chan, inner) }
            }
        } catch (e: BadInnerHeader) {
            AppLog.e("tcp6 ${Ch.name(chan)}: плохой заголовок inner: ${e.message}")
            fail(chan, "плохой заголовок: ${e.message}")
        } catch (e: EOFException) {
            fail(chan, "обрыв посреди сообщения")
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка чтения: ${e.message}")
        }
    }

    private fun onHeartbeat(chan: Int) {
        val now = clock()
        if (chan == Ch.CMD) hbCmd++
        if (hbCount == 0L) {
            hbFirstMs = now
            WLog.ev(WLog.TCP, "hb-first", "afterMs" to now - t0, "chan" to Ch.name(chan))
        } else {
            val gap = now - hbLastMs
            if (gap > hbMaxGapMs) hbMaxGapMs = gap
        }
        hbLastMs = now
        hbCount++
    }

    private fun writeLoop(chan: Int, s: Socket, onWritten: (Int, ByteArray) -> Unit) {
        val q = queues.getValue(chan)
        val video = chan == Ch.VIDEO
        try {
            val out = s.getOutputStream()
            while (!closed.get()) {
                val inner = q.take()
                if (video) {
                    videoBytes.addAndGet(-inner.size.toLong())
                    videoFrames.decrementAndGet()
                }
                val t = System.nanoTime()
                out.write(inner)
                out.flush()
                if (video) drain.onWrite(clock(), true, inner.size, System.nanoTime() - t)
                synchronized(txLock) { onWritten(chan, inner) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) fail(chan, "ошибка записи: ${e.message}")
        }
    }

    companion object {
        private const val READ_BUF = 64 * 1024
    }
}

/** Для тестов и диагностики: inner из потока по одному (обёртка над InnerCodec). */
fun readInners(chan: Int, input: InputStream): List<ByteArray> {
    val din = DataInputStream(input)
    val out = ArrayList<ByteArray>()
    while (true) out += InnerCodec.read(chan, din) ?: return out
}
