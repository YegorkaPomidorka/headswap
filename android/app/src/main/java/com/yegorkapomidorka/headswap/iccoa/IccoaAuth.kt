// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaAuth.kt.
// Изменено для Headswap: PbWriter проекта, workModes параметром; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbWriter
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Вход ICCOA (cat 3). Ровно один способ: PIN (первый раз) или pinnedCarIdentity (быстрый вход без PIN).
 * Ключи согласования и nonce живут одно подключение. PIN и ключи в лог и исключения не попадают.
 */
class IccoaAuth(
    private val pin: String?, private val clientId: String, private val model: String,
    private val identity: KeyPair = newKeyPair(), private val pinnedCarIdentity: ByteArray? = null,
    private val workModes: Int = 5
) {
    private val agreement = newKeyPair()
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes)

    /** Сохранять можно только после первого расшифрованного кадра control: Confirm сам по себе ничего не доказывает. */
    var verifiedCarIdentity: ByteArray? = null
        private set

    val quick: Boolean get() = pinnedCarIdentity != null

    init {
        require((pin == null) == (pinnedCarIdentity != null)) { "Exactly one ICCOA authentication method is required" }
        if (pin != null) require(pin.matches(Regex("[0-9]{6}"))) { "PIN must contain six digits" }
        if (pinnedCarIdentity != null) require(pinnedCarIdentity.size == 64) { "Invalid pinned car key" }
    }

    /** Тело cat 3 method 1. */
    fun request(): ByteArray {
        val identityBytes = publicBytes(identity.public as ECPublicKey)
        val agreementBytes = publicBytes(agreement.public as ECPublicKey)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(agreementBytes + nonce)
            sign()
        }
        val request = PbWriter().int32(1, 1793)
        // Быстрый вход: без authPub, HMAC и userConfirmed.
        if (pin != null) request.bytes(2, identityBytes).bytes(3, pinMac(pin, identityBytes, nonce))
        request.bytes(4, agreementBytes).bytes(5, signature).bytes(6, nonce)
            .string(7, clientId).string(8, model)
        if (pin != null) request.int32(9, 1)
        return request.int32(10, workModes).string(11, model).toByteArray()
    }

    /** Проверяет ответ машины (result, HMAC от PIN, подпись) и выводит ключ сессии. Провал — IccoaHandshakeException. */
    fun accept(response: ByteArray, onProgress: (String) -> Unit = {}): IccoaFrame.Cipher {
        onProgress("parse-response")
        val fields = IccoaFrame.Fields(response)
        onProgress("response-fields ${fields.shape()}")
        val result = fields.number(7)
        onProgress("receiver-result=$result")
        if (result != 0L) throw IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_RESULT, result)
        val responseIdentity = if (quick) fields.optionalBytes(2) ?: ByteArray(0) else fields.bytes(2)
        val carIdentity = if (quick) {
            val known = pinnedCarIdentity ?: error("No pinned car key")
            // Часть машин при быстром входе authPub не шлёт; если прислала — должен совпасть с сохранённым.
            if (responseIdentity.isNotEmpty() && !MessageDigest.isEqual(responseIdentity, known))
                throw IccoaHandshakeException(IccoaHandshakeException.Reason.TRUSTED_IDENTITY_MISMATCH)
            known
        } else responseIdentity.also {
            if (it.size != 64) throw IccoaHandshakeException(IccoaHandshakeException.Reason.IDENTITY_LENGTH)
        }
        val carAgreement = fields.bytes(4).also { if (it.size != 64) throw IccoaHandshakeException(IccoaHandshakeException.Reason.AGREEMENT_LENGTH) }
        val carNonce = fields.bytes(6).also { if (it.size != 32) throw IccoaHandshakeException(IccoaHandshakeException.Reason.NONCE_LENGTH) }
        onProgress("verify-pin-mac")
        if (pin != null) {
            val expectedMac = pinMac(pin, carIdentity + publicBytes(identity.public as ECPublicKey), carNonce + nonce)
            if (!MessageDigest.isEqual(expectedMac, fields.bytes(3)))
                throw IccoaHandshakeException(IccoaHandshakeException.Reason.PIN_MAC)
        }
        onProgress("verify-agreement-signature")
        val verified = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey(carIdentity))
                update(carAgreement + publicBytes(agreement.public as ECPublicKey) + carNonce + nonce)
                verify(fields.bytes(5))
            }
        } catch (e: java.security.GeneralSecurityException) {
            false   // кривой DER подписи или точка не на кривой — тот же отказ
        }
        if (!verified) throw IccoaHandshakeException(IccoaHandshakeException.Reason.AGREEMENT_SIGNATURE)
        verifiedCarIdentity = carIdentity.copyOf()
        onProgress("derive-session-key")
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(agreement.private)
            doPhase(publicKey(carAgreement), true)
            generateSecret()
        }
        return try {
            val key = hkdf(shared, sha256(nonce + carNonce), "session_key".toByteArray(), 16)
            try { IccoaFrame.Cipher(key) } finally { key.fill(0) }
        } finally { shared.fill(0) }
    }

    /** Тело cat 3 method 3: после него машина идёт на порты телефона. */
    fun confirm(cipher: IccoaFrame.Cipher, localAddress: String): ByteArray =
        PbWriter().bytes(1, cipher.encrypt("$localAddress:0".toByteArray(Charsets.UTF_8))).toByteArray()

    companion object {
        fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /** Открытый ключ как X‖Y по 32 байта. */
        fun publicBytes(key: ECPublicKey): ByteArray {
            fun coordinate(value: BigInteger): ByteArray {
                val encoded = value.toByteArray()
                return ByteArray(32).also {
                    val size = minOf(encoded.size, 32)
                    encoded.copyInto(it, 32 - size, encoded.size - size)
                }
            }
            return coordinate(key.w.affineX) + coordinate(key.w.affineY)
        }

        fun publicKey(encoded: ByteArray): ECPublicKey {
            require(encoded.size == 64)
            val parameters = AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
            val point = ECPoint(BigInteger(1, encoded.copyOfRange(0, 32)), BigInteger(1, encoded.copyOfRange(32, 64)))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters)) as ECPublicKey
        }

        internal fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        internal fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

        /** HMAC-SHA256(key = SHA256(salt‖PIN), message). */
        internal fun pinMac(pin: String, message: ByteArray, salt: ByteArray): ByteArray =
            hmac(sha256(salt + pin.toByteArray(Charsets.UTF_8)), message)

        internal fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
            require(size in 1..(255 * 32))
            val prk = hmac(salt, secret)
            val result = ByteArray(size)
            var previous = ByteArray(0)
            var position = 0
            var counter = 1
            try {
                while (position < size) {
                    previous = hmac(prk, previous + info + byteArrayOf(counter++.toByte()))
                    val count = minOf(previous.size, size - position)
                    previous.copyInto(result, position, 0, count)
                    position += count
                }
            } finally { prk.fill(0); previous.fill(0) }
            return result
        }
    }
}
