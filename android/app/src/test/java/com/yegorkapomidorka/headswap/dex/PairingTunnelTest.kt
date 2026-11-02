// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PairingTunnelTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun targetRefusalSurvivesTheClientSideClose() {
        val unusedPort = ServerSocket(0, 1, loopback).use { it.localPort }
        PairingTunnel("127.0.0.1", unusedPort, operationTimeoutMs = 2_000).use { tunnel ->
            Socket(loopback, tunnel.port).use { client ->
                client.soTimeout = 3_000
                // The app's TLS socket sees EOF or reset; diagnostics must retain ECONNREFUSED.
                try { assertEquals(-1, client.getInputStream().read()) } catch (_: IOException) { }
                val failure = tunnel.failure
                assertNotNull(failure)
                assertEquals(PairingTunnel.FailureKind.CONNECT, failure!!.kind)
                assertTrue(failure.error is ConnectException)
                assertEquals(AdbReason.REFUSED, AdbDiagnostics.classify(failure.error))
            }
        }
    }

    @Test fun hardDeadlineClosesAnUnresponsivePairingPeer() {
        ServerSocket(0, 1, loopback).use { target ->
            val executor = Executors.newSingleThreadExecutor()
            val accepted = CountDownLatch(1)
            val worker = executor.submit {
                target.accept().use { socket ->
                    socket.soTimeout = 3_000
                    accepted.countDown()
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            try {
                PairingTunnel("127.0.0.1", target.localPort, operationTimeoutMs = 300).use { tunnel ->
                    Socket(loopback, tunnel.port).use { client ->
                        client.soTimeout = 3_000
                        assertTrue(accepted.await(2, TimeUnit.SECONDS))
                        try { assertEquals(-1, client.getInputStream().read()) } catch (_: IOException) { }
                        assertEquals(PairingTunnel.FailureKind.TIMEOUT, tunnel.failure?.kind)
                        assertEquals(AdbReason.TIMEOUT, AdbDiagnostics.classify(tunnel.failure!!.error))
                    }
                }
                worker.get(3, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun relaysFragmentedBinaryBytesBothWaysAndPreservesHalfClose() {
        val payload = ByteArray(513) { (it % 256).toByte() }
        val response = payload.reversedArray()
        ServerSocket(0, 1, loopback).use { target ->
            val executor = Executors.newSingleThreadExecutor()
            val worker = executor.submit {
                target.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val received = ByteArray(payload.size)
                    DataInputStream(socket.getInputStream()).readFully(received)
                    assertArrayEquals(payload, received)
                    for (offset in response.indices step 7) {
                        socket.getOutputStream().write(response, offset, minOf(7, response.size - offset))
                        socket.getOutputStream().flush()
                    }
                    assertEquals(-1, socket.getInputStream().read())
                    socket.shutdownOutput()
                }
            }
            try {
                PairingTunnel("127.0.0.1", target.localPort, operationTimeoutMs = 5_000).use { tunnel ->
                    Socket(loopback, tunnel.port).use { client ->
                        client.soTimeout = 3_000
                        for (offset in payload.indices step 3) {
                            client.getOutputStream().write(payload, offset, minOf(3, payload.size - offset))
                            client.getOutputStream().flush()
                        }
                        client.shutdownOutput()
                        val received = ByteArray(response.size)
                        DataInputStream(client.getInputStream()).readFully(received)
                        assertArrayEquals(response, received)
                        assertEquals(-1, client.getInputStream().read())
                        worker.get(3, TimeUnit.SECONDS)
                        assertNull(tunnel.failure)
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
