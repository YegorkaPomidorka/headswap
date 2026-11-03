// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.embed

import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.yegorkapomidorka.headswap.dex.DesktopModePolicy
import com.yegorkapomidorka.headswap.dex.IDexUserService
import com.yegorkapomidorka.headswap.dex.DesktopTouchState
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session authority stays in HeadSwap. Public handles are unrelated to Android display IDs.
 * The parent is HeadSwap's own desktop display; callers live in this process (uid = Process.myUid()).
 */
internal object EmbeddedSessionRegistry {
    private class Parent(val owner: Any, val remote: IDexUserService, val displayId: Int, val calls: ExecutorService)
    private class Lease(val handle: Int, val uid: Int, val parent: Parent, val token: IBinder) {
        lateinit var death: IBinder.DeathRecipient
        @Volatile var linked = false
        @Volatile var packageName: String? = null
        var closing: CompletableFuture<Unit>? = null // Guarded by lock.
    }
    private val lock = Any()
    private var parent: Parent? = null
    private val leases = HashMap<Int, Lease>()
    private val nextHandle = AtomicInteger(1000)
    private val cleanup = Executors.newSingleThreadExecutor { r ->
        Thread(r, "headswap-embedded-cleanup").apply { isDaemon = true }
    }

    fun activate(owner: Any, remote: IDexUserService, displayId: Int, calls: ExecutorService) = synchronized(lock) {
        require(displayId > 0)
        check(parent == null || parent?.owner === owner) { "Предыдущий рабочий стол ещё активен" }
        parent = Parent(owner, remote, displayId, calls)
    }

    /** Reject new operations immediately; retained leases can still acknowledge producer release. */
    fun deactivate(owner: Any) = synchronized(lock) {
        if (parent?.owner === owner) parent = null
    }

    /** Called only after stop ACK or confirmed shell process death. */
    fun producerStopped(owner: Any) {
        val completed = synchronized(lock) {
            if (parent?.owner === owner) parent = null
            leases.values.filter { it.parent.owner === owner }.also { list -> list.forEach { leases.remove(it.handle) } }
        }
        completed.forEach { it.closing?.complete(Unit); unlink(it) }
    }

    fun create(uid: Int, surface: Surface, token: IBinder, requestedParent: Int,
        width: Int, height: Int, density: Int): Int {
        EmbeddedGatewayPolicy.requireGeometry(width, height, density)
        require(surface.isValid && token.isBinderAlive) { "Поверхность встроенного приложения недоступна" }
        val lease = synchronized(lock) {
            val active = checkNotNull(parent) { "Рабочий стол ещё не готов" }
            EmbeddedGatewayPolicy.requireParent(DesktopModePolicy.ANDROID_DESKTOP, requestedParent, active.displayId)
            check(active.remote.asBinder().isBinderAlive) { "Служба рабочего стола завершилась" }
            EmbeddedGatewayPolicy.requireCanAllocate(leases.size)
            require(leases.values.none { it.token == token }) { "Токен поверхности уже используется" }
            Lease(nextHandle.incrementAndGet(), uid, active, token).also { leases[it.handle] = it }
        }
        lease.death = IBinder.DeathRecipient {
            cleanup.execute { runCatching { close(lease) }.onFailure { Log.w("HeadSwapEmbed", "Companion cleanup pending", it) } }
        }
        try {
            token.linkToDeath(lease.death, 0)
            lease.linked = true
            activeCall(lease) { it.createChildDisplay(surface, token, width, height, density) }
            synchronized(lock) {
                check(parent === lease.parent && leases[lease.handle] === lease && token.isBinderAlive) {
                    "Сеанс рабочего стола завершился"
                }
            }
            return lease.handle
        } catch (error: Throwable) {
            // Never claim that a Surface can be freed if its producer might still be alive.
            runCatching { close(lease) }.onFailure { Log.w("HeadSwapEmbed", "Failed creation cleanup pending", it) }
            throw error
        }
    }

    private fun active(uid: Int, handle: Int): Lease = synchronized(lock) {
        val lease = checkNotNull(leases[handle]) { "Встроенное окно уже закрыто" }
        if (lease.uid != uid) throw SecurityException("Чужое встроенное окно")
        check(parent === lease.parent && lease.token.isBinderAlive) { "Сеанс рабочего стола завершился" }
        lease
    }

