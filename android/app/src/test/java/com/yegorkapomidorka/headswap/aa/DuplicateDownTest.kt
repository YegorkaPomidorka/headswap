package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.aa.AaBridgeCore.Companion.isDuplicateDown
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDownTest {
    @Test fun sameHeldPointWithinWindowIsDuplicate() {
        assertTrue(isDuplicateDown(100 to 200, 1, 100, 200))
        assertTrue(isDuplicateDown(100 to 200, 25, 100, 200))
        assertTrue(isDuplicateDown(100 to 200, AaBridgeCore.DUP_DOWN_MS, 100, 200))
    }

    @Test fun releasedFingerIsNewTap() {
        assertFalse(isDuplicateDown(null, 5, 100, 200))
    }

    @Test fun otherPointOrLateDownIsNewTap() {
        assertFalse(isDuplicateDown(100 to 200, 5, 101, 200))
        assertFalse(isDuplicateDown(100 to 200, 5, 100, 199))
        assertFalse(isDuplicateDown(100 to 200, AaBridgeCore.DUP_DOWN_MS + 1, 100, 200))
        assertFalse(isDuplicateDown(100 to 200, -1, 100, 200))
    }
}
