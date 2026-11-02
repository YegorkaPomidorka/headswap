// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.embed

import com.yegorkapomidorka.headswap.dex.DesktopModePolicy
import com.yegorkapomidorka.headswap.dex.EmbeddedDisplayPolicy

/** Pure argument bounds; caller identity and live session generation are checked by the service. */
object EmbeddedGatewayPolicy {
    const val MAX_LEASES = 2

    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    fun requireParent(mode: Int, requestedParentDisplayId: Int, ownedParentDisplayId: Int) {
        require(mode == DesktopModePolicy.ANDROID_DESKTOP) { "Embedded apps require HeadSwap's own desktop" }
        require(requestedParentDisplayId > 0 && ownedParentDisplayId > 0 &&
            requestedParentDisplayId == ownedParentDisplayId) { "Parent display is not the owned desktop" }
    }

    fun requireGeometry(width: Int, height: Int, density: Int) {
        EmbeddedDisplayPolicy.requireSize(width, height, density)
    }

    /** Count includes leases being created or retired until their producer is confirmed closed. */
    fun requireCanAllocate(activeLeaseCount: Int) {
        require(activeLeaseCount in 0 until MAX_LEASES) { "No embedded display lease is available" }
    }

    /** Accepts full class names, including classes whose namespace differs from the APK package. */
    fun requireComponent(packageName: String, className: String) {
        require(packagePattern.matches(packageName) && classPattern.matches(className)) {
            "Expected an explicit package and full activity class name"
        }
    }
}
