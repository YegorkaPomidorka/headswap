// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Process-local bootstrap ownership. Nothing here is restored from preferences or disk. */
internal class DelegatedBootstrapState {
    class Lease internal constructor(internal val stop: () -> Unit) {
        internal var stopping = false
    }
    private var current: Lease? = null

    @Synchronized
    fun begin(stop: () -> Unit): Lease {
        check(current == null) { "Активация уже выполняется" }
        return Lease(stop).also { current = it }
    }

    @Synchronized
    fun isActive(): Boolean = current?.stopping == false

    @Synchronized
    fun end(lease: Lease) {
        if (current === lease) current = null
    }

    fun requestStop(): Boolean {
        val lease = synchronized(this) {
            current?.takeUnless { it.stopping }?.also { it.stopping = true }
        } ?: return false
        lease.stop()
        return true
    }
}
