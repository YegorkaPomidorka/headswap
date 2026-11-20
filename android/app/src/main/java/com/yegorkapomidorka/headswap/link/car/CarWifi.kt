package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/**
 * Wi-Fi точка доступа машины (ГУ раздаёт её сам): по ней телефон входит в сеть машины через WifiNetworkSpecifier, не теряя
 * интернет основной сети. Отдельный файл prefs `car_wifi`, исключён из бэкапа (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * SSID и пароль не пишем никуда, кроме этого файла и экрана настроек: ни в WLog, ни в AppLog, ни в отчёт.
 */
class CarWifi(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_wifi", Context.MODE_PRIVATE)

    val ssid: String? get() = sp.getString("ssid", null)

    /** Пароль WPA2/WPA3; пустая строка (или null) - открытая сеть. */
    val pass: String get() = sp.getString("pass", null).orEmpty()

    val isSet: Boolean get() = !ssid.isNullOrEmpty()

    /** 6/6 уже поднимались в этой сети: ГУ подключается через свой Wi-Fi, сеть машины просим без ожидания. Сбрасывается сменой SSID. */
    val proven: Boolean get() = sp.getBoolean("proven", false)

    fun markProven() {
        sp.edit().putBoolean("proven", true).apply()
    }

    /**
     * Защита, с которой вход в сеть машины уже удался ("open"/"wpa2"/"wpa3"), или null. Android считает WPA2 и WPA3 с одним
     * SSID разными сетями и даёт им разные случайные MAC: ГУ видит новое устройство (Павел 2026-10-19: 4 дубля в Known devices).
     */
    val sec: String? get() = sp.getString("sec", null)

    fun rememberSec(label: String) {
        if (label != sec) sp.edit().putString("sec", label).apply()
    }

    fun save(ssid: String, pass: String) {
        val ed = sp.edit().putString("ssid", ssid).putString("pass", pass)
        if (ssid != this.ssid) {
            ed.remove("proven")
            ed.remove("lanAddr").remove("lanPrefix").remove("lanGw")
        }
        if (ssid != this.ssid || pass != this.pass) ed.remove("sec")
        ed.apply()
    }

    /** Адрес телефона в сети ГУ после удачного входа, например `10.173.189.58` и префикс 24. Шлюз может быть null. */
    val lanAddr: String? get() = sp.getString("lanAddr", null)

    val lanPrefix: Int get() = sp.getInt("lanPrefix", -1)

    val lanGw: String? get() = sp.getString("lanGw", null)

    fun rememberLan(addr: String, prefix: Int, gw: String?) {
        if (prefix !in 16..30 || addr.isEmpty()) return
        val ed = sp.edit().putString("lanAddr", addr).putInt("lanPrefix", prefix)
        if (!gw.isNullOrEmpty()) ed.putString("lanGw", gw)
        ed.apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
