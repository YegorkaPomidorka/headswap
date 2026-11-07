// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov: Kadb pairing and the keystore-held ADB identity.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.yegorkapomidorka.headswap.AppLog
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wireless debugging of this very phone. Every call is blocking (worker thread only) and every connection goes
 * to a literal local address; there is no host or shell-command input. Errors reach the caller only as
 * [AdbAccessException]: library messages can carry codes, keys or peer material.
 */
internal object PhoneAdb {
    private const val CLIENT_NAME = "Headswap Desktop"
    private var configured = false

    @Synchronized
    private fun configure(context: Context) {
        if (configured) return
        KadbCert.configure(IdentityStore(context.applicationContext))
        KadbCert.ensureReady()
        configured = true
    }

    /** Wireless debugging only runs over Wi-Fi; without it there is nothing to pair with or connect to. */
    @Suppress("DEPRECATION")
    fun wifiAvailable(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    fun hasIdentity(context: Context): Boolean = IdentityStore(context.applicationContext).exists()

    /** Forget the key: the next activation asks for a fresh pairing code. */
    fun forget(context: Context) {
        runCatching { IdentityStore(context.applicationContext).clear() }
        synchronized(this) { configured = false }
    }

    /** One-shot pairing with the code from the system dialog; [host] is the phone's own address. */
    fun pair(context: Context, host: String, port: Int, code: String) {
        val operation = Operation("pair", port)
        try {
            operation.stage(AdbStage.VALIDATE)
            if (!PhoneActivationPolicy.isValidPort(port)) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.INVALID_PORT)
            if (!PhoneActivationPolicy.isValidCode(code)) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.INVALID_CODE)
            if (!LocalAddressPolicy.accepts(host, LocalAddressPolicy.ownAddresses())) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.NETWORK_UNREACHABLE)
            val address = LocalAddressPolicy.normalizeOrNull(host)!!
            operation.stage(AdbStage.IDENTITY)
            configure(context)
            operation.stage(AdbStage.PAIR_CONNECT)
            // Kadb pairing has no socket timeout. This opaque single-connection tunnel gives it a hard deadline
            // without spending the one-shot pairing port on a probe or terminating the end-to-end TLS session.
            PairingTunnel(address, port, onConnected = { operation.stage(AdbStage.PAIR_HANDSHAKE) }).use { tunnel ->
                try {
                    runBlocking { Kadb.pair(LocalAddressPolicy.LOOPBACK, tunnel.port, code, CLIENT_NAME) }
                } catch (e: Exception) {
                    // The client's SSL close is only a consequence when the target refused TCP or the deadline
                    // expired: keep the original transport failure.
                    val failure = tunnel.failure
                    if (failure != null && failure.kind != PairingTunnel.FailureKind.RELAY) {
                        val stage = if (failure.kind == PairingTunnel.FailureKind.CONNECT) AdbStage.PAIR_CONNECT else operation.current
                        throw operation.failure(failure.error, stage)
                    }
                    throw e
                }
            }
            operation.success()
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    /** Runs [block] in an adb shell session on the main wireless-debugging port (never a pairing port). */
    fun <T> session(context: Context, host: String, port: Int, block: (Kadb) -> T): T {
        val operation = Operation("session", port)
        try {
            operation.stage(AdbStage.VALIDATE)
            if (!PhoneActivationPolicy.isValidPort(port)) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.INVALID_PORT)
            if (!LocalAddressPolicy.accepts(host, LocalAddressPolicy.ownAddresses())) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.NETWORK_UNREACHABLE)
            val address = LocalAddressPolicy.normalizeOrNull(host)!!
            operation.stage(AdbStage.IDENTITY)
            configure(context)
            operation.stage(AdbStage.CONNECT)
            // Kadb connects lazily with the first stream, TLS and key authorization included.
            return Kadb.create(address, port, connectTimeout = 3_000, socketTimeout = 15_000).use { adb ->
                operation.stage(AdbStage.SHELL)
                val result = block(adb)
                operation.success()
                result
            }
        } catch (e: Exception) {
            throw operation.failure(e)
        }
    }

    /**
     * Starts the same activation as the computer command. The ADB session is torn down by the activation itself
     * (it restarts this app), so everything after the dispatch line is only confirmed by the app's own state.
     * Returns the launch nonce.
     */
    fun activate(context: Context, host: String, port: Int, audio: Boolean, state: PhoneActivationState): String {
        var submitted = false
        var nonce = ""
        try {
            session(context, host, port) { adb ->
                val uid = adb.shell("id -u")
                if (uid.exitCode != 0 || !PhoneActivationPolicy.isShellUid(uid.output))
                    throw AdbAccessException(AdbStage.SHELL, AdbReason.SHELL_REJECTED)
                val apk = context.applicationInfo.sourceDir
                val installed = adb.shell("pm path ${context.packageName}")
                if (installed.exitCode != 0 || !PhoneActivationPolicy.packageListed(installed.output, apk))
                    throw AdbAccessException(AdbStage.SHELL, AdbReason.PACKAGE_MISMATCH)
                if (adb.shell(PhoneActivationPolicy.PREFLIGHT).exitCode != 0)
                    throw AdbAccessException(AdbStage.SHELL, AdbReason.UNSUPPORTED_SHELL)
                nonce = PhoneActivationPolicy.newNonce()
                val options = ActivationToken(context).options(audio)
                val command = PhoneActivationPolicy.dispatchCommand(apk, options, nonce)
                state.connectPort = port
                state.begin(nonce) // on disk before the app is restarted
                submitted = true
                val result = adb.shell(command)
                if (!PhoneActivationPolicy.dispatchAccepted(result.exitCode, result.output, nonce))
                    throw AdbAccessException(AdbStage.DISPATCH, AdbReason.DISPATCH_UNCONFIRMED)
            }
        } catch (e: AdbAccessException) {
            // The restart may cut the stream after a successful dispatch: only an exception that could not
            // have started the launcher is a failure.
            if (submitted && e.reason != AdbReason.DISPATCH_UNCONFIRMED) {
                AppLog.i("PHONE activation dispatched, stream lost: ${e.reason}")
                return nonce
            }
            state.clear()
            throw e
        }
        return nonce
    }

    /** The launcher's log (bounded tail); removes the log files afterwards. */
    fun readLog(context: Context, host: String, port: Int, nonce: String): String =
        session(context, host, port) { adb ->
            val log = adb.shell(PhoneActivationPolicy.readLogCommand(nonce)).output
            adb.shell(PhoneActivationPolicy.REMOVE_LOGS)
            log
        }

    private class Operation(private val name: String, port: Int) {
        private val started = System.nanoTime()
        private val safePort = if (PhoneActivationPolicy.isValidPort(port)) port.toString() else "invalid"
        @Volatile var current: AdbStage = AdbStage.VALIDATE
            private set
        private var logged: AdbAccessException? = null

        fun stage(value: AdbStage) {
            current = value
            AppLog.detail("${prefix()} result=started")
        }

        fun success() = AppLog.i("${prefix()} result=success")

        fun failure(error: Exception, stage: AdbStage = current): AdbAccessException {
            if (error === logged) return error
            val failure = if (error is AdbAccessException) error else AdbAccessException(stage,
                if (stage == AdbStage.IDENTITY) AdbReason.IDENTITY_FAILURE else AdbDiagnostics.classify(error, stage))
            current = failure.stage
            // Never hand an unsanitized Throwable to AppLog: the pairing library may put codes or keys in it.
            AppLog.e("${prefix()} result=failed reason=${failure.reason} trace=${AdbDiagnostics.safeTrace(error)}")
            logged = failure
            return failure
        }

        private fun prefix(): String = "PHONE adb operation=$name stage=$current port=$safePort elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
    }

    /** The ADB private identity is encrypted with a non-exportable Android Keystore AES key. */
    private class IdentityStore(context: Context) : KadbPrivateKeyStore {
        private val file = context.noBackupFilesDir.resolve("headswap-adb-identity.bin")
        private val alias = "headswap.adb.identity.v1"
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun key(): SecretKey {
            (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        fun exists(): Boolean = file.exists()
        @Synchronized override fun readPrivateKeyPem(): ByteArray? {
            if (!file.exists()) return null
            val data = file.readBytes()
            require(data.size in 30..16_384 && data[0] == 1.toByte()) { "Повреждена сохранённая ADB-идентичность" }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
                doFinal(data.copyOfRange(13, data.size))
            }
        }
        @Synchronized override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val tmp = file.resolveSibling("${file.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(byteArrayOf(1) + cipher.iv + cipher.doFinal(privateKeyPem))
                out.fd.sync()
            }
            check(tmp.renameTo(file)) { "Не удалось сохранить ADB-идентичность" }
        }
        @Synchronized override fun clear() {
            file.delete()
            file.resolveSibling("${file.name}.tmp").delete()
            keyStore().deleteEntry(alias)
        }
    }
}
