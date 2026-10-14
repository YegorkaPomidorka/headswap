package com.yegorkapomidorka.headswap

import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Транспорт сессии с ГУ. AOA: один USB-поток, сообщения с внешним заголовком [chan u32][len u32].
 * ADB: шесть TCP-сокетов (по одному на канал), внутри «голые» inner-сообщения без внешнего заголовка.
 */
sealed interface CarLifeIo {
    /** Для лога: "AOA" / "ADB TCP". */
    val label: String

    /** Закрыть все ресурсы; ошибки глотаются, повторный вызов безопасен. */
    fun close()
}

class AoaIo(private val pfd: ParcelFileDescriptor) : CarLifeIo {
    val input = FileInputStream(pfd.fileDescriptor)
    val output = FileOutputStream(pfd.fileDescriptor)

    override val label = "AOA"

    override fun close() {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        try {
            input.close()
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
    }
}

/** Шесть принятых сокетов ADB-режима: канал (Ch.*) -> сокет. */
class TcpIo(val sockets: Map<Int, Socket>) : CarLifeIo {
    val input: Map<Int, InputStream> = sockets.mapValues { BufferedInputStream(it.value.getInputStream(), 64 * 1024) }
    val output: Map<Int, OutputStream> = sockets.mapValues { BufferedOutputStream(it.value.getOutputStream(), 64 * 1024) }

    @Volatile var isClosed = false
        private set

    override val label = "ADB TCP"

    override fun close() {
        isClosed = true
        // потоки не закрываем: BufferedOutputStream.close() сначала flush и может зависнуть на мёртвом сокете
        for (s in sockets.values) {
            try {
                s.shutdownInput()
            } catch (_: Exception) {
            }
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }
}

/** Чтение одного inner-сообщения из TCP-потока канала. Чистая функция: без Android и AppLog, чтобы тестировать на JVM. */
object TcpFraming {
    /**
     * Заголовок (8 байт для CMD/TOUCH, 12 для остальных), declared из него, затем тело.
     * null - чистый EOF на границе сообщения; EOFException - обрыв посреди заголовка или тела;
     * ProtocolDesync - declared вне допустимого (с hex заголовка).
     * onHeader вызывается сразу после чтения заголовка, до тела: если тело не придёт, заголовок хотя бы попадёт в лог.
     */
    fun readInner(input: InputStream, chan: Int, onHeader: ((hdr: ByteArray, declared: Int) -> Unit)? = null): ByteArray? {
        val cmd = Ch.isCmdStyle(chan)
        val hdrLen = if (cmd) 8 else 12
        val first = input.read()
        if (first < 0) return null
        val hdr = ByteArray(hdrLen)
        hdr[0] = first.toByte()
        readFully(input, hdr, 1, hdrLen - 1)
        val declared = if (cmd) Wire.u16(hdr, 0) else Wire.u32(hdr, 0)
        val limit = if (cmd) InnerAssembler.MAX_CMD_DECLARED else Wire.MAX_OUTER_LEN
        onHeader?.invoke(hdr, declared)
        if (declared < 0 || declared > limit) {
            throw ProtocolDesync("плохой заголовок ${Ch.name(chan)}: declared=$declared, ${Hex.compact(hdr, 16)}")
        }
        val inner = hdr.copyOf(hdrLen + declared)
        readFully(input, inner, hdrLen, declared)
        return inner
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var p = 0
        while (p < len) {
            val n = input.read(buf, off + p, len - p)
            if (n < 0) throw EOFException("поток оборвался на $p из $len байт")
            p += n
        }
    }
}
