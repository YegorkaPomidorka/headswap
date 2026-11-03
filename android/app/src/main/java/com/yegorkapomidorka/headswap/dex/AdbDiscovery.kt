// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov: mDNS discovery of the phone's own adbd.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.yegorkapomidorka.headswap.AppLog

internal enum class AdbService(val type: String) {
    PAIRING("_adb-tls-pairing._tcp"),
    CONNECT("_adb-tls-connect._tcp"),
}

internal data class AdbEndpoint(val host: String, val port: Int)

/** A service name or subnet is not proof that this is our phone: check every numeric address. */
internal fun selectOwnEndpoint(
    addresses: List<String>,
    port: Int,
    isOwnHost: (String) -> Boolean,
): AdbEndpoint? {
    if (port !in 1..65535) return null
    return addresses.asSequence()
        .filter(isOwnHost)
        .sortedBy { if (':' in it) 1 else 0 }
        .firstOrNull()?.let { AdbEndpoint(it, port) }
}

/** Foreground-only mDNS discovery. It only reports ports; it never pairs or opens ADB sockets. */
internal class AdbDiscovery(
    context: Context,
    private val onUpdate: (pairing: AdbEndpoint?, connect: AdbEndpoint?) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val executor = context.mainExecutor
    private val timer = Handler(Looper.getMainLooper())
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val endpoints = linkedMapOf<String, Pair<AdbService, AdbEndpoint>>()
    // No API-34 callback type appears in this class's fields or signatures on Android 11-13.
    private val resolver: Resolver by lazy {
        if (Build.VERSION.SDK_INT >= 34) ModernResolver() else LegacyResolver()
    }
    private var active = false
    private var generation = 0

    // Public entry points are called on main. Older NSD callbacks use an internal handler, so marshal every
    // discovery callback explicitly.
    fun start() {
        close()
        active = true
        val session = generation
        AdbService.entries.forEach { kind ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    executor.execute {
                        if (current(session)) { discoveries.remove(this); failed("start", code) }
                    }
                }
                override fun onStopDiscoveryFailed(type: String, code: Int) {
                    executor.execute { AppLog.i("PHONE discovery stop failed code=$code") }
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    executor.execute { if (current(session)) resolver.track(kind, info, session) }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    executor.execute {
                        if (current(session)) {
                            val key = key(kind, info)
                            resolver.remove(key)
                            if (endpoints.remove(key) != null) publish()
                        }
                    }
                }
            }
            discoveries.add(listener)
            try {
                // Available since API 16. The executor/Network overload was only added in 33.
                manager.discoverServices(kind.type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: RuntimeException) {
                discoveries.remove(listener)
                AppLog.i("PHONE discovery start failed ${e.javaClass.simpleName}")
            }
        }
        publish()
    }

    private interface Resolver {
        fun track(kind: AdbService, info: NsdServiceInfo, session: Int)
        fun remove(key: String)
        fun close()
    }

    /** Kept in its own class so the API 34 callback interface is never verified on older ART. */
    @RequiresApi(34)
    private inner class ModernResolver : Resolver {
        private val resolutions = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        override fun track(kind: AdbService, info: NsdServiceInfo, session: Int) {
            val key = key(kind, info)
            if (key in resolutions || resolutions.size >= MAX_RESOLUTIONS) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                    if (!current(session) || resolutions[key] !== this) return
                    resolutions.remove(key)
                    failed("resolve", code)
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() {
                    if (current(session) && resolutions[key] === this && endpoints.remove(key) != null) publish()
                }
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    if (current(session) && resolutions[key] === this) {
                        resolved(kind, key, updated.hostAddresses.mapNotNull { it.hostAddress }, updated.port)
                    }
                }
            }
            resolutions[key] = callback
            try { manager.registerServiceInfoCallback(info, executor, callback) }
            catch (e: RuntimeException) {
                resolutions.remove(key)
                resolveException(e)
            }
        }
        override fun remove(key: String) {
            resolutions.remove(key)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
        override fun close() {
            val previous = resolutions.values.toList()
            resolutions.clear()
            previous.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
    }

    private data class ResolveRequest(val kind: AdbService, val info: NsdServiceInfo, val session: Int)

    /** Android 11-13 allows one outstanding resolve. There is no supported cancellation API. */
    @Suppress("DEPRECATION")
    private inner class LegacyResolver : Resolver {
        private val queue = AdbResolveQueue<ResolveRequest>(MAX_RESOLUTIONS)
        override fun track(kind: AdbService, info: NsdServiceInfo, session: Int) {
            queue.offer(key(kind, info), ResolveRequest(kind, info, session))
            pump()
        }
        override fun remove(key: String) { queue.remove(key) }
        override fun close() { queue.clear() }

        private fun pump() {
            val ticket = queue.take() ?: return
            val request = ticket.value
            val timeout = Runnable {
                // Keep the slot until its terminal callback: another concurrent resolve would fail on Android 11.
                queue.expire(ticket)
            }
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    executor.execute {
                        timer.removeCallbacks(timeout)
                        if (queue.complete(ticket) != null && current(request.session)) failed("resolve", code)
                        pump()
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    executor.execute {
                        timer.removeCallbacks(timeout)
                        if (queue.complete(ticket) != null && current(request.session)) {
                            resolved(request.kind, ticket.key, listOfNotNull(info.host?.hostAddress), info.port)
                        }
                        pump()
                    }
                }
            }
            timer.postDelayed(timeout, RESOLVE_TIMEOUT_MS)
            try { manager.resolveService(request.info, callback) }
            catch (e: RuntimeException) {
                timer.removeCallbacks(timeout)
                if (queue.complete(ticket) != null && current(request.session)) resolveException(e)
                // Defer the next bounded attempt instead of recursing through queued failures.
                executor.execute { pump() }
            }
        }
    }

    private fun resolved(kind: AdbService, key: String, addresses: List<String>, port: Int) {
        val own = selectOwnEndpoint(addresses, port) { LocalAddressPolicy.accepts(it, LocalAddressPolicy.ownAddresses()) }
        if (own == null) {
            if (endpoints.remove(key) != null) publish()
            return
        }
        if (endpoints[key] == (kind to own)) return
        endpoints.remove(key)
        endpoints[key] = kind to own
        AppLog.i("PHONE discovery local ${kind.name.lowercase()} port=${own.port}")
        publish()
    }
    private fun resolveException(error: RuntimeException) {
        AppLog.i("PHONE discovery resolve failed ${error.javaClass.simpleName}")
    }
    private fun key(kind: AdbService, info: NsdServiceInfo): String {
        val network = if (Build.VERSION.SDK_INT >= 33) networkKey(info) else "legacy"
        return "${kind.name}:$network:${info.serviceName}"
    }
    @RequiresApi(33) private fun networkKey(info: NsdServiceInfo) = info.network?.networkHandle?.toString() ?: "default"
    private fun current(session: Int) = active && generation == session
    private fun failed(stage: String, code: Int) {
        AppLog.i("PHONE discovery $stage failed code=$code")
    }
    private fun publish() {
        if (!active) return
        onUpdate(
            endpoints.values.lastOrNull { it.first == AdbService.PAIRING }?.second,
            endpoints.values.lastOrNull { it.first == AdbService.CONNECT }?.second,
        )
    }

    override fun close() {
        active = false
        generation++
        discoveries.toList().forEach { runCatching { manager.stopServiceDiscovery(it) } }
        discoveries.clear()
        resolver.close()
        endpoints.clear()
    }
    private companion object {
        const val MAX_RESOLUTIONS = 16
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
