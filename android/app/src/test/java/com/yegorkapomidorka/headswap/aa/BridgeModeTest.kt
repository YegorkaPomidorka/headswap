package com.yegorkapomidorka.headswap.aa

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class BridgeModeTest {
    private class MemoryPrefs : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; proxy }
                "apply" -> null
                else -> error("Unexpected editor ${method.name}")
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @Test fun defaultIsAndroidAutoAndJunkFallsBackToIt() {
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.DEFAULT)
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.parse(null))
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.parse(""))
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.parse("DESKTOP"))      // ключ, а не имя константы
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.parse("aa"))
        assertEquals(BridgeMode.DESKTOP, BridgeMode.parse("desktop"))
    }

    @Test fun keysAreUniqueAndRoundTrip() {
        assertEquals(BridgeMode.values().size, BridgeMode.values().map { it.key }.toSet().size)
        for (m in BridgeMode.values()) assertEquals(m, BridgeMode.parse(m.key))
    }

    @Test fun desktopIsAndroid13Plus() {
        assertEquals(33, BridgeMode.DESKTOP_MIN_SDK)
        assertFalse(BridgeMode.desktopSupported(29))
        assertFalse(BridgeMode.desktopSupported(32))
        assertTrue(BridgeMode.desktopSupported(33))
        assertTrue(BridgeMode.desktopSupported(37))
    }

    @Test fun effectiveModeBelowAndroid13IsAlwaysAndroidAuto() {
        for (sdk in listOf(0, 29, 31, 32)) {
            assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.effective(BridgeMode.DESKTOP, sdk))
            assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.effective(BridgeMode.ANDROID_AUTO, sdk))
        }
    }

    @Test fun effectiveModeOnAndroid13PlusFollowsTheSetting() {
        for (sdk in listOf(33, 34, 36, 37)) {
            assertEquals(BridgeMode.DESKTOP, BridgeMode.effective(BridgeMode.DESKTOP, sdk))
            assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.effective(BridgeMode.ANDROID_AUTO, sdk))
        }
    }

    @Test fun hostIsReusedOnlyInTheSameMode() {
        for (a in BridgeMode.values()) for (b in BridgeMode.values()) assertEquals(a == b, BridgeMode.hostReusable(a, b))
    }

    @Test fun freshInstallIsAndroidAutoAndTheChoiceSurvivesNewPreferenceInstances() {
        val context = MemoryPrefs()
        assertEquals(BridgeMode.ANDROID_AUTO, AaPrefs(context).bridgeMode)
        AaPrefs(context).bridgeMode = BridgeMode.DESKTOP
        assertEquals(BridgeMode.DESKTOP, AaPrefs(context).bridgeMode)
        assertEquals("desktop", context.values["bridgeMode"])
        AaPrefs(context).bridgeMode = BridgeMode.ANDROID_AUTO
        assertEquals(BridgeMode.ANDROID_AUTO, AaPrefs(context).bridgeMode)
    }

    @Test fun storedDesktopOnOldAndroidStaysStoredButIsAppliedAsAndroidAuto() {
        val context = MemoryPrefs()
        AaPrefs(context).bridgeMode = BridgeMode.DESKTOP
        val stored = AaPrefs(context).bridgeMode
        assertEquals(BridgeMode.DESKTOP, stored)      // после обновления до Android 13 выбор не потерян
        assertEquals(BridgeMode.ANDROID_AUTO, BridgeMode.effective(stored, 31))
        assertEquals(BridgeMode.DESKTOP, BridgeMode.effective(stored, 34))
    }
}
