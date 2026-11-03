// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.embed.EmbeddedSessionRegistry
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaLifecycle
import com.yegorkapomidorka.headswap.aa.AaPeer
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.DesktopSurfaceListener
import com.yegorkapomidorka.headswap.aa.DesktopNavigation
import com.yegorkapomidorka.headswap.aa.TerminalSource
import com.yegorkapomidorka.headswap.aa.SurfaceVideoInput
import com.yegorkapomidorka.headswap.aa.TouchPoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.util.UUID

/** Owns the privileged producer; CarLife owns its consumer Surface and single H.264 encoder. */
class DelegatedDexConnector(context: Context, private val core: AaBridgeCore,
    private val onFailed: (DesktopFailure) -> Unit = {}) :
    AaLifecycle, AaPeer, DesktopSurfaceListener, DesktopNavigation, TerminalSource {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val failureSignalled = AtomicBoolean()
    override val isTerminated: Boolean get() = stopping.get() || failureSignalled.get()
    private val stateLock = Any()
    private val connected = CountDownLatch(1)
    private val serviceDied = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val inputGateway = DelegatedInputGateway(this.context)
    private val bindAction = DelegatedDesktopService.BIND_ACTION_PREFIX + UUID.randomUUID()
    // Binder transactions are not cancelled by Future.cancel(). Keep start/stop ordered
    // even when the caller's deadline expires and the remote start finishes late.
    private val binderCalls = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-binder").apply { isDaemon = true } }
    private val input = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(128),
        { r -> Thread(r, "dex-mouse").apply { isDaemon = true } })
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dex-watch").apply { isDaemon = true } }
    @Volatile private var service: IDexUserService? = null
    @Volatile private var serviceBinder: IBinder? = null
    private var bindRequested = false // Read/written only on the main looper.
    @Volatile private var generation = 0L
    @Volatile private var retiringGeneration = 0L
    @Volatile private var displayId = -1
    @Volatile private var desktopMode = DesktopModePolicy.ANDROID_DESKTOP
    @Volatile private var firstFrame = false
    @Volatile private var established = false
    @Volatile private var failure: String? = null
    @Volatile private var generationStartedAt = 0L
    private val death = IBinder.DeathRecipient {
        EmbeddedSessionRegistry.producerStopped(this)
        serviceDied.countDown()
        val retired = retiringGeneration
        if (retired != 0L) core.desktopSurfaceProducerClosed(retired)
        fail(helperUnavailableMessage(), DesktopFailure.HELPER_UNAVAILABLE)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                serviceBinder = binder
                binder.linkToDeath(death, 0)
                service = IDexUserService.Stub.asInterface(binder)
                // A connection queued before a stop can arrive after the session ended.
                if (stopping.get()) unbind()
            } catch (e: Exception) { fail("Не удалось открыть службу рабочего стола: ${e.message}") }
            finally { connected.countDown() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // A disconnect callback is not proof that the producer process died.
            fail(helperUnavailableMessage(), DesktopFailure.HELPER_UNAVAILABLE)
        }
        override fun onBindingDied(name: ComponentName) {
            connected.countDown()
            fail(helperUnavailableMessage(), DesktopFailure.HELPER_UNAVAILABLE)
        }
        override fun onNullBinding(name: ComponentName) { connected.countDown(); fail("Служба рабочего стола недоступна. Активируйте доступ с компьютера") }
    }

    override fun start() {
        synchronized(stateLock) {
            if (!started.compareAndSet(false, true) || stopping.get()) return
            try {
                inputGateway.preflight()
                DexState.error = null
                DexState.running = false
                DexState.displayId = -1
                core.onAaConnecting()
                core.peer = this
                core.enableDesktopSurface(this)
                core.desktopMedia = DesktopMediaControl(context)
                status("Запускаю рабочий стол…")
            } catch (e: Exception) { fail(e.message ?: "Не удалось подготовить рабочий стол"); return }
        }
        main.post {
            if (!stopping.get()) try {
                check(DelegatedDesktopAccess.isReady(context)) { "Активируйте доступ с компьютера" }
                val intent = Intent(context, DelegatedDesktopService::class.java).setAction(bindAction)
                    .putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, inputGateway) })
                bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bindRequested) { "Android не запустил службу рабочего стола" }
            } catch (e: Exception) { connected.countDown(); fail(e.message ?: "Доступ с компьютера недоступен") }
        }
        try { watchdog.scheduleWithFixedDelay({
            if (!stopping.get()) try {
                check(DelegatedDesktopAccess.isReady(context)) { "Доступ завершён. Подключите телефон к компьютеру для повторной активации" }
                core.tick()
                core.desktopVideoError?.let { fail(it) }
                if (generationStartedAt != 0L && !firstFrame &&
                    System.nanoTime() - generationStartedAt > TimeUnit.SECONDS.toNanos(30)) {
                    fail("Рабочий стол не передал первый кадр за 30 секунд")
                }
            } catch (e: Exception) { fail(e.message ?: "Ошибка рабочего стола") }
        }, 500, 500, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) {
            // stop() raced with scheduling; its cleanup already owns the lifecycle.
        }
    }

    override fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput) {
        if (stopping.get()) return
        this.generation = generation
        firstFrame = false
        generationStartedAt = System.nanoTime()
        try {
            check(connected.await(12, TimeUnit.SECONDS)) { "Служба рабочего стола не ответила за 12 секунд" }
            check(!stopping.get()) { "Подключение остановлено" }
            val remote = checkNotNull(service) { "Служба рабочего стола недоступна" }
            val prefs = DexPrefs(context)
            val density = input.density
            desktopMode = prefs.desktopMode
            check(desktopMode != DesktopModePolicy.SAMSUNG_DEX) { "Для доступа с компьютера нужен режим Android Desktop" }
            check(DelegatedDesktopAccess.isReady(context)) { "Активируйте доступ с компьютера" }
            val lifetime = Binder()
            synchronized(stateLock) {
                check(!stopping.get()) { "Подключение остановлено" }
                inputGateway.beginGeneration(generation, lifetime)
            }
            val id = bounded(25) {
                check(remote.protocolVersion() == 5) { "Версия службы рабочего стола не совпадает с приложением" }
                remote.startDesktopSized(input.surface, lifetime, input.width, input.height, density, desktopMode)
            }
            check(id > 0) { "Не удалось создать отдельный экран" }
            displayId = id
            if (stopping.get() || !core.desktopSurfaceReady(generation)) {
                onSurfaceClosing(generation)
                return
            }
            synchronized(stateLock) {
                check(!stopping.get() && retiringGeneration != generation) { "Сеанс завершён" }
                DexState.displayId = id
                EmbeddedSessionRegistry.activate(this, remote, id, this.input)
            }
            status("Рабочий стол ${input.width}×${input.height} готов, ожидаю видео…")
            AppLog.i("DESKTOP mode=$desktopMode delegated display=$id generation=$generation native=${input.width}x${input.height} density=$density preview=false singleEncode=true", false)
        } catch (e: Exception) {
            if (!stopping.get()) {
                AppLog.e("DESKTOP delegated: screen creation failed", e)
                val reason = e.cause?.message ?: e.message ?: "Ошибка создания экрана"
                core.desktopSurfaceFailed(generation, reason)
                fail(reason, failureKind(e))
            }
        }
    }

    override fun onSurfaceClosing(generation: Long) {
        if (this.generation != generation) return
        // Invalidate queued input before retiring the corresponding display.
        synchronized(stateLock) {
            EmbeddedSessionRegistry.deactivate(this)
            inputGateway.deactivate()
            retiringGeneration = generation
        }
        while (true) {
            val queued = input.queue.poll() ?: break
            (queued as? Future<*>)?.cancel(false)
        }
        firstFrame = false
        generationStartedAt = 0
        DexState.running = false
        DexState.displayId = -1
        val remote = service
        val binder = serviceBinder
        if (remote != null && binder?.isBinderAlive == true && serviceDied.count != 0L) {
            try { bounded(8) { remote.stop() } }
            catch (e: Exception) {
                AppLog.i("DESKTOP delegated: stop failed (${e.javaClass.simpleName}); closing producer", false)
                // Ordinary unbind is NOT a producer-death acknowledgement. Try the
                // explicit close transaction before trusting actual Binder death.
                val closed = runCatching { bounded(8) { remote.destroy() }; true }.getOrDefault(false)
                val confirmedDead = if (closed) false else try {
                    serviceDied.await(6, TimeUnit.SECONDS) || !binder.isBinderAlive
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); !binder.isBinderAlive }
                if (!closed && !confirmedDead) {
                    val message = "Служба не подтвердила закрытие экрана. Завершите HeadSwap перед повторным подключением"
                    failure = message
                    DexState.error = message
                    fail(message)
                    // Keep this generation's consumer until the actual producer death
                    // recipient acknowledges desktopSurfaceProducerClosed().
                    throw IOException(message, e)
                }
            }
        }
        EmbeddedSessionRegistry.producerStopped(this)
        this.generation = 0
        retiringGeneration = 0
        displayId = -1
        if (!stopping.get()) status("Ожидаю повторного подключения USB CarLife…")
    }

    override fun onFirstFrame(generation: Long) {
        if (stopping.get() || this.generation != generation) return
        firstFrame = true
        established = true
        DexState.running = true
        DexState.displayId = displayId
        status("${DesktopModePolicy.label(desktopMode)} → CarLife · ${core.videoMode.outLabel}", false)
    }

    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (points.isEmpty() || points.size > 2) return
        val width = core.videoMode.outW
        val height = core.videoMode.outH
        if (desktopMode != DesktopModePolicy.SAMSUNG_DEX) {
            val ids = points.map { it.id }.toIntArray()
            val xs = points.map { it.x.coerceIn(0, width - 1).toFloat() }.toFloatArray()
            val ys = points.map { it.y.coerceIn(0, height - 1).toFloat() }.toFloatArray()
            if (runCatching { DesktopTouchState.validate(action, actionIndex, ids, xs, ys) }.isFailure) return
            enqueue { it.injectTouchPoints(action, actionIndex, ids, xs, ys) }
        } else {
            // Samsung window captions and resize edges require mouse input.
            val p = points.first()
            if (action !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL)) return
            val buttons = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0 else MotionEvent.BUTTON_PRIMARY
            enqueue { it.injectPointer(action, p.x.coerceIn(0, width - 1), p.y.coerceIn(0, height - 1), buttons) }
        }
    }

    override fun sendKey(keycode: Int) {
        if (keycode !in 0..1000) return
        enqueue { it.injectKey(0, keycode); it.injectKey(1, keycode) }
    }

    override fun requestKeyFrame() { /* CarLife requests its own encoder's IDR directly. */ }

    override fun showDesktop(): Boolean {
        if (stopping.get() || generation == 0L || retiringGeneration == generation || !firstFrame) return false
        enqueue { it.showDesktop() }
        return true
    }

    private fun enqueue(block: (IDexUserService) -> Unit) {
        val token = generation
        if (stopping.get() || token == 0L || retiringGeneration == token || !firstFrame) return
        try { input.execute {
            if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) try { service?.let(block) }
            catch (e: Exception) {
                // An already dequeued input can overlap normal USB-detach cleanup.
                // Its retired display is gone; this must not end a reconnectable session.
                if (!stopping.get() && generation == token && retiringGeneration != token && firstFrame) {
                    fail("Не удалось передать управление рабочему столу: ${e.message}", failureKind(e))
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (!stopping.get()) fail("Очередь управления переполнена. Подключитесь снова")
        }
    }

    private fun <T> bounded(seconds: Long, block: () -> T): T {
        val future = binderCalls.submit<T> { block() }
        return future.get(seconds, TimeUnit.SECONDS)
    }

    private fun helperUnavailableMessage(): String =
        if (DelegatedDesktopAccess.isReady(context)) "Служба рабочего стола завершилась; доступ с компьютера сохранён"
        else "Доступ завершён. Подключите телефон к компьютеру для повторной активации"

    private fun failureKind(error: Throwable): DesktopFailure =
        if (error is DeadObjectException || error.cause is DeadObjectException) DesktopFailure.HELPER_UNAVAILABLE else DesktopFailure.OTHER

    private fun fail(message: String, reason: DesktopFailure = DesktopFailure.OTHER) {
        if (stopping.get() || !failureSignalled.compareAndSet(false, true)) return
        failure = message
        DexState.error = message
        AppLog.i("DESKTOP delegated: $message")
        // The host must retire this one-shot connector even if the USB transport stays alive.
        runCatching { onFailed(reason) }.onFailure { AppLog.i("DEX failure callback: ${it.javaClass.simpleName}", false) }
        stop {}
    }

    override fun stop(onDone: () -> Unit) {
        val firstStop = synchronized(stateLock) {
            stopping.compareAndSet(false, true).also { if (it) {
                EmbeddedSessionRegistry.deactivate(this)
                inputGateway.deactivate()
            } }
        }
        if (firstStop) {
            connected.countDown()
            watchdog.shutdownNow()
            input.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
            // Release the producer first. Unlike a shell-hosted user service, ordinary
            // unbind does not kill its process and cannot prove Surface safety.
            core.stopDesktopSurface {
                unbind()
                binderCalls.shutdownNow()
                if (core.peer === this) core.peer = null
                core.onAaEnded(established)
                DexState.running = false
                DexState.displayId = -1
                DexState.error = failure
                status(failure ?: "Рабочий стол остановлен")
                finished.countDown()
            }
        }
        Thread({
            try { finished.await(); onDone() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }, "dex-stop").apply { isDaemon = true; start() }
    }

    private fun unbind() {
        main.post {
            // Reached after consumer disposal, or a late first bind that never started
            // a producer. The helper process may stay cached: release its death hook
            // so completed USB sessions are not retained until that process exits.
            serviceBinder?.let { binder -> runCatching { binder.unlinkToDeath(death, 0) } }
            service = null
            serviceBinder = null
            if (bindRequested) {
                bindRequested = false
                runCatching { context.unbindService(connection) }
                    .onFailure { AppLog.i("DESKTOP delegated unbind: ${it.javaClass.simpleName}", false) }
            }
        }
    }

    private fun status(text: String, placeholder: Boolean = true) {
        DexState.status = text
        AaState.status = text
        if (placeholder) core.showStatus(text)
        AppLog.i("DEX: $text")
    }
}
