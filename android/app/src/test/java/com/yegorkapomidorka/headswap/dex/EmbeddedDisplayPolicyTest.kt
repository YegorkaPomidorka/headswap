// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class EmbeddedDisplayPolicyTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe widget request accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun onlyLiveOwnDesktopParentCanHostBoundedChildren() {
        EmbeddedDisplayPolicy.requireParent(DesktopModePolicy.ANDROID_DESKTOP, 24)
        EmbeddedDisplayPolicy.requireCapacity(0)
        EmbeddedDisplayPolicy.requireCapacity(1)
        EmbeddedDisplayPolicy.requireCapacity(2)
        rejects { EmbeddedDisplayPolicy.requireCapacity(3) }
        rejects { EmbeddedDisplayPolicy.requireCapacity(-1) }
        rejects { EmbeddedDisplayPolicy.requireParent(DesktopModePolicy.ANDROID_DESKTOP, 0) }
        rejects { EmbeddedDisplayPolicy.requireParent(DesktopModePolicy.SAMSUNG_DEX, 24) }
        rejects { EmbeddedDisplayPolicy.requireParent(2, 24) } // The former DriveDeck mode no longer exists.
    }

    @Test fun onlyAliceAssistantActivityLaunchesWithoutLauncherCategory() {
        assertTrue(EmbeddedDisplayPolicy.assistLaunch("com.yandex.searchapp", "ru.yandex.searchplugin.AssistantActivityAlias"))
        assertTrue(EmbeddedDisplayPolicy.assistLaunch("ru.yandex.searchplugin", "ru.yandex.searchplugin.AssistantActivityAlias"))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch("com.yandex.searchapp", "ru.yandex.searchplugin.MainActivity"))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch("com.yandex.searchapp", ".AssistantActivityAlias"))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch("com.example.app", "ru.yandex.searchplugin.AssistantActivityAlias"))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch("com.yandex.maps", "ru.yandex.searchplugin.AssistantActivityAlias"))
    }

    @Test fun geminiLaunchesHandsFreeAndAliceAssist() {
        val g = "com.google.android.googlequicksearchbox"
        val hf = "com.google.android.voicesearch.handsfree.HandsFreeActivity"
        assertTrue(EmbeddedDisplayPolicy.assistLaunch(g, hf))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch(g, "com.google.android.googlequicksearchbox.SearchActivity"))
        assertFalse(EmbeddedDisplayPolicy.assistLaunch("com.example.app", hf))
        assertEquals("android.speech.action.VOICE_SEARCH_HANDS_FREE", EmbeddedDisplayPolicy.assistAction(g, hf))
        assertEquals("android.intent.action.ASSIST",
            EmbeddedDisplayPolicy.assistAction("com.yandex.searchapp", "ru.yandex.searchplugin.AssistantActivityAlias"))
    }

    @Test fun validatesWidgetGeometryBeforeAllocation() {
        EmbeddedDisplayPolicy.requireSize(800, 850, 160)
        EmbeddedDisplayPolicy.requireSize(128, 128, 120)
        EmbeddedDisplayPolicy.requireSize(1600, 900, 320)
        EmbeddedDisplayPolicy.requireSize(3840, 2160, 1280)
        EmbeddedDisplayPolicy.requireSize(128, 128, 96)
        EmbeddedDisplayPolicy.requireSize(1884, 1752, 316) // Verified Navigator 2x buffer.
        EmbeddedDisplayPolicy.requireSize(3200, 1800, 640)
        for ((width, height, dpi) in listOf(Triple(0, 850, 160), Triple(127, 850, 160),
            Triple(800, 127, 160), Triple(3841, 850, 160), Triple(800, 2161, 160),
            Triple(800, 850, 95), Triple(800, 850, 1281))) {
            rejects { EmbeddedDisplayPolicy.requireSize(width, height, dpi) }
        }
    }

    @Test fun largerChildDoesNotBroadenParentDensityOrVideoGeometry() {
        EmbeddedDisplayPolicy.requireSize(3200, 1800, 640)
        assertEquals(1600, DexUserServicePolicy.WIDTH)
        assertEquals(900, DexUserServicePolicy.HEIGHT)
        DexUserServicePolicy.requireDensity(640)
        rejects { DexUserServicePolicy.requireDensity(641) }
        rejects { DexUserServicePolicy.validateGeometry(25, 3200, 1800, 3200, 1800, 160,
            DexUserServicePolicy.DISPLAY_TRUSTED, desktopMode = DesktopModePolicy.ANDROID_DESKTOP) }
    }

    @Test fun totalBudgetAllowsOneDoubledFullHdNavigatorAndNormalTileButRejectsTwoMaximalTiles() {
        EmbeddedDisplayPolicy.requirePixelBudget(1920L * 1080, 3840, 2160)
        EmbeddedDisplayPolicy.requirePixelBudget(3840L * 2160, 1920, 1080)
        EmbeddedDisplayPolicy.requirePixelBudget(0, 1884, 1752)
        rejects { EmbeddedDisplayPolicy.requirePixelBudget(3840L * 2160, 3840, 2160) }
        rejects { EmbeddedDisplayPolicy.requirePixelBudget(1920L * 1080 + 1, 3840, 2160) }
        rejects { EmbeddedDisplayPolicy.requirePixelBudget(-1, 128, 128) }
        rejects { EmbeddedDisplayPolicy.requirePixelBudget(Long.MAX_VALUE, 128, 128) }
    }

    @Test fun highResolutionChildAcceptsItsFarCornerWithoutAnotherCoordinateScale() {
        val state = EmbeddedTouchState(1884, 1752)
        assertEquals(listOf(EmbeddedTouchState.Event(0, 1883.5f, 1751.5f)), state.events(0, 1883.5f, 1751.5f))
        assertEquals(listOf(EmbeddedTouchState.Event(1, 1883.5f, 1751.5f)), state.events(1, 1883.5f, 1751.5f))
        rejects { state.events(0, 1884f, 1751f) }
        rejects { state.events(0, 1883f, 1752f) }
    }

    @Test fun childFlagsNeverMirrorPhoneOrRequestSamsungDesktop() {
        val flags = EmbeddedDisplayPolicy.displayFlags(33)
        assertEquals(1355, flags)
        assertTrue(flags and 8 != 0) // OWN_CONTENT_ONLY
        assertTrue(flags and 1024 != 0) // TRUSTED
        assertTrue(flags and 256 != 0) // DESTROY_CONTENT_ON_REMOVAL
        assertEquals(0, flags and (16 or 512 or 131072)) // No mirror, system decorations, Samsung content-mode.
    }

    @Test fun childDisplaysFollowTheParentUnlockedPolicy() {
        for (sdk in listOf(33, 34, 37)) {
            assertEquals(DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, sdk, true),
                EmbeddedDisplayPolicy.displayFlags(sdk, true))
        }
        assertEquals(1355, EmbeddedDisplayPolicy.displayFlags(33, false))
    }

    @Test fun embeddedAndParentFocusPoliciesMatchAcrossAndroidBoundary() {
        assertEquals(1355, EmbeddedDisplayPolicy.displayFlags(33))
        for (sdk in listOf(33, 34, 37)) {
            val child = EmbeddedDisplayPolicy.displayFlags(sdk)
            assertEquals(DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, sdk), child)
            assertEquals(if (sdk >= 34) 1 shl 14 else 0, child and (1 shl 14))
            assertEquals(0, child and (1 shl 16))
        }
    }

    @Test fun explicitLaunchAlwaysTargetsChildAndUserWithoutShellOrHomeResolution() {
        val command = EmbeddedDisplayPolicy.launchCommand(25, 24, 10, "com.example.navigator", ".MainActivity").toList()
        assertEquals(listOf("/system/bin/am", "start", "--user", "10", "--display", "25", "--windowingMode", "1",
            "-f", "0x34000000", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", "com.example.navigator/.MainActivity"), command)
        for (id in listOf(-1, 0, 24)) rejects {
            EmbeddedDisplayPolicy.launchCommand(id, 24, 10, "com.example.navigator", ".MainActivity")
        }
        rejects { EmbeddedDisplayPolicy.launchCommand(25, 0, 10, "com.example.navigator", ".MainActivity") }
        rejects { EmbeddedDisplayPolicy.launchCommand(25, 24, -1, "com.example.navigator", ".MainActivity") }
        for (bad in listOf("com.example; input keyevent 3", "-n", "com.example/nav", "com.example\nother")) {
            rejects { EmbeddedDisplayPolicy.launchCommand(25, 24, 10, bad, ".MainActivity") }
            rejects { EmbeddedDisplayPolicy.launchCommand(25, 24, 10, "com.example.navigator", bad) }
        }
    }

    @Test fun onlyTelegramLaunchesMultiWindow() {
        for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")) {
            assertEquals(true, EmbeddedDisplayPolicy.multiWindowLaunch(pkg))
        }
        for (pkg in listOf("com.google.android.youtube", "ru.yandex.yandexnavi", "org.telegram.messengerx")) {
            assertEquals(false, EmbeddedDisplayPolicy.multiWindowLaunch(pkg))
        }
    }

    @Test fun unspecifiedSplashFollowsPortraitPackage() {
        // Заставка без ориентации, остальные экраны портретные (Звук)
        assertEquals(true, EmbeddedDisplayPolicy.multiWindowLaunch("com.zvooq.openplay", -1, listOf(-1, 1, 1, 1, 7)))
        // Портретных половина или меньше - как раньше, на весь экран
        assertEquals(false, EmbeddedDisplayPolicy.multiWindowLaunch("com.google.android.youtube", -1, listOf(-1, -1, 1, 4)))
        assertEquals(false, EmbeddedDisplayPolicy.multiWindowLaunch("com.example", -1, emptyList()))
        // Явная ландшафтная заставка пакетом не перебивается
        assertEquals(false, EmbeddedDisplayPolicy.multiWindowLaunch("com.example", 0, listOf(1, 1, 1)))
    }

    @Test fun portraitManifestLaunchesMultiWindow() {
        for (orientation in listOf(1, 7, 9, 12)) {
            assertEquals(true, EmbeddedDisplayPolicy.multiWindowLaunch("com.zvooq.openplay", orientation))
        }
        // UNSPECIFIED, LANDSCAPE, USER, SENSOR, SENSOR_LANDSCAPE, FULL_SENSOR, LOCKED
        for (orientation in listOf(-1, 0, 2, 4, 6, 10, 14)) {
            assertEquals(false, EmbeddedDisplayPolicy.multiWindowLaunch("com.google.android.youtube", orientation))
        }
    }

    @Test fun childInputUsesItsOwnPixelBoundsAndPreservesDragAndRelease() {
        val state = EmbeddedTouchState(800, 850)
        assertEquals(listOf(EmbeddedTouchState.Event(0, 100f, 200f)), state.events(0, 100f, 200f))
        assertEquals(listOf(EmbeddedTouchState.Event(2, 799.5f, 849.5f)), state.events(2, 799.5f, 849.5f))
        assertEquals(listOf(EmbeddedTouchState.Event(1, 799.5f, 849.5f)), state.events(1, 799.5f, 849.5f))
        assertNull(state.cancel())
        assertTrue(state.events(2, 20f, 30f).isEmpty())
        assertTrue(state.events(1, 20f, 30f).isEmpty())
    }

    @Test fun duplicateDownAndRetirementCancelOnlyThisTilesGesture() {
        val left = EmbeddedTouchState(800, 850)
        val right = EmbeddedTouchState(600, 500)
        left.events(0, 10f, 20f)
        right.events(0, 30f, 40f)
        assertEquals(listOf(EmbeddedTouchState.Event(3, 10f, 20f), EmbeddedTouchState.Event(0, 50f, 60f)),
            left.events(0, 50f, 60f))
        assertEquals(EmbeddedTouchState.Event(3, 50f, 60f), left.cancel())
        assertNull(left.cancel())
        assertEquals(listOf(EmbeddedTouchState.Event(1, 30f, 40f)), right.events(1, 30f, 40f))
        assertTrue(EmbeddedTouchState(128, 128).events(2, 5f, 5f).isEmpty()) // Resize begins without a pressed pointer.
    }

    @Test fun rejectedInputDoesNotAlterThePressedGesture() {
        val state = EmbeddedTouchState(800, 850)
        state.events(0, 10f, 20f)
        for ((x, y) in listOf(Float.NaN to 0f, Float.POSITIVE_INFINITY to 0f, 0f to Float.NEGATIVE_INFINITY,
            -1f to 0f, 800f to 0f, 0f to 850f)) rejects { state.events(1, x, y) }
        for (action in listOf(-1, 4, 5, 6, 7, 8, 11, 12, 256)) rejects { state.events(action, 10f, 20f) }
        assertEquals(EmbeddedTouchState.Event(3, 10f, 20f), state.cancel())
    }
}
