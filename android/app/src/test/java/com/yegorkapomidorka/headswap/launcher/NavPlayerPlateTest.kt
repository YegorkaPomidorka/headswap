package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.launcher.NavPlayerPlate.Look
import org.junit.Assert.assertEquals
import org.junit.Test

class NavPlayerPlateTest {
    private fun look(
        navArea: Boolean = true, navReady: Boolean = true, carHost: Boolean = false, access: Boolean = true,
        hasSession: Boolean = true, ringing: Boolean = false, alice: Boolean = false,
    ) = NavPlayerPlate.look(navArea, navReady, carHost, access, hasSession, ringing, alice)

    @Test fun showsOnlyOverReadyWindowedNavigatorWithSession() {
        assertEquals(Look.FULL, look())
        assertEquals(Look.HIDDEN, look(navArea = false))
        assertEquals(Look.HIDDEN, look(navReady = false))
        assertEquals(Look.HIDDEN, look(carHost = true))
        assertEquals(Look.HIDDEN, look(access = false))
        assertEquals(Look.HIDDEN, look(hasSession = false))
    }

    @Test fun ringingAndAliceHideIt() {
        assertEquals(Look.HIDDEN, look(ringing = true))
        assertEquals(Look.HIDDEN, look(alice = true))
    }

    @Test fun sitsBetweenLeftAndRightPanels() {
        // Vezel 1280x720: область 1136 после рейки 144, плашка 340 - между 515 и 991
        val left = NavPlayerPlate.left(1136, 340, 1f)
        assertEquals(583, left)
        // широкая ГУ 1920x720: та же середина промежутка
        assertEquals(515 + (1920 - 144 - 145 - 515 - 340) / 2, NavPlayerPlate.left(1920 - 144, 340, 1f))
        // узкая область: прижата к правой панели, не уходит за начало
        assertEquals(800 - 145 - 340, NavPlayerPlate.left(800, 340, 1f))
        assertEquals(0, NavPlayerPlate.left(300, 340, 1f))
    }

    @Test fun defaultPlaceIsBottomInTheGap() {
        assertEquals(583 to 720 - 72 - 19, NavPlayerPlate.origin(null, 1136, 720, 340, 72, 1f))
        // экран крупнее макета: отступы растут с k
        assertEquals(NavPlayerPlate.left(1704, 510, 1.5f) to 1080 - 108 - 28, NavPlayerPlate.origin(null, 1704, 1080, 510, 108, 1.5f))
    }

    @Test fun draggedPlaceSurvivesOtherAreaSize() {
        val p = NavPlayerPlate.pos(398f, 324f, 1136, 720, 340, 72)
        assertEquals(0.5f, p.fx, 0.001f)
        assertEquals(0.5f, p.fy, 0.001f)
        // та же середина на широкой ГУ
        assertEquals((1776 - 340) / 2 to (720 - 72) / 2, NavPlayerPlate.origin(p, 1776, 720, 340, 72, 1f))
        // за край не уходит
        assertEquals(NavPlayerPlate.Pos(0f, 1f), NavPlayerPlate.pos(-50f, 5000f, 1136, 720, 340, 72))
    }

    @Test fun posSavesAndRestores() {
        val p = NavPlayerPlate.Pos(0.25f, 0.75f)
        assertEquals(p, NavPlayerPlate.Pos.restore(p.save()))
        assertEquals(null, NavPlayerPlate.Pos.restore(null))
        assertEquals(null, NavPlayerPlate.Pos.restore("мусор"))
        assertEquals(NavPlayerPlate.Pos(1f, 0f), NavPlayerPlate.Pos.restore("3,-1"))
    }
}
