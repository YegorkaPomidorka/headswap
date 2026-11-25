// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaInputEventTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IccoaInputEventTest {
    private val video = IccoaVideoConfig("127.0.0.1", 1920, 1080)
    private fun touch(json: String) = IccoaInputEvent.touch(JSONObject(json), video)

    @Test fun missingAndZeroDimensionsUseNegotiatedResolution() {
        for (dimensions in listOf("", ",\"width\":0,\"height\":0")) {
            val event = touch("{\"action\":0,\"x0\":321,\"y0\":654$dimensions}")!!
            assertEquals(321, event.x)
            assertEquals(654, event.y)
            assertEquals(-1, event.x2)
        }
    }

    @Test fun explicitDimensionsScaleAndClampCoordinates() {
        val event = touch("""{"action":2,"count":1,"width":960,"height":540,"x0":480,"y0":600}""")!!
        assertEquals(960, event.x)
        assertEquals(1079, event.y)
    }

    @Test fun invalidTouchDoesNotThrowOrBecomeAnOriginTap() {
        for (fields in listOf("", "\"x0\":1,\"y0\":2,\"width\":-1",
            "\"x0\":1,\"y0\":2,\"count\":0", "\"x0\":1,\"y0\":2,\"count\":3",
            "\"x0\":\"NaN\",\"y0\":2")) {
            assertNull(touch("{\"action\":0${if (fields.isEmpty()) "" else ",$fields"}}"))
        }
    }

    @Test fun twoFingerPointerIndexAndCoordinatesArePreserved() {
        val event = touch("""{"action":261,"count":2,"x0":10,"y0":20,"x1":30,"y1":40}""")!!
        assertEquals(5, event.action)
        assertEquals(1, event.index)
        assertEquals(30, event.x2)
        assertEquals(40, event.y2)
    }
}
