// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0:
// IccoaControl.kt, IccoaInboundFrame.kt, IccoaHandshakeException.kt.
// Изменено для Headswap: protobuf через PbReader проекта, ack, порты; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbField
import com.yegorkapomidorka.headswap.PbReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Кадр ICCOA: заголовок 20 байт BE, CRC-16/ARC, тело в AES-GCM-конвертах. Без состояния сессии. */
object IccoaFrame {
    const val HEADER_SIZE = 20
    const val MAX_FRAME_SIZE = 4_000_000

    const val TYPE_NOTIFY = 0
    const val TYPE_REQUEST = 1
    const val TYPE_RESPONSE = 2
    const val TYPE_NEEDS_ACK = 3

    const val CAT_CONTROL = 1
    const val CAT_SENSOR = 2
    const val CAT_AUTH = 3
    const val CAT_PCM = 4
    const val CAT_ACK = 7

    const val AUTH_PORT = 57209
    const val CONTROL_PORT = 57219
    const val AUDIO_PORT = 57229
    const val SENSOR_PORT = 57239

    data class Frame(val sequence: Int, val category: Int, val method: Int,
                     val type: Int, val format: Int, val body: ByteArray)

    fun crc16(bytes: ByteArray, length: Int = bytes.size): Int {
        require(length in 0..bytes.size)
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return crc
    }

    fun frame(category: Int, method: Int, type: Int, body: ByteArray,
              sequence: Int, format: Int = 1,
              timeSeconds: Int = (System.currentTimeMillis() / 1000).toInt()): ByteArray {
        require(category in 0..255 && method in 0..65535 && type in 0..31 && format in 0..3)
        require(body.size <= MAX_FRAME_SIZE - HEADER_SIZE)
        val result = ByteBuffer.allocate(HEADER_SIZE + body.size)
            .putInt(HEADER_SIZE + body.size).putInt(sequence).putInt(timeSeconds)
            .put(((type shl 3) or (format shl 1) or 1).toByte()).put(category.toByte())
            .putShort(method.toShort()).putShort(0).putShort(0).put(body).array()
        ByteBuffer.wrap(result).putShort(18, crc16(result, 18).toShort())
        return result
    }

    /** Ack на кадр type 3. По спеке XDL (cat 7, m0, type 2, пустое тело); FT ack не шлёт. */
    fun ack(sequence: Int): ByteArray = frame(CAT_ACK, 0, TYPE_RESPONSE, byteArrayOf(), sequence)

    fun read(input: InputStream, onHeader: ((String) -> Unit)? = null): Frame =
        readOrNull(input, onHeader) ?: throw EOFException("ICCOA channel closed")

    /** null только при EOF между кадрами; обрыв внутри заголовка или тела — EOFException. */
    fun readOrNull(input: InputStream, onHeader: ((String) -> Unit)? = null): Frame? {
        val stream = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        val first = stream.read()
        if (first < 0) return null
        header[0] = first.toByte()
        stream.readFully(header, 1, HEADER_SIZE - 1)
        val view = ByteBuffer.wrap(header)
        val length = view.int
        val flags = header[12].toInt() and 255
        val crcValid = (view.getShort(18).toInt() and 65535) == crc16(header, 18)
        // В лог только числа заголовка: тело может нести ключи и PIN.
        onHeader?.invoke("length=$length category=${header[13].toInt() and 255} method=${view.getShort(14).toInt() and 65535} flags=$flags crcValid=$crcValid")
        // Длина и CRC проверяются до выделения тела: длина приходит от чужой стороны.
        if (length !in HEADER_SIZE..MAX_FRAME_SIZE) throw IccoaHandshakeException(IccoaHandshakeException.Reason.FRAME_LENGTH)
        if (!crcValid) throw IccoaHandshakeException(IccoaHandshakeException.Reason.HEADER_CRC)
        // Бит 0 флагов не проверяем: XA60 отвечает 0x12.
        val body = ByteArray(length - HEADER_SIZE)
        stream.readFully(body)
        return Frame(view.getInt(4), header[13].toInt() and 255,
            view.getShort(14).toInt() and 65535, flags ushr 3, (flags ushr 1) and 3, body)
    }

