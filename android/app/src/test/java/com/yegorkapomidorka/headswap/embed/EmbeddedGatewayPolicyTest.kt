// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.embed

import com.yegorkapomidorka.headswap.dex.DesktopModePolicy
import org.junit.Assert.fail
import org.junit.Test

class EmbeddedGatewayPolicyTest {
    @Test fun onlyCurrentPositiveOwnDesktopParentCanHostEmbeddedApps() {
        EmbeddedGatewayPolicy.requireParent(DesktopModePolicy.ANDROID_DESKTOP, 12, 12)
        for ((requested, owned) in listOf(0 to 0, 0 to 12, -1 to 12, 12 to -1, 12 to 0, 12 to 13)) {
            rejected { EmbeddedGatewayPolicy.requireParent(DesktopModePolicy.ANDROID_DESKTOP, requested, owned) }
        }
        for (mode in listOf(DesktopModePolicy.SAMSUNG_DEX, -1, 2, 3)) {
            rejected { EmbeddedGatewayPolicy.requireParent(mode, 12, 12) }
        }
    }

    @Test fun usableTileAndBoundarySizesRemainAccepted() {
        EmbeddedGatewayPolicy.requireGeometry(128, 128, 120)
        EmbeddedGatewayPolicy.requireGeometry(1600, 900, 320)
        EmbeddedGatewayPolicy.requireGeometry(960, 800, 160)
        EmbeddedGatewayPolicy.requireGeometry(640, 800, 160)
        EmbeddedGatewayPolicy.requireGeometry(1884, 1752, 316)
        EmbeddedGatewayPolicy.requireGeometry(3200, 1800, 640)
        EmbeddedGatewayPolicy.requireGeometry(3840, 2160, 1280)
        EmbeddedGatewayPolicy.requireGeometry(128, 128, 96)
    }

    @Test fun eachGeometryAxisRejectsOversizedOrInvalidSurfaceRequests() {
        for (width in listOf(Int.MIN_VALUE, -1, 0, 127, 3841, Int.MAX_VALUE)) {
            rejected { EmbeddedGatewayPolicy.requireGeometry(width, 800, 160) }
        }
        for (height in listOf(Int.MIN_VALUE, -1, 0, 127, 2161, Int.MAX_VALUE)) {
            rejected { EmbeddedGatewayPolicy.requireGeometry(640, height, 160) }
        }
        for (density in listOf(-1, 0, 95, 1281, Int.MAX_VALUE)) {
            rejected { EmbeddedGatewayPolicy.requireGeometry(640, 800, density) }
        }
    }

    @Test fun fourthOrUnaccountedLeaseCannotBeAllocated() {
        EmbeddedGatewayPolicy.requireCanAllocate(0)
        EmbeddedGatewayPolicy.requireCanAllocate(1)
        EmbeddedGatewayPolicy.requireCanAllocate(2)
        for (count in listOf(-1, 3, 4, Int.MAX_VALUE)) {
            rejected { EmbeddedGatewayPolicy.requireCanAllocate(count) }
        }
    }

    @Test fun renamedApkAndNestedActivityClassDoNotNeedMatchingNamespaces() {
        EmbeddedGatewayPolicy.requireComponent("org.example.app", "org.example.launcher.MainActivity")
        EmbeddedGatewayPolicy.requireComponent("org.example.maps", "org.example.ui.Main\$Directions")
    }

    @Test fun componentArgumentsCannotContainOptionsOrImplicitTargets() {
        for (pkg in listOf("", " org.example.maps", "org.example.maps ", "--user", "org.example/maps",
            "org.example;id", "org.example\nother")) {
            rejected { EmbeddedGatewayPolicy.requireComponent(pkg, "org.example.Main") }
        }
        for (cls in listOf("", ".Main", "Main", "org.example/.Main", "org.example.Main --display 0",
            "org.example.Main;id", "org.example.Main\nother")) {
            rejected { EmbeddedGatewayPolicy.requireComponent("org.example.maps", cls) }
        }
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Unsafe embedded display request was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
