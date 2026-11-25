// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaInputServerTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import org.json.JSONObject

class IccoaInputReaderTest {
    /** Шифрованный поток: тач и кнопка из двух конвертов, режим виден в логе. */
    @Test fun encryptedStreamYieldsTouchAndKeyEvents() {
        val cipher = IccoaFrame.Cipher(ByteArray(16) { it.toByte() })
        val video = IccoaVideoConfig("127.0.0.1", 2422, 1272)
        val touches = mutableListOf<IccoaTouch>()
        val keys = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val stream = cipher.encrypt("""{"type":1,"action":0,"count":1,"x0":1211,"y0":636,"trackID0":0,"width":2422,"height":1272}{"type":2,"action":0,"keycode":4}""".toByteArray()) +
            cipher.encrypt("""{"type":2,"action":1,"keycode":4}{"type":1,"action":1,"x0":10,"y0":20}""".toByteArray())
        IccoaInputReader.read(ByteArrayInputStream(stream), cipher::decrypt, IccoaInputJson { event: JSONObject ->
            if (event.optInt("type") == 1) IccoaInputEvent.touch(event, video)?.let(touches::add)
            else IccoaInputEvent.key(event)?.let(keys::add)
        }, logs::add)
        assertEquals(listOf(IccoaTouch(IccoaTouch.DOWN, 1211, 636), IccoaTouch(IccoaTouch.UP, 10, 20)), touches)
        assertEquals(listOf(4), keys)
        assertEquals(listOf("UIBC mode=encrypted"), logs)
    }

    @Test fun cleanAndTruncatedInputAreDistinguishedAtEveryPacketBoundary() {
        val cipher = IccoaFrame.Cipher(ByteArray(16))
        val packet = cipher.encrypt("{\"type\":1}".toByteArray())
        fun read(bytes: ByteArray): List<Int> {
            val events = mutableListOf<Int>()
            IccoaInputReader.read(ByteArrayInputStream(bytes), cipher::decrypt,
                IccoaInputJson { events += it.getInt("type") })
            return events
        }
        assertTrue(read(byteArrayOf()).isEmpty())
        assertEquals(listOf(1, 1), read(packet + packet))
        for (size in 1 until packet.size) {
            assertThrows("Truncated encrypted packet: $size bytes", EOFException::class.java) {
                read(packet.copyOf(size))
            }
            assertThrows("Truncated next encrypted packet: $size bytes", EOFException::class.java) {
                read(packet + packet.copyOf(size))
            }
        }
    }

    @Test fun plaintextObjectsCanSpanReadsButUnfinishedJsonIsAnError() {
        val events = mutableListOf<Int>()
        val bytes = "{\"type\":1}{\"type\":2}\n".toByteArray()
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        IccoaInputReader.read(fragmented, { error("Plaintext must not decrypt") },
            IccoaInputJson { events += it.getInt("type") })
        assertEquals(listOf(1, 2), events)
        assertThrows(EOFException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream("{\"type\":".toByteArray()), { it }, IccoaInputJson {})
        }
    }

    @Test fun invalidCiphertextAndOversizedPacketsAreStillRejected() {
        val cipher = IccoaFrame.Cipher(ByteArray(16))
        val corrupt = cipher.encrypt("{\"type\":1}".toByteArray()).apply {
            this[lastIndex] = (last().toInt() xor 1).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(corrupt), cipher::decrypt, IccoaInputJson {})
        }
        val invalid = ByteBuffer.allocate(20).putInt(12).put(ByteArray(12)).putInt(65537).array()
        assertThrows(IllegalArgumentException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(invalid), cipher::decrypt, IccoaInputJson {})
        }
    }

    @Test fun completeEncryptedEnvelopeWithUnfinishedJsonIsNotCleanEof() {
        val cipher = IccoaFrame.Cipher(ByteArray(16))
        assertThrows(EOFException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(cipher.encrypt("{\"type\":".toByteArray())),
                cipher::decrypt, IccoaInputJson {})
        }
    }

    @Test fun cleanEofReleasesActiveGestureExactlyOnce() {
        IccoaInputGesture().finish()
        var releases = 0
        val gesture = IccoaInputGesture { releases++ }
        gesture.accept(0)
        for (action in listOf(2, 5 or (1 shl 8), 6 or (1 shl 8), 3)) {
            gesture.accept(action)
        }
        gesture.finish()
        gesture.finish()
        assertEquals(1, releases)
        gesture.accept(0)
        gesture.accept(1)
        gesture.finish()
        assertEquals(1, releases)
        gesture.accept(0)
        gesture.finish()
        assertEquals(2, releases)
    }

    @Test fun truncatedPlaintextPrefixIsNotCleanEof() {
        assertThrows(EOFException::class.java) {
            IccoaInputReader.read(ByteArrayInputStream(byteArrayOf(0, 0)), { it }, IccoaInputJson {})
        }
    }
}
