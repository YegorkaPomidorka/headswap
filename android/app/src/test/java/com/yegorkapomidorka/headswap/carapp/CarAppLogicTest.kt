package com.yegorkapomidorka.headswap.carapp

import androidx.car.app.navigation.model.Maneuver
import com.yegorkapomidorka.headswap.aa.AaMeta
import org.junit.Assert.*
import org.junit.Test

class CarAppLogicTest {
    @Test fun areasKeepCardsOutOfVisibleAndButtonsOutOfStable() {
        val (visible, stable) = CarAreas.of(1136, 720, leftPx = 440, rightPx = 120, topPx = 0)
        assertEquals(Box(440, 0, 1016, 720), visible)
        assertEquals(Box(440, 108, 1016, 720), stable)
    }

    @Test fun visibleNeverEqualsStableEvenWithoutButtons() {
        // без кнопок и карточек 2ГИС прячет спидометр, камеры и мини-карту - stable обязан отличаться
        for ((l, r, t) in listOf(Triple(0, 0, 0), Triple(300, 0, 0), Triple(0, 0, -5), Triple(5000, 5000, 5000))) {
            val (visible, stable) = CarAreas.of(712, 501, l, r, t)
            assertNotEquals("l=$l r=$r t=$t", visible, stable)
            assertTrue(stable.width > 0 && stable.height > 0)
            assertTrue(stable.left >= visible.left && stable.right <= visible.right && stable.top >= visible.top)
        }
        assertEquals(75, CarAreas.of(712, 501, 0, 0, 0).second.top)
    }

    @Test fun defaultAreasMatchTheProbe() {
        val (visible, stable) = CarAreas.default(712, 501)
        assertEquals(Box(256, 0, 641, 501), visible)
        assertEquals(Box(256, 75, 641, 501), stable)
    }

    @Test fun cardShowsCenterCropAndAreasFollowIt() {
        val crop = CarAreas.crop(1136, 720, 712, 501)
        assertEquals(Box(212, 109, 924, 610), crop)
        val (visible, stable) = CarAreas.of(crop, 0, 0, 0)
        assertEquals(crop, visible)
        assertEquals(Box(212, 109 + 75, 924, 610), stable)
        assertEquals(Box(0, 0, 1136, 720), CarAreas.crop(1136, 720, 1500, 800))
    }

    @Test fun emptySurfaceGivesEmptyAreas() {
        assertEquals(Box(0, 0, 0, 0), CarAreas.of(0, 720, 10, 10, 10).first)
    }

    @Test fun dpiIsAutoTimesScaleWithinLimits() {
        assertEquals(160, CarConfigMath.dpi(1f))
        assertEquals(80, CarConfigMath.dpi(0.5f))
        assertEquals(107, CarConfigMath.dpi(2f / 3f))
        assertEquals(160, CarConfigMath.dpi(0f))
        assertEquals(CarConfigMath.MAX_DPI, CarConfigMath.dpi(5f))
        assertEquals(CarConfigMath.MIN_DPI, CarConfigMath.dpi(0.1f))
        assertEquals(757, CarConfigMath.dp(1136, 240))
        assertEquals(0, CarConfigMath.dp(1136, 0))
    }

    @Test fun pickSkipsOwnPackageAndKeepsFirstServicePerPackage() {
        val a = CarAppTarget("ru.dublgis.dgismobile", "ru.dublgis.car.CarService", 1, null)
        val b = CarAppTarget("ru.dublgis.dgismobile", "other.Service", 1, null)
        val own = CarAppTarget("com.yegorkapomidorka.headswap", "x.Y", 1, null)
        val picked = CarAppPick.targets(listOf(a, b, own), "com.yegorkapomidorka.headswap")
        assertEquals(mapOf(a.pkg to a), picked)
    }

    @Test fun apiLevelIsAppLatestCappedByHost() {
        assertEquals(7, CarAppPick.apiLevel(4, 7, 8))
        assertEquals(8, CarAppPick.apiLevel(4, 9, 8))
        assertEquals(4, CarAppPick.apiLevel(4, null, 8))
        assertNull(CarAppPick.apiLevel(9, 9, 8))
        assertEquals(1, CarAppPick.apiLevel(null, null, 8))
    }

    @Test fun verdictHoldsUntilVersionChanges() {
        val v = CarAppVerdicts()
        val t = CarAppTarget("ru.yandex.yandexnavi", "S", 10, null)
        assertNull(v.refusal(t))
        v.refuse(t, CarAppRefusal.HANDSHAKE)
        assertEquals(CarAppRefusal.HANDSHAKE, v.refusal(t))
        assertNull(v.refusal(t.copy(versionCode = 11)))
        v.clear(t.pkg)
        assertNull(v.refusal(t))
    }

    @Test fun distanceUnitsToMeters() {
        assertEquals(50, CarTrip.meters(50.0, CarTrip.UNIT_METERS))
        assertEquals(1200, CarTrip.meters(1.2, CarTrip.UNIT_KILOMETERS_P1))
        assertEquals(1609, CarTrip.meters(1.0, CarTrip.UNIT_MILES))
        assertEquals(30, CarTrip.meters(100.0, CarTrip.UNIT_FEET))
    }

    @Test fun maneuverTypesToCarLifeTurns() {
        assertEquals(AaMeta.T_LEFT, CarTrip.turnFor(Maneuver.TYPE_TURN_NORMAL_LEFT))
        assertEquals(AaMeta.T_RIGHT, CarTrip.turnFor(Maneuver.TYPE_TURN_NORMAL_RIGHT))
        assertEquals(AaMeta.T_RING, CarTrip.turnFor(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW))
        assertEquals(AaMeta.T_DEST, CarTrip.turnFor(Maneuver.TYPE_DESTINATION_LEFT))
        assertEquals(AaMeta.T_FRONT, CarTrip.turnFor(Maneuver.TYPE_STRAIGHT))
        assertEquals(AaMeta.T_FRONT, CarTrip.turnFor(Maneuver.TYPE_UNKNOWN))
    }

    @Test fun tapIsClickDragIsScroll() {
        val t = CarTouch()
        assertNull(t.feed(0, 100f, 100f))
        assertNull(t.feed(2, 105f, 104f))
        assertEquals(CarTouch.Click(106f, 104f), t.feed(1, 106f, 104f))

        assertNull(t.feed(0, 100f, 100f))
        assertEquals(CarTouch.Scroll(-20f, 0f), t.feed(2, 120f, 100f))
        assertEquals(CarTouch.Scroll(-10f, -5f), t.feed(2, 130f, 105f))
        assertNull(t.feed(1, 130f, 105f))

        assertNull(t.feed(2, 10f, 10f))   // MOVE без DOWN
        assertNull(t.feed(0, 1f, 1f)); assertNull(t.feed(3, 1f, 1f)); assertNull(t.feed(1, 1f, 1f))
    }
}