    fun resize(uid: Int, handle: Int, width: Int, height: Int, density: Int) {
        EmbeddedGatewayPolicy.requireGeometry(width, height, density)
        val lease = active(uid, handle)
        activeCall(lease) { it.resizeChildDisplay(lease.token, width, height, density) }
    }
    fun launch(uid: Int, handle: Int, packageName: String, className: String) {
        EmbeddedGatewayPolicy.requireComponent(packageName, className)
        val lease = active(uid, handle)
        activeCall(lease) { it.launchChildApp(lease.token, packageName, className) }
        lease.packageName = packageName
    }
    fun touch(uid: Int, handle: Int, action: Int, x: Float, y: Float) {
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildTouch(lease.token, action, x, y) }
    }
    fun touchPoints(uid: Int, handle: Int, action: Int, actionIndex: Int, pointerIds: IntArray,
        xs: FloatArray, ys: FloatArray) {
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildTouchPoints(lease.token, action, actionIndex, pointerIds, xs, ys) }
    }
    fun back(uid: Int, handle: Int) {
        val lease = active(uid, handle)
        activeCall(lease) { it.injectChildBack(lease.token) }
    }
    fun producerLifetime(uid: Int, handle: Int): IBinder {
        val lease = active(uid, handle)
        return activeCall(lease) { it.childProducerLifetime(lease.token) }
    }
    fun taskState(uid: Int, handle: Int, packageName: String): Int {
        val lease = active(uid, handle)
        if (lease.packageName == null) return 0 // A newly created owned display has no app task yet.
        if (lease.packageName != packageName) return 0 // Only the app explicitly launched into this lease is tracked.
        return activeCall(lease) { it.childTaskState(lease.token, packageName) }
    }
    /** Пакеты задач этого окна (свежие первыми) для «Недавних»; null - Android не отдал список. */
    fun taskPackages(uid: Int, handle: Int): List<String>? {
        val lease = active(uid, handle)
        return activeCall(lease) { it.childTaskPackages(lease.token)?.toList() }
    }
    fun release(uid: Int, handle: Int) {
        val lease = synchronized(lock) { leases[handle] } ?: return // Already stopped with ACK.
        if (lease.uid != uid) throw SecurityException("Чужое встроенное окно")
        close(lease)
    }
    private fun close(lease: Lease) {
        var leader = false
        val done = synchronized(lock) {
            if (leases[lease.handle] !== lease) { unlink(lease); return }
            lease.closing ?: CompletableFuture<Unit>().also { lease.closing = it; leader = true }
        }
        if (!leader) { done.get(30, TimeUnit.SECONDS); return }
        try {
            if (lease.parent.remote.asBinder().isBinderAlive) {
                val stop: (IDexUserService) -> Unit = { remote ->
                    if (synchronized(lock) { leases[lease.handle] === lease }) remote.closeChildDisplay(lease.token)
                }
                try {
                    try { call(lease.parent, stop) }
                    catch (_: RejectedExecutionException) { stop(lease.parent.remote) }
                } catch (error: Exception) { if (lease.parent.remote.asBinder().isBinderAlive) throw error }
            }
            synchronized(lock) { if (leases[lease.handle] === lease) leases.remove(lease.handle) }
            unlink(lease)
            done.complete(Unit)
        } catch (error: Throwable) {
            synchronized(lock) { if (lease.closing === done) lease.closing = null }
            done.completeExceptionally(error)
            throw error
        }
    }

    private fun <T> activeCall(lease: Lease, action: (IDexUserService) -> T): T = call(lease.parent) { remote ->
        synchronized(lock) {
            check(parent === lease.parent && leases[lease.handle] === lease && lease.closing == null &&
                lease.token.isBinderAlive) { "Встроенное окно уже закрыто" }
        }
        action(remote)
    }
    private fun unlink(lease: Lease) {
        if (lease.linked) {
            runCatching { lease.token.unlinkToDeath(lease.death, 0) }
            lease.linked = false
        }
    }

    /** Child app starts cannot overlap parent input RPCs and trip its shorter input timeout. */
    private fun <T> call(parent: Parent, action: (IDexUserService) -> T): T {
        val result = parent.calls.submit<T> { action(parent.remote) }
        try { return result.get(30, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
        // Do not cancel a running Binder call: its producer must still acknowledge Surface release.
    }
}
