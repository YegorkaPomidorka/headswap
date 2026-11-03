// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Keeps a confirmed desktop link eligible through the service's short USB reconnect grace. */
internal class DesktopMusicUsbLink(private val graceMs: Long) {
    var serviceOwner: Any? = null
        private set
    var owner: Any? = null
        private set
    var sawReady = false
        private set
    var physicallyDetached = false
        private set
    private var readyLinkClosedAt: Long? = null

    fun attach(service: Any, link: Any, now: Long) {
        if (serviceOwner !== service) clear()
        if (owner === link) return
        owner?.let { close(it, now) }
        serviceOwner = service
        owner = link
        sawReady = false
        physicallyDetached = false
    }

    fun markReady() { if (owner != null && !physicallyDetached) sawReady = true }

    fun close(link: Any, now: Long): Boolean {
        if (owner !== link) return false
        if (sawReady && !physicallyDetached) readyLinkClosedAt = now
        owner = null
        sawReady = false
        return true
    }

    fun consumePhysicalDetach(service: Any, now: Long): Boolean {
        if (serviceOwner !== service || physicallyDetached) return false
        val closedAt = readyLinkClosedAt
        val eligible = sawReady || (closedAt != null && now >= closedAt && now - closedAt <= graceMs)
        physicallyDetached = true
        sawReady = false
        readyLinkClosedAt = null
        return eligible
    }

    fun clear() {
        serviceOwner = null
        owner = null
        sawReady = false
        physicallyDetached = false
        readyLinkClosedAt = null
    }
}
