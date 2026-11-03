// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopModePolicyTest {
    @Test fun genericDisplayDoesNotRequestSamsungDesktopOrAutoMirroring() {
        val flags = DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, 33)
        assertEquals(1355, flags)
        assertEquals(0, flags and (131072 or 16 or 512))
        assertTrue(flags and 8 != 0) // Blank when there is no own content, never mirror the phone.
        assertTrue(flags and 1024 != 0) // Trusted display still requires the shell helper.
        assertTrue(flags and 256 != 0) // Destroy display-owned tasks on close.
    }

    @Test fun alwaysUnlockedAddsOwnDisplayGroupTogetherAndOnlyWhenAllowed() {
        val mode = DesktopModePolicy.ANDROID_DESKTOP
        for (sdk in listOf(33, 34, 37)) {
            val plain = DesktopModePolicy.displayFlags(mode, sdk)
            val unlocked = DesktopModePolicy.displayFlags(mode, sdk, alwaysUnlocked = true)
            // без права флагов нет: иначе DisplayManagerService бросит SecurityException и рабочий стол не стартует
            assertEquals(0, plain and ((1 shl 11) or (1 shl 12)))
            // ALWAYS_UNLOCKED без OWN_DISPLAY_GROUP система молча отбрасывает, поэтому только парой
            assertEquals((1 shl 11) or (1 shl 12), unlocked xor plain)
            assertTrue(unlocked and 1024 != 0) // TRUSTED остаётся: OWN_DISPLAY_GROUP требует ADD_TRUSTED_DISPLAY
            assertEquals(0, unlocked and (1 shl 16)) // STEAL_TOP_FOCUS_DISABLED не трогаем (клавиатура)
        }
        assertEquals(1355 or 2048 or 4096, DesktopModePolicy.displayFlags(mode, 33, alwaysUnlocked = true))
    }

    @Test fun wakeLockOnlyForDisplayInOwnGroup() {
        // Display.FLAG_*: TRUSTED 128, OWN_DISPLAY_GROUP 256, ALWAYS_UNLOCKED 512 (не те же биты, что VIRTUAL_DISPLAY_FLAG_*)
        assertFalse(DisplayWakeLocks.shouldHold(128 or 1 or 8))
        assertTrue(DisplayWakeLocks.shouldHold(128 or 256 or 512))
        assertTrue(DisplayWakeLocks.describe(128 or 256 or 512).endsWith("ownGroup=true alwaysUnlocked=true"))
        assertTrue(DisplayWakeLocks.describe(128).endsWith("ownGroup=false alwaysUnlocked=false"))
    }

    @Test fun samsungDisplayKeepsItsVerifiedFlagSet() {
        for (sdk in listOf(33, 34, 37)) {
            assertEquals(132419, DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, sdk))
        }
    }

    @Test fun modernGenericDisplaysKeepFocusWithoutDisablingImeOrChangingTrustScope() {
        val mode = DesktopModePolicy.ANDROID_DESKTOP
        for (sdk in listOf(34, 35, 36, 37)) {
            val flags = DesktopModePolicy.displayFlags(mode, sdk)
            val legacy = DesktopModePolicy.displayFlags(mode, 33)
            assertEquals(1 shl 14, flags xor legacy) // Only OWN_FOCUS changes.
            assertEquals(0, flags and (1 shl 16)) // STEAL_TOP_FOCUS_DISABLED would disable IME.
            assertTrue(flags and 1024 != 0)
            assertEquals(0, flags and (16 or 512 or 4096 or 131072))
        }
    }

    @Test fun homeActivityIsInOurNamespaceAndNotAHardcodedApplicationId() {
        assertTrue(DesktopModePolicy.ANDROID_HOME_CLASS.startsWith("com.yegorkapomidorka.headswap."))
    }

    @Test fun dropsDriveDeckMode() {
        assertThrows(IllegalArgumentException::class.java) { DesktopModePolicy.requireMode(2) }
    }

    @Test fun invalidModeCannotFallBackToSamsungOrPhoneHome() {
        for (mode in listOf(-1, 2, 3, Int.MAX_VALUE)) {
            for (sdk in listOf(33, 34, 37)) {
                assertThrows(IllegalArgumentException::class.java) { DesktopModePolicy.displayFlags(mode, sdk) }
            }
        }
    }

    @Test fun onlyGenericDesktopConsumesHomeAndRecentsBeforeInputInjection() {
        val mode = DesktopModePolicy.ANDROID_DESKTOP
        assertTrue(DesktopModePolicy.consumesNavigationKey(mode, 3))
        assertTrue(DesktopModePolicy.consumesNavigationKey(mode, 187))
        assertFalse(DesktopModePolicy.consumesNavigationKey(mode, 4)) // Back stays on the current app.
        assertFalse(DesktopModePolicy.consumesNavigationKey(DesktopModePolicy.SAMSUNG_DEX, 3))
        assertFalse(DesktopModePolicy.consumesNavigationKey(DesktopModePolicy.SAMSUNG_DEX, 187))
    }

    @Test fun genericNativeGeometryDoesNotRequireSamsungCapability() {
        DexUserServicePolicy.validateGeometry(586, 1600, 900, 1600, 900, 160, 128,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsUntrustedSurface() {
        DexUserServicePolicy.validateGeometry(586, 1600, 900, 1600, 900, 160, 8,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsPrimaryDisplay() {
        DexUserServicePolicy.validateGeometry(0, 1600, 900, 1600, 900, 160, 128,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }

    @Test(expected = IllegalStateException::class)
    fun genericDisplayStillRejectsHiddenScaling() {
        DexUserServicePolicy.validateGeometry(586, 1600, 900, 1920, 1080, 160, 128,
            desktopMode = DesktopModePolicy.ANDROID_DESKTOP)
    }
}
