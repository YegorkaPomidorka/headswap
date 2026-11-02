// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DelegatedInputRegistryTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Accepted a stale or unowned display") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun parentRegistrationRequiresExactGenerationTokenAndPositiveDisplay() {
        val registry = DelegatedInputRegistry()
        val token = Any()
        registry.begin(1, token)
        rejects { registry.registerParent(0, token, 1600, 900) }
        rejects { registry.registerParent(12, Any(), 1600, 900) }
        registry.registerParent(12, token, 1600, 900)
        assertEquals(1600, registry.requireDisplay(12, token).width)
    }

    @Test fun focusProofContainsOnlyRegisteredDisplaysOfTheLiveGeneration() {
        val registry = DelegatedInputRegistry()
        val parent = Any()
        val child = Any()
        registry.begin(1, parent)
        assertTrue(registry.registeredDisplayIds().isEmpty())
        registry.registerParent(85, parent, 1600, 900)
        registry.registerChild(85, 87, child, 600, 850)
        val published = registry.registeredDisplayIds()
        assertEquals(setOf(85, 87), published)
        registry.unregister(87, child)
        assertEquals(setOf(85), registry.registeredDisplayIds())
        assertEquals(setOf(85, 87), published)
        registry.deactivate()
        assertTrue(registry.registeredDisplayIds().isEmpty())
    }

    @Test fun childrenRequireCurrentParentAndIndependentTokensWithBoundedCapacity() {
        val registry = DelegatedInputRegistry()
        val parent = Any()
        val child = Any()
        registry.begin(1, parent)
        registry.registerParent(12, parent, 1600, 900)
        rejects { registry.registerChild(13, 14, child, 800, 900) }
        rejects { registry.registerChild(12, 14, parent, 800, 900) }
        registry.registerChild(12, 14, child, 800, 900)
        rejects { registry.registerChild(12, 15, child, 800, 900) }
        registry.registerChild(12, 15, Any(), 800, 900)
        rejects { registry.registerChild(12, 16, Any(), 800, 900) }
        rejects { registry.registerChild(14, 16, Any(), 800, 900) }
    }

    @Test fun closingParentRevokesAllChildInputAndResizeRequiresOwner() {
        val registry = DelegatedInputRegistry()
        val parent = Any()
        val child = Any()
        registry.begin(1, parent)
        registry.registerParent(12, parent, 1600, 900)
        registry.registerChild(12, 14, child, 800, 900)
        rejects { registry.resize(14, Any(), 400, 900) }
        registry.resize(14, child, 400, 900)
        assertEquals(400, registry.requireDisplay(14, child).width)
        registry.unregister(12, parent)
        rejects { registry.requireDisplay(14, child) }
    }

    @Test fun reusedDisplayIdCannotAcceptPreviousGenerationToken() {
        val registry = DelegatedInputRegistry()
        val old = Any()
        val next = Any()
        registry.begin(1, old)
        registry.registerParent(12, old, 1600, 900)
        registry.deactivate()
        rejects { registry.requireDisplay(12, old) }
        registry.begin(2, next)
        rejects { registry.registerParent(12, old, 1600, 900) }
        registry.registerParent(12, next, 1600, 900)
        rejects { registry.requireDisplay(12, old) }
        rejects { registry.unregister(12, old) }
        assertEquals(next, registry.requireDisplay(12, next).token)
    }

    @Test fun doubledGeometryIsChildOnlyAndRejectedParentResizeDoesNotChangeInputBounds() {
        val registry = DelegatedInputRegistry()
        val parent = Any()
        val child = Any()
        registry.begin(1, parent)
        rejects { registry.registerParent(12, parent, 1884, 1752) }
        registry.registerParent(12, parent, 1600, 900)
        registry.registerChild(12, 14, child, 1884, 1752)
        assertEquals(1884, registry.requireDisplay(14, child).width)
        assertEquals(1752, registry.requireDisplay(14, child).height)
        rejects { registry.resize(12, parent, 1884, 1752) }
        assertEquals(1600, registry.requireDisplay(12, parent).width)
        assertEquals(900, registry.requireDisplay(12, parent).height)
        rejects { registry.resize(14, Any(), 3200, 1800) }
        registry.resize(14, child, 3200, 1800)
        assertEquals(3200, registry.requireDisplay(14, child).width)
        rejects { registry.resize(14, child, 3841, 1800) }
        rejects { registry.resize(14, child, 3200, 2161) }
        assertEquals(1800, registry.requireDisplay(14, child).height)
    }

    @Test fun childPixelBudgetRejectsAllocationAndResizeBeforeChangingRegisteredBounds() {
        val registry = DelegatedInputRegistry()
        val parent = Any()
        val map = Any()
        val music = Any()
        registry.begin(1, parent)
        registry.registerParent(12, parent, 1920, 1080)
        registry.registerChild(12, 14, map, 3840, 2160)
        rejects { registry.registerChild(12, 15, music, 3840, 2160) }
        rejects { registry.requireDisplay(15, music) }
        registry.registerChild(12, 15, music, 1920, 1080)
        rejects { registry.resize(15, music, 1920, 1081) }
        assertEquals(1080, registry.requireDisplay(15, music).height)
        // Shrinking the first tile frees budget; resizing must exclude its previous dimensions.
        registry.resize(14, map, 1920, 1080)
        registry.resize(15, music, 3840, 2160)
        assertEquals(3840, registry.requireDisplay(15, music).width)
        registry.unregister(15, music)
        registry.resize(14, map, 3840, 2160)
        assertEquals(3840, registry.requireDisplay(14, map).width)
    }
}
