package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnobLogicTest {
    @Test fun rotaryDirection() {
        assertEquals(Knob.NEXT, KnobLogic.forRotary(1))
        assertEquals(Knob.PREVIOUS, KnobLogic.forRotary(-1))
        assertNull(KnobLogic.forRotary(0))
    }

    @Test fun headUnitKeysAsBridgeMapsThem() {
        // AaBridge.HARD_KEY_TO_ANDROID: MOVE_* -> DPAD, OK -> DPAD_CENTER, MAIN -> HOME, NAVI -> 65538, MEDIA -> MUSIC
        assertEquals(Knob.UP, KnobLogic.forKey(19))
        assertEquals(Knob.DOWN, KnobLogic.forKey(20))
        assertEquals(Knob.LEFT, KnobLogic.forKey(21))
        assertEquals(Knob.RIGHT, KnobLogic.forKey(22))
        assertEquals(Knob.OK, KnobLogic.forKey(23))
        assertEquals(Knob.HOME, KnobLogic.forKey(3))
        assertEquals(Knob.NAVI, KnobLogic.forKey(65538))
        assertEquals(Knob.MUSIC, KnobLogic.forKey(209))
    }

    @Test fun backAndMediaKeysStayOnInjection() {
        assertNull(KnobLogic.forKey(4)) // BACK: окно лаунчера -> hardwareBack
        assertNull(KnobLogic.forKey(87))
        assertNull(KnobLogic.forKey(126))
    }

    @Test fun onlyNavigationMovesFocus() {
        assertTrue(KnobLogic.moves(Knob.NEXT))
        assertTrue(KnobLogic.moves(Knob.LEFT))
        assertFalse(KnobLogic.moves(Knob.OK))
        assertFalse(KnobLogic.moves(Knob.NAVI))
    }

    @Test fun windowGetsArrowsAndCenterOnly() {
        assertEquals(20, KnobLogic.windowKey(Knob.NEXT))
        assertEquals(19, KnobLogic.windowKey(Knob.PREVIOUS))
        assertEquals(21, KnobLogic.windowKey(Knob.LEFT))
        assertEquals(23, KnobLogic.windowKey(Knob.OK))
        assertNull(KnobLogic.windowKey(Knob.HOME))
        assertNull(KnobLogic.windowKey(Knob.NAVI))
        assertNull(KnobLogic.windowKey(Knob.MUSIC))
    }

    @Test fun secondHomeWithinWindowIsDouble() {
        assertFalse(KnobLogic.doubleHome(0, 5_000)) // первое нажатие
        assertTrue(KnobLogic.doubleHome(5_000, 5_300))
        assertTrue(KnobLogic.doubleHome(5_000, 5_000 + KnobLogic.DOUBLE_HOME_MS))
        assertFalse(KnobLogic.doubleHome(5_000, 5_001 + KnobLogic.DOUBLE_HOME_MS))
        // Захар, h829: два «Назад» с шагом 0,999 с - обычные отдельные нажатия, такое двойным не считаем
        assertFalse(KnobLogic.doubleHome(5_000, 5_999))
        assertFalse(KnobLogic.doubleHome(5_000, 4_000)) // часы не идут назад, но и не падаем
    }
}
