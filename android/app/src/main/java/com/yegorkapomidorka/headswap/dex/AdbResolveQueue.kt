// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Main-thread serial queue for the uncancellable API30 NSD resolver. */
internal class AdbResolveQueue<T>(private val capacity: Int) {
    init { require(capacity > 0) }
    class Ticket<T>(val key: String, val value: T) {
        internal var valid = true
        internal var expired = false
    }
    private val tracked = mutableMapOf<String, Ticket<T>>()
    private val pending = ArrayDeque<Ticket<T>>()
    private var running: Ticket<T>? = null

    fun offer(key: String, value: T): Boolean {
        if (key in tracked || tracked.size >= capacity) return false
        val ticket = Ticket(key, value)
        tracked[key] = ticket
        pending.addLast(ticket)
        return true
    }
    fun take(): Ticket<T>? {
        if (running != null) return null
        return pending.removeFirstOrNull()?.also { running = it }
    }
    fun remove(key: String) {
        tracked.remove(key)?.let { it.valid = false; pending.remove(it) }
    }
    fun clear() {
        tracked.values.forEach { it.valid = false }
        tracked.clear()
        pending.clear()
        // Running OS operation remains in flight; new generations must wait for its callback.
    }
    fun expire(ticket: Ticket<T>): Boolean {
        if (running !== ticket || !ticket.valid || ticket.expired) return false
        ticket.expired = true
        return true
    }
    fun complete(ticket: Ticket<T>): Ticket<T>? {
        if (running !== ticket) return null
        running = null
        if (tracked[ticket.key] === ticket) tracked.remove(ticket.key)
        return ticket.takeIf { it.valid && !it.expired }
    }
}
