// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.yegorkapomidorka.headswap.aa.DesktopResolution
import com.yegorkapomidorka.headswap.aa.FpsSetting
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DesktopPrefsTest {
    private class MemoryPrefs : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error("Unexpected editor ${method.name}")
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getInt", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                else -> error("Unexpected preferences ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    @Test fun freshInstallDefaultsAreNativeGeometryAndIndependentDesktopFps() {
        val prefs = DexPrefs(MemoryPrefs())
        assertEquals(DesktopResolution.R1600_900, prefs.desktopResolution)
        assertEquals(160, prefs.desktopDensity)
        assertEquals(FpsSetting.FPS30, prefs.desktopFps)
        assertNotEquals(FpsSetting.DEFAULT, prefs.desktopFps) // Independent from the AA default.
        assertTrue(prefs.usbPlaybackAudio)
        assertEquals(DesktopModePolicy.ANDROID_DESKTOP, prefs.desktopMode)
    }
    @Test fun desktopChoicesSurviveNewPreferenceInstances() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.desktopResolution = DesktopResolution.AUTO
        prefs.desktopDensity = 0
        prefs.desktopFps = FpsSetting.FPS15
        val restored = DexPrefs(context)
        assertEquals(DesktopResolution.AUTO, restored.desktopResolution)
        assertEquals(0, restored.desktopDensity)
        assertEquals(FpsSetting.FPS15, restored.desktopFps)
        restored.desktopDensity = 200
        assertEquals(200, DexPrefs(context).desktopDensity)
    }
    @Test fun customGeometryAndDpiAndSixtyFpsSurviveRestartWithoutChangingDefaults() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.setCustomDesktopSize(1920, 1080)
        prefs.desktopDensity = 96
        prefs.desktopFps = FpsSetting.FPS60
        val restored = DexPrefs(context)
        assertEquals(DesktopResolution.CUSTOM, restored.desktopResolution)
        assertEquals(1920, restored.customDesktopWidth)
        assertEquals(1080, restored.customDesktopHeight)
        assertEquals("1920 × 1080", restored.desktopResolutionLabel)
        assertEquals(96, restored.desktopDensity)
        assertEquals(FpsSetting.FPS60, restored.desktopFps)
        val snapshot = restored.desktopVideoSelection()
        restored.setCustomDesktopSize(1024, 768)
        restored.desktopDensity = 640
        assertEquals(1920, snapshot.resolve().outW)
        assertEquals(96, snapshot.densityOverride)
        assertEquals(640, DexPrefs(context).desktopDensity)
    }
    @Test fun rejectedDimensionsDoNotPartiallySaveAndCorruptStoredCustomFallsBackSafely() {
        val context = MemoryPrefs()
        val prefs = DexPrefs(context)
        prefs.setCustomDesktopSize(1280, 720)
        assertThrows(IllegalArgumentException::class.java) { prefs.setCustomDesktopSize(1921, 1080) }
        assertThrows(IllegalArgumentException::class.java) { prefs.desktopDensity = 641 }
        assertEquals(1280, prefs.customDesktopWidth)
        assertEquals(720, prefs.customDesktopHeight)
        for (bad in listOf("1921x1080", "1920x1080x1", "bad", "999999999999x720", "640x900")) {
            context.values["desktop_custom_size"] = bad
            assertEquals(1600, prefs.customDesktopWidth)
            assertEquals(900, prefs.customDesktopHeight)
        }
    }
    @Test fun usbAudioOptOutIsKept() {
        val context = MemoryPrefs()
        DexPrefs(context).usbPlaybackAudio = false
        assertFalse(DexPrefs(context).usbPlaybackAudio)
    }
    @Test fun unsupportedStoredModeFallsBackToAndroidDesktop() {
        val context = MemoryPrefs()
        for (stored in listOf(0, 2, 7)) {
            context.values["desktop_mode"] = stored
            assertEquals(DesktopModePolicy.ANDROID_DESKTOP, DexPrefs(context).desktopMode)
        }
    }
}
