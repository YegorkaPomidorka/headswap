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

    @Test fun notRoute() {
        assertNull(RouteIntent.geoFor(null))
        assertNull(RouteIntent.geoFor("yandexnavi://show_point_on_map"))
        assertNull(RouteIntent.geoFor("yandexnavi://build_route_on_map?lat_to=abc&lon_to=20"))
        assertNull(RouteIntent.geoFor("https://ya.ru/"))
    }
}
