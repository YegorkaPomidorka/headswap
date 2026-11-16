package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaImagesLimitTest {
    /** Как JPEG 256x256: размер растёт с качеством, q90 ≈ 27 КБ, q50 ≈ 15 КБ. */
    private fun fakeJpeg(q: Int) = ByteArray(q * 300)

    @Test fun limitOffIsUnchanged() {
        assertEquals(30720, MetaImages.artLimit(art16k = false, mazda20622111 = false))
        assertEquals(MetaImages.MAX_BYTES, MetaImages.artLimit(false, false))
    }

    @Test fun limitOnIs16k() {
        assertEquals(16384, MetaImages.artLimit(art16k = true, mazda20622111 = false))
    }

    @Test fun mazdaStaysStricter() {
        assertEquals(AaBridgeCore.MAZDA_20622111_ART_BYTES, MetaImages.artLimit(false, true))
        assertEquals(AaBridgeCore.MAZDA_20622111_ART_BYTES, MetaImages.artLimit(true, true))
    }

    @Test fun offKeepsQ90Cover() {
        val out = MetaImages.fitQuality(MetaImages.artLimit(false, false), ::fakeJpeg)!!
        assertEquals(27000, out.size)
    }

    @Test fun onFitsUnder16k() {
        val out = MetaImages.fitQuality(MetaImages.artLimit(true, false), ::fakeJpeg)!!
        assertTrue(out.size <= 16384)
        assertEquals(15000, out.size) // q50: самое лучшее качество, что влезло
    }

    @Test fun nullWhenNothingFits() {
        assertNull(MetaImages.fitQuality(100) { ByteArray(200) })
    }
}