    /** AES-128-GCM сессии: один ключ на control, audio и UIBC. */
    class Cipher(key: ByteArray) {
        private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 16) }, "AES")
        private val random = SecureRandom()
        // Каналы пишут из разных потоков: у каждого потока свой javax Cipher.
        private class LocalCipher : ThreadLocal<javax.crypto.Cipher>() {
            override fun initialValue(): javax.crypto.Cipher =
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        }
        private val encryption = LocalCipher()
        private val decryption = LocalCipher()

        fun encrypt(plain: ByteArray): ByteArray {
            require(plain.size <= MAX_FRAME_SIZE - 56)
            val iv = ByteArray(12).also(random::nextBytes)
            val aes = encryption.get()!!
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
            val encrypted = aes.doFinal(plain)
            return ByteBuffer.allocate(8 + iv.size + encrypted.size)
                .putInt(iv.size).put(iv).putInt(encrypted.size).put(encrypted).array()
        }

        /** Несколько конвертов подряд склеиваются в одно тело. */
        fun decrypt(envelope: ByteArray): ByteArray {
            require(envelope.size in 36..MAX_FRAME_SIZE) { "Invalid ICCOA encrypted body size" }
            val input = ByteBuffer.wrap(envelope)
            val result = ByteArrayOutputStream()
            while (input.hasRemaining()) {
                require(input.remaining() >= 36 && input.int == 12) { "Invalid ICCOA GCM IV" }
                val iv = ByteArray(12).also(input::get)
                val size = input.int
                require(size in 16..input.remaining()) { "Invalid ICCOA GCM length" }
                val encrypted = ByteArray(size).also(input::get)
                val aes = decryption.get()!!
                aes.init(javax.crypto.Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
                result.write(aes.doFinal(encrypted))
            }
            return result.toByteArray()
        }
    }

    fun isEncrypted(body: ByteArray): Boolean = body.size >= 4 && ByteBuffer.wrap(body).int == 12

    /** Тело входящего кадра. authenticatedControl — зашифрованный кадр control с порта 57219: только он доказывает, что машина приняла сессию. */
    data class Inbound(val body: ByteArray, val authenticatedControl: Boolean)

    fun decode(port: Int, frame: Frame, cipher: Cipher): Inbound {
        val encrypted = isEncrypted(frame.body)
        val body = if (encrypted) cipher.decrypt(frame.body) else frame.body
        return Inbound(body, encrypted && port == CONTROL_PORT &&
            frame.category == CAT_CONTROL && frame.method != 23)
    }

    /** Строгий разбор protobuf рукопожатия: обрыв, неизвестный wire type и повтор поля — отказ. */
    class Fields(bytes: ByteArray) {
        private val fields = LinkedHashMap<Int, PbField>()
        init {
            val parsed = requireNotNull(PbReader.parse(bytes)) { "Malformed protobuf" }
            for (f in parsed) require(fields.put(f.number, f) == null) { "Duplicate handshake field" }
        }

        fun number(field: Int, default: Long = 0): Long {
            val value = fields[field] ?: return default
            require(value.wire == 0) { "Invalid handshake integer field $field" }
            return value.num
        }

        fun bytes(field: Int): ByteArray = optionalBytes(field) ?: error("Missing handshake field $field")

        /** Поле другого типа — ошибка, а не «поля нет». */
        fun optionalBytes(field: Int): ByteArray? {
            val value = fields[field] ?: return null
            return requireNotNull(value.data) { "Invalid handshake binary field $field" }
        }

        /** Номера и длины полей без значений — это можно в лог. */
        fun shape(): String = fields.values.joinToString(",") { f ->
            "${f.number}:" + if (f.data != null) "bytes(${f.data.size})" else "number"
        }
    }
}

/** В диагностику идут только код причины и числовой result машины. */
class IccoaHandshakeException(val reason: Reason, val result: Long? = null) :
    IllegalArgumentException(reason.name) {
    enum class Reason {
        FRAME_LENGTH, HEADER_CRC, RESPONSE_LANE, RESPONSE_RESULT,
        IDENTITY_LENGTH, AGREEMENT_LENGTH, NONCE_LENGTH, PIN_MAC, AGREEMENT_SIGNATURE,
        TRUSTED_IDENTITY_MISMATCH
    }

    val diagnostic: String get() = reason.name + (result?.let { " result=$it" } ?: "")
}
