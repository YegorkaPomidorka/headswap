// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One opaque TLS connection; no TLS interception and no preliminary pairing-port probe. */
internal class PairingTunnel(
    targetHost: String,
    targetPort: Int,
    private val connectTimeoutMs: Int = 3_000,
    private val operationTimeoutMs: Long = 25_000,
    private val onConnected: () -> Unit = {},
) : Closeable {
    enum class FailureKind { CONNECT, TIMEOUT, RELAY }
    data class Failure(val kind: FailureKind, val error: Exception)
    private val firstFailure = AtomicReference<Failure?>(null)
    val failure: Failure? get() = firstFailure.get()
    private val server = listener(connectTimeoutMs, operationTimeoutMs)
    val port: Int = server.localPort
    private val timeout = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "adb-pair-deadline").apply { isDaemon = true } }
    @Volatile private var incoming: Socket? = null
    @Volatile private var outgoing: Socket? = null
    private val closed = AtomicBoolean(false)

    init {
        timeout.schedule({
            record(FailureKind.TIMEOUT, SocketTimeoutException("Pairing deadline"))
            close()
        }, operationTimeoutMs, TimeUnit.MILLISECONDS)
        Thread({
            try {
                val a = server.accept()
                incoming = a
                if (closed.get()) { a.close(); return@Thread }
                server.close() // Accept exactly one connection, including on failure.
                val b = Socket()
                outgoing = b
                if (closed.get()) { b.close(); return@Thread }
                try {
                    b.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                } catch (e: Exception) {
                    record(FailureKind.CONNECT, e)
                    close()
                    return@Thread
                }
                a.tcpNoDelay = true
                b.tcpNoDelay = true
                a.soTimeout = operationTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                b.soTimeout = a.soTimeout
                onConnected()
                Thread({ relay(b, a) }, "adb-pair-response").apply { isDaemon = true }.start()
                relay(a, b)
            } catch (e: Exception) {
                record(FailureKind.RELAY, e)
                close()
            }
        }, "adb-pair-request").apply { isDaemon = true }.start()
    }

    private fun record(kind: FailureKind, error: Exception) {
        if (!closed.get()) firstFailure.compareAndSet(null, Failure(kind, error))
    }

    private fun relay(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
            to.shutdownOutput()
        } catch (e: Exception) {
            record(if (e is SocketTimeoutException) FailureKind.TIMEOUT else FailureKind.RELAY, e)
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { incoming?.close() }
        runCatching { outgoing?.close() }
        timeout.shutdownNow()
    }

    private companion object {
        fun listener(connectTimeoutMs: Int, operationTimeoutMs: Long): ServerSocket {
            require(connectTimeoutMs > 0 && operationTimeoutMs > 0)
            return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
    }
}
