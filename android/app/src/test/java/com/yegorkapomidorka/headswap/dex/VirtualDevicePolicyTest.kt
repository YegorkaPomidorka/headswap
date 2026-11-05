package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDevicePolicyTest {
    @Test fun onlyAndroid17ByDefault() {
        assertFalse(VirtualDevicePolicy.enabled(36, null))
        assertFalse(VirtualDevicePolicy.enabled(36, ""))
        assertTrue(VirtualDevicePolicy.enabled(37, null))
        assertTrue(VirtualDevicePolicy.enabled(38, " "))
    }

    @Test fun overrideForChecksOnOlderAndroid() {
        assertTrue(VirtualDevicePolicy.enabled(36, "1"))
        assertTrue(VirtualDevicePolicy.enabled(34, "true"))
        assertFalse(VirtualDevicePolicy.enabled(33, "1")) // нет VirtualDevice.createVirtualDisplay(config)
        assertFalse(VirtualDevicePolicy.enabled(37, "0"))
    }

    @Test fun fakeMacIsStableAndPerPackage() {
        val mac = VirtualDevicePolicy.fakeMac("com.yegorkapomidorka.headswap", 0)
        assertEquals(mac, VirtualDevicePolicy.fakeMac("com.yegorkapomidorka.headswap", 0))
        assertTrue(mac, Regex("02:48:53:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}").matches(mac))
        assertFalse(mac == VirtualDevicePolicy.fakeMac("com.yegorkapomidorka.headswap.smoke", 0))
        assertFalse(mac == VirtualDevicePolicy.fakeMac("com.yegorkapomidorka.headswap", 10))
    }

    @Test fun associateCommandPerAndroidVersion() {
        assertEquals("cmd companiondevice associate 0 p 02:48:53:00:00:01",
            VirtualDevicePolicy.associateCommand(0, "p", "02:48:53:00:00:01", 34))
        assertEquals("cmd companiondevice associate 0 p m android.app.role.COMPANION_DEVICE_APP_STREAMING",
            VirtualDevicePolicy.associateCommand(0, "p", "m", 35))
        assertEquals("cmd companiondevice associate 10 p m android.app.role.COMPANION_DEVICE_APP_STREAMING false",
            VirtualDevicePolicy.associateCommand(10, "p", "m", 37))
        assertEquals("cmd companiondevice disassociate 0 p m", VirtualDevicePolicy.disassociateCommand(0, "p", "m"))
    }

    @Test fun associationProfileMustBeStreaming() {
        assertTrue(VirtualDevicePolicy.associationUsable(null, 34))
        assertFalse(VirtualDevicePolicy.associationUsable(null, 37))
        assertTrue(VirtualDevicePolicy.associationUsable(VirtualDevicePolicy.PROFILE_APP_STREAMING, 37))
    }

    @Test fun deviceDisplayDropsOwnGroupAndUnlockFlags() {
        val desktop = DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, 37, alwaysUnlocked = true)
        val inDevice = VirtualDevicePolicy.deviceDisplayFlags(desktop)
        assertEquals(0, inDevice and DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP)
        assertEquals(0, inDevice and DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED)
        assertEquals(desktop and ((1 shl 11) or (1 shl 12)).inv(), inDevice) // остальное как было: TRUSTED, OWN_FOCUS, тач
        assertTrue(inDevice and 1024 != 0)
        val fallback = VirtualDevicePolicy.deviceDisplayFallbackFlags(desktop)
        assertEquals(0, fallback and 3) // без PUBLIC и PRESENTATION
        assertEquals(inDevice and 3.inv(), fallback)
    }

    @Test fun ownGroupIsPositiveId() {
        assertFalse(VirtualDevicePolicy.ownGroup(null))
        assertFalse(VirtualDevicePolicy.ownGroup(0))
        assertFalse(VirtualDevicePolicy.ownGroup(-1))
        assertTrue(VirtualDevicePolicy.ownGroup(9))
    }
}
