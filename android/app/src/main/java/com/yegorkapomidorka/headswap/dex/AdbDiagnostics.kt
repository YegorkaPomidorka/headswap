// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.R
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

enum class AdbStage { VALIDATE, IDENTITY, PAIR_CONNECT, PAIR_HANDSHAKE, CONNECT, SHELL, DISPATCH }

enum class AdbReason(val messageRes: Int) {
    NO_WIFI(R.string.pa_err_no_wifi),
    INVALID_PORT(R.string.pa_err_port),
    INVALID_CODE(R.string.pa_err_code),
    NO_PAIRING_PORT(R.string.pa_err_no_pair_port),
    NO_CONNECT_PORT(R.string.pa_err_no_connect_port),
    REFUSED(R.string.pa_err_refused),
    TIMEOUT(R.string.pa_err_timeout),
    NETWORK_UNREACHABLE(R.string.pa_err_no_wifi),
    PERMISSION_DENIED(R.string.pa_err_permission),
    PAIR_REJECTED(R.string.pa_err_pair_rejected),
    NOT_PAIRED(R.string.pa_err_not_paired),
    TLS_HANDSHAKE(R.string.pa_err_tls),
    TLS_PROTOCOL(R.string.pa_err_tls),
    TLS_PROVIDER(R.string.pa_err_tls_provider),
    CONNECTION_CLOSED(R.string.pa_err_closed),
    IDENTITY_FAILURE(R.string.pa_err_identity),
    SHELL_REJECTED(R.string.pa_err_shell),
    PACKAGE_MISMATCH(R.string.pa_err_package),
    UNSUPPORTED_SHELL(R.string.pa_err_unsupported),
    DISPATCH_UNCONFIRMED(R.string.pa_err_dispatch),
    IO_FAILURE(R.string.pa_err_io),
    INTERNAL(R.string.pa_err_internal),
}

/** Deliberately omits the raw cause: library errors can carry pairing material. */
class AdbAccessException(val stage: AdbStage, val reason: AdbReason) : IOException("[$stage/$reason]")

internal object AdbDiagnostics {
    fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var cursor: Throwable? = error
        while (cursor != null && result.size < 8 && seen.add(cursor)) {
            result += cursor
            cursor = cursor.cause
        }
        return result
    }

    /**
     * Inspect known platform tokens only; never return or log arbitrary exception messages. After the pairing
     * step a TLS or "closed" failure on the main port means adbd does not know our key: [AdbReason.NOT_PAIRED].
     */
    fun classify(error: Throwable, stage: AdbStage = AdbStage.VALIDATE): AdbReason {
        if (error is AdbAccessException) return error.reason
        val causes = chain(error)
        if (causes.any { it is SecurityException || it.message.orEmpty().contains("EACCES") || it.message.orEmpty().contains("EPERM") }) return AdbReason.PERMISSION_DENIED
        if (causes.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException || it.message.orEmpty().contains("ETIMEDOUT") }) return AdbReason.TIMEOUT
        if (causes.any { it is NoRouteToHostException || it.message.orEmpty().contains("ENETUNREACH") || it.message.orEmpty().contains("EHOSTUNREACH") }) return AdbReason.NETWORK_UNREACHABLE
        if (causes.any { it is ConnectException || it.message.orEmpty().contains("ECONNREFUSED") }) return AdbReason.REFUSED
        val connecting = stage == AdbStage.CONNECT || stage == AdbStage.SHELL
        if (causes.any { it is SSLProtocolException }) return if (connecting) AdbReason.NOT_PAIRED else AdbReason.TLS_PROTOCOL
        if (causes.any { it is SSLHandshakeException }) return if (connecting) AdbReason.NOT_PAIRED else AdbReason.TLS_HANDSHAKE
        if (causes.any { it is SSLException }) return AdbReason.TLS_PROVIDER
        if (causes.any { it.message.orEmpty().contains("exportKeyingMaterial") || it.message.orEmpty().contains("Conscrypt") }) return AdbReason.TLS_PROVIDER
        if (causes.any {
                it.message.orEmpty().contains("Could not exchange peer info") ||
                    it.message.orEmpty().contains("Exchanging message wasn't successful")
            }) return AdbReason.PAIR_REJECTED
        if (causes.any { it is SocketException || it is EOFException }) return if (connecting) AdbReason.NOT_PAIRED else AdbReason.CONNECTION_CLOSED
        return if (causes.any { it is IOException }) AdbReason.IO_FAILURE else AdbReason.INTERNAL
    }

    /** Only exception class names and our own frame names/line numbers, never messages. */
    fun safeTrace(error: Throwable): String = chain(error).joinToString(" <- ") { cause ->
        val name = cause.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }.take(80)
        fun frameName(value: String) = value.filter { it.isLetterOrDigit() || it in "_$<>" }.take(80)
        val frames = cause.stackTrace.filter { it.className.startsWith("com.yegorkapomidorka.headswap.") }
            .take(5).joinToString(",") { "${frameName(it.className.substringAfterLast('.'))}.${frameName(it.methodName)}:${it.lineNumber}" }
        if (frames.isEmpty()) name else "$name{$frames}"
    }
}
