// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0:
// IccoaInputEvent.kt, IccoaInputReader/IccoaInputGesture из IccoaInputServer.kt,
// IccoaInputJson из IccoaMediaTransport.kt, CarTouch.kt.
// Изменено для Headswap: без сокетов, свой тип касания, разбор кнопки; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/** Касание в пикселях экрана машины. x2/y2 = -1, если палец один; index — палец для POINTER_*. */
data class IccoaTouch(val action: Int, val x: Int, val y: Int, val x2: Int = -1, val y2: Int = -1, val index: Int = 0) {
    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}

object IccoaInputEvent {
    /** Событие type=1. null — мусор: не превращаем его в тап в (0,0). */
    fun touch(event: JSONObject, video: IccoaVideoConfig): IccoaTouch? {
        val rawWidth = event.optInt("width", 0)
        val rawHeight = event.optInt("height", 0)
        if (rawWidth !in 0..8192 || rawHeight !in 0..8192) return null
        // Часть машин width/height не шлёт: координаты уже в размере экрана из M3.
        val width = rawWidth.takeIf { it > 0 } ?: video.width
        val height = rawHeight.takeIf { it > 0 } ?: video.height
        val count = event.optInt("count", 1)
        if (count !in 1..2) return null
        val action = event.optInt("action", -1)
        val kind = action and 255
        if (kind !in setOf(0, 1, 2, 3, 5, 6)) return null
        val index = action ushr 8 and 255
        if (kind in setOf(5, 6) && (count != 2 || index >= count)) return null
        fun coordinate(name: String, source: Int, target: Int): Int? {
            val value = event.optDouble(name, Double.NaN)
            if (!value.isFinite()) return null
            return (value * target / source).toInt().coerceIn(0, target - 1)
        }
        return IccoaTouch(kind,
            coordinate("x0", width, video.width) ?: return null,
            coordinate("y0", height, video.height) ?: return null,
            if (count > 1) coordinate("x1", width, video.width) ?: return null else -1,
            if (count > 1) coordinate("y1", height, video.height) ?: return null else -1, index)
    }

    /** Событие type≠1: Android keycode по нажатию (action 0); 3 Home, 4 Back. */
    fun key(event: JSONObject): Int? =
        event.optInt("keycode").takeIf { it > 0 && event.optInt("type") != 1 && event.optInt("action") == 0 }
}

/** Поток склеенных JSON: режем по балансу скобок, объект может прийти по частям; скобки в строках не считаются. */
class IccoaInputJson(private val emit: (JSONObject) -> Unit) {
    private val buffer = StringBuilder()

    /** EOF посреди объекта — обрыв, а не чистое закрытие. */
    fun finish() {
        if (buffer.isNotBlank()) throw EOFException("Incomplete UIBC JSON")
    }

    fun accept(bytes: ByteArray) {
        buffer.append(bytes.toString(Charsets.UTF_8))
        require(buffer.length <= 65536) { "UIBC object too large" }
        var depth = 0; var quoted = false; var escaped = false; var start = -1; var consumed = 0
        for (i in buffer.indices) {
            val c = buffer[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> { if (depth++ == 0) start = i }
                '}' -> if (depth > 0 && --depth == 0) {
                    emit(JSONObject(buffer.substring(start, i + 1))); consumed = i + 1
                }
            }
        }
        if (consumed > 0) buffer.delete(0, consumed)
        if (depth == 0 && buffer.length > 4096) buffer.clear()
    }
}

/** Читает UIBC до EOF. Первые 4 байта = 12 → AES-GCM-конверты, иначе открытый JSON. */
object IccoaInputReader {
    fun read(input: InputStream, decrypt: (ByteArray) -> ByteArray, json: IccoaInputJson,
             log: (String) -> Unit = {}) {
        val stream = DataInputStream(input)
        fun prefix(): ByteArray? {
            val first = stream.read()
            if (first < 0) return null
            return ByteArray(4).also { it[0] = first.toByte(); stream.readFully(it, 1, 3) }
        }
        var head = prefix() ?: return
        if (ByteBuffer.wrap(head).int == 12) {
            log("UIBC mode=encrypted")
            while (true) {
                val ivLength = ByteBuffer.wrap(head).int
                require(ivLength == 12) { "Invalid UIBC IV" }
                val iv = ByteArray(ivLength); stream.readFully(iv)
                val size = stream.readInt(); require(size in 17..65536) { "Invalid UIBC size" }
                val cipher = ByteArray(size); stream.readFully(cipher)
                val packet = ByteBuffer.allocate(8 + ivLength + size)
                    .putInt(ivLength).put(iv).putInt(size).put(cipher).array()
                json.accept(decrypt(packet))
                head = prefix() ?: break
            }
        } else {
            log("UIBC mode=plaintext")
            json.accept(head)
            val bytes = ByteArray(4096)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                json.accept(bytes.copyOf(n))
            }
        }
        json.finish()
    }
}

/** UIBC может закрыться отдельно от видео: зажатый палец надо отпустить, иначе он залипнет. */
class IccoaInputGesture(private val release: () -> Unit = {}) {
    private var active = false
    fun accept(action: Int) {
        when (action and 255) {
            0 -> active = true
            1 -> active = false
        }
    }
    fun finish() {
        if (active) {
            active = false
            release()
        }
    }
}
