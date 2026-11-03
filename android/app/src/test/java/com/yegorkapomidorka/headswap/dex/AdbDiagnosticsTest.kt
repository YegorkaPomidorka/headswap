// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.*
import org.junit.Test

class AdbDiagnosticsTest {
    @Test fun classifiesNestedTransportFailuresWithoutCallingThemTlsFailures() {
        val refusal = SSLHandshakeException("hidden message").apply { initCause(ConnectException("ECONNREFUSED")) }
        assertEquals(AdbReason.REFUSED, AdbDiagnostics.classify(refusal))
        assertEquals(AdbReason.TIMEOUT, AdbDiagnostics.classify(IOException(SocketTimeoutException())))
        assertEquals(AdbReason.NETWORK_UNREACHABLE, AdbDiagnostics.classify(ConnectException("ENETUNREACH")))
        assertEquals(AdbReason.PERMISSION_DENIED, AdbDiagnostics.classify(IOException("EPERM")))
        assertEquals(AdbReason.TLS_PROTOCOL, AdbDiagnostics.classify(SSLProtocolException("TLS failed")))
        assertEquals(AdbReason.PAIR_REJECTED, AdbDiagnostics.classify(IOException("Could not exchange peer info")))
        assertEquals(AdbReason.IO_FAILURE, AdbDiagnostics.classify(IOException("something")))
        assertEquals(AdbReason.INTERNAL, AdbDiagnostics.classify(IllegalStateException("x")))
    }

    @Test fun anUnknownKeyOnTheMainPortReadsAsNotPairedButOnlyAfterPairing() {
        val handshake = SSLHandshakeException("peer rejected")
        assertEquals(AdbReason.NOT_PAIRED, AdbDiagnostics.classify(handshake, AdbStage.CONNECT))
        assertEquals(AdbReason.NOT_PAIRED, AdbDiagnostics.classify(SSLProtocolException("x"), AdbStage.SHELL))
        assertEquals(AdbReason.NOT_PAIRED, AdbDiagnostics.classify(EOFException(), AdbStage.CONNECT))
        assertEquals(AdbReason.NOT_PAIRED, AdbDiagnostics.classify(SocketException("closed"), AdbStage.SHELL))
        assertEquals(AdbReason.TLS_HANDSHAKE, AdbDiagnostics.classify(handshake, AdbStage.PAIR_HANDSHAKE))
        assertEquals(AdbReason.CONNECTION_CLOSED, AdbDiagnostics.classify(EOFException(), AdbStage.PAIR_HANDSHAKE))
        // a closed port is a closed port at any stage
        assertEquals(AdbReason.REFUSED, AdbDiagnostics.classify(ConnectException("ECONNREFUSED"), AdbStage.CONNECT))
    }

    @Test fun ourOwnFailuresKeepTheirReason() {
        assertEquals(AdbReason.NO_CONNECT_PORT, AdbDiagnostics.classify(AdbAccessException(AdbStage.CONNECT, AdbReason.NO_CONNECT_PORT), AdbStage.SHELL))
    }

    @Test fun everyReasonHasAUserMessage() {
        for (reason in AdbReason.entries) assertNotEquals(reason.name, 0, reason.messageRes)
    }

    @Test fun safeTraceCannotRevealMessagesPemOrPairingCode() {
        val secret = "654321\n-----BEGIN PRIVATE KEY-----\nprivate-key-material\n-----END PRIVATE KEY-----"
        val error = IOException(secret, ConnectException(secret)).apply {
            stackTrace = arrayOf(
                StackTraceElement("com.yegorkapomidorka.headswap.dex.PhoneAdb", "pair", "PhoneAdb.kt", 99),
                StackTraceElement("untrusted.library", "exchange", secret, 1),
            )
        }
        val trace = AdbDiagnostics.safeTrace(error)
        assertTrue(trace.contains("IOException{PhoneAdb.pair:99}"))
        assertTrue(trace.contains("ConnectException"))
        assertFalse(trace.contains("654321"))
        assertFalse(trace.contains("PRIVATE"))
        assertFalse(trace.contains("private-key-material"))
        assertFalse(trace.contains("untrusted.library"))
        assertFalse(trace.contains('\n'))
        val reported = AdbAccessException(AdbStage.PAIR_CONNECT, AdbDiagnostics.classify(error))
        assertNull(reported.cause)
        assertFalse(reported.toString().contains("654321"))
    }
}
