package com.yegorkapomidorka.headswap.carapp

/**
 * Маршрут, который ассистент отдал приложению диплинком, - в geo:-uri для CarContext.ACTION_NAVIGATE: так хост AA
 * передаёт навигатору «поехали туда» от Ассистента (ADR 0026). Без android.net.Uri - для юнит-тестов.
 */
object RouteIntent {
    const val ACTION_NAVIGATE = "androidx.car.app.action.NAVIGATE"

    /** geo:lat,lon из диплинка маршрута; null - не маршрут или нет координат. */
    fun geoFor(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val scheme = uri.substringBefore(':').lowercase()
        val q = query(uri)
        val point = when (scheme) {
            // Яндекс Навигатор: yandexnavi://build_route_on_map?lat_to=..&lon_to=..
            "yandexnavi" -> pair(q["lat_to"], q["lon_to"])
            // Яндекс Карты: rtext=from~to, последняя точка "lat,lon"; Google Карты от Gemini - googleMapsDir
            "yandexmaps", "https", "http" -> q["rtext"]?.substringAfterLast('~')?.let(::latLon) ?: googleMapsDir(uri)
            "geo" -> latLon(uri.substringAfter(':').substringBefore('?')) ?: q["q"]?.let(::latLon)
            "google.navigation" -> (q["q"] ?: uri.substringAfter(':').removePrefix("q=")).let(::latLon)
            else -> null
        }
        return point?.let(::geo)
    }

    private fun geo(p: Pair<Double, Double>) = "geo:${p.first},${p.second}"

    private val DIR_POINT = Regex("!1d(-?[0-9.]+)!2d(-?[0-9.]+)")

    /**
     * Gemini открывает Google Карты ссылкой google.com/maps/dir//<куда>/data=...!1d<долгота>!2d<широта>... (стенд 07.11):
     * последняя пара !1d!2d - точка назначения. Других путей и ссылок без координат не берём.
     */
    private fun googleMapsDir(uri: String): Pair<Double, Double>? {
        val path = uri.substringAfter("://").substringBefore('?')
        val host = path.substringBefore('/').lowercase()
        if (!(host.endsWith("google.com") || host.startsWith("maps.google.")) || !path.contains("/maps/dir/")) return null
        val m = DIR_POINT.findAll(path).lastOrNull() ?: return null
        return pair(m.groupValues[2], m.groupValues[1])
    }

    private fun pair(lat: String?, lon: String?): Pair<Double, Double>? {
        val a = lat?.toDoubleOrNull() ?: return null
        val b = lon?.toDoubleOrNull() ?: return null
        return (a to b).takeIf { it.first in -90.0..90.0 && it.second in -180.0..180.0 }
    }

    private fun latLon(s: String): Pair<Double, Double>? {
        val parts = decode(s).split(',')
        return if (parts.size < 2) null else pair(parts[0].trim(), parts[1].trim())
    }

    private fun query(uri: String): Map<String, String> {
        val qs = uri.substringAfter('?', "").substringBefore('#')
        if (qs.isEmpty()) return emptyMap()
        return qs.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            if (k.isEmpty()) null else k to decode(kv.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
