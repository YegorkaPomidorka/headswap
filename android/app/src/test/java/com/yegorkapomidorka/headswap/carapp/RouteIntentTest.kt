package com.yegorkapomidorka.headswap.carapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteIntentTest {
    @Test fun yandexNavi() = assertEquals("geo:54.70693,20.50532",
        RouteIntent.geoFor("yandexnavi://build_route_on_map?lat_to=54.70693&lon_to=20.50532"))

    @Test fun yandexNaviWithExtraParams() = assertEquals("geo:54.7,20.5",
        RouteIntent.geoFor("yandexnavi://build_route_on_map?lat_from=1&lon_from=2&lat_to=54.7&lon_to=20.5&client=007"))

    @Test fun yandexMapsRtext() = assertEquals("geo:54.7,20.5",
        RouteIntent.geoFor("yandexmaps://maps.yandex.ru/?rtext=54.6,20.4~54.7,20.5&rtt=auto"))

    @Test fun geoPassesThrough() = assertEquals("geo:54.7,20.5", RouteIntent.geoFor("geo:54.7,20.5"))

    @Test fun googleNavigation() = assertEquals("geo:54.7,20.5", RouteIntent.geoFor("google.navigation:q=54.7,20.5"))

    /** Ссылка Gemini со стенда 07.11 («Начни навигацию до Балтийска»): назначение - последняя пара !1d(долгота)!2d(широта). */
    @Test fun googleMapsDirFromGemini() = assertEquals("geo:54.654734,19.911756999999998", RouteIntent.geoFor(
        "https://www.google.com/maps/dir//%D0%91%D0%B0%D0%BB%D1%82%D0%B8%D0%B9%D1%81%D0%BA,+%D0%A0%D0%BE%D1%81%D1%81%D0%B8%D1%8F" +
            "/data=!4m9!4m8!1m0!1m5!1m1!19sChIJQXj6a3At40YRI1kpp-AmFsE!2m2!1d19.911756999999998!2d54.654734!3e0!11m1!6b1" +
            "?entry=gemini&utm_source=gemini"))

    @Test fun googleMapsWithoutPointOrOtherHost() {
        assertNull(RouteIntent.geoFor("https://www.google.com/maps/dir//Балтийск"))
        assertNull(RouteIntent.geoFor("https://www.google.com/maps/place/x/data=!1d19.9!2d54.6"))
        assertNull(RouteIntent.geoFor("https://evil.example/maps/dir//x/data=!1d19.9!2d54.6"))
    }

    @Test fun notRoute() {
        assertNull(RouteIntent.geoFor(null))
        assertNull(RouteIntent.geoFor("yandexnavi://show_point_on_map"))
        assertNull(RouteIntent.geoFor("yandexnavi://build_route_on_map?lat_to=abc&lon_to=20"))
        assertNull(RouteIntent.geoFor("https://ya.ru/"))
    }
}
