// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** Owned-display lease engine. Runs in the :delegated app process, one producer per desktop session. */
@Keep
class DexUserService private constructor(private val context: Context,
    private val delegatedInputGateway: IDelegatedInputGateway) : IDexUserService.Stub() {
    private val delegatedShutdown = DelegatedShutdownGate()
    private val clientUid = context.applicationInfo.uid
    private val worker = HandlerThread("headswap-app-display").apply { start() }
    private val handler = Handler(worker.looper)
    private val generation = AtomicLong()
    // Never return the service Binder to a companion; this token has no callable operations.
    private val producerLifetime = Binder()
    // Everything below is accessed only on worker, including destruction and input state.
    private var session: Session? = null
    private var runtime: DesktopRuntimeAccess? = null
    private var mouse = DexMouseState()
    private val keyDownTimes = HashMap<Int, Long>()
    private var mouseDownTime = 0L

    private class Session(val epoch: Long, val surface: Surface, val token: IBinder, val death: IBinder.DeathRecipient,
        val density: Int, val desktopMode: Int, val width: Int, val height: Int) {
        var display: OwnedDisplay? = null
        var linked = false
        val children = LinkedHashMap<IBinder, ChildSession>()
        val contacts = DesktopTouchState()
        var touchDownTime = 0L
    }

    private class ChildSession(val parentEpoch: Long, val surface: Surface, val token: IBinder,
        val death: IBinder.DeathRecipient, var width: Int, var height: Int, var density: Int) {
        var display: OwnedDisplay? = null
        var linked = false
        var touch = EmbeddedTouchState(width, height)
        val contacts = DesktopTouchState()
        var downTime = 0L
        var launchedPackage: String? = null
        var taskStateAt = 0L
        var taskState = -1
    }

    override fun protocolVersion(): Int {
        enforceClient()
        return 5
    }

    override fun start(surface: Surface, lifetimeToken: IBinder, density: Int): Int =
        startDesktop(surface, lifetimeToken, density, DesktopModePolicy.SAMSUNG_DEX)

    override fun startDesktop(surface: Surface, lifetimeToken: IBinder, density: Int, mode: Int): Int =
        startDesktopSized(surface, lifetimeToken, DexUserServicePolicy.WIDTH, DexUserServicePolicy.HEIGHT, density, mode)

    override fun startDesktopSized(surface: Surface, lifetimeToken: IBinder, width: Int, height: Int, density: Int, mode: Int): Int {
        enforceClient()
        DesktopModePolicy.requireMode(mode)
        DelegatedDesktopPolicy.requireMode(mode)
        DexUserServicePolicy.requireDensity(density)
        DexUserServicePolicy.requireDimensions(width, height)
        require(surface.isValid) { "Поверхность видеокодера недоступна" }
        require(lifetimeToken.isBinderAlive) { "Приложение уже остановлено" }
        val epoch = generation.incrementAndGet()
        try { return serialized(START_TIMEOUT_MS) {
            releaseCurrent()
            check(generation.get() == epoch && lifetimeToken.isBinderAlive) { "DeX остановлен" }
            lateinit var lease: Session
            val death = IBinder.DeathRecipient {
                if (generation.compareAndSet(epoch, epoch + 1)) {
                    Thread({
                        runCatching {
                            serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) {
                                if (session === lease) releaseCurrent()
                            }
                        }.onFailure { Log.w(TAG, "owner death cleanup failed", it) }
                    }, "headswap-owner-death").apply { isDaemon = true; start() }
                }
            }
            lease = Session(epoch, surface, lifetimeToken, death, density, mode, width, height)
            mouse = DexMouseState(width, height)
            session = lease
            try {
                lifetimeToken.linkToDeath(death, 0)
                lease.linked = true
                ensureActive(epoch)
                val access = runtime ?: DelegatedDesktopRuntime(context, delegatedInputGateway).also { runtime = it }
                ensureActive(epoch)
                lease.display = access.createDisplay(surface, width, height, density, mode)
                // A stop/death that arrived during create must release its late result.
                ensureActive(epoch)
                val display = checkNotNull(lease.display) { "Не удалось создать экран DeX" }.display
                val id = display.displayId
                validateDisplay(lease)
                access.registerParentDisplay(id, lease.token, width, height)
                ensureActive(epoch)
                launchDesktop(lease)
                ensureActive(epoch)
                Log.i(TAG, "started display=$id mode=${DesktopModePolicy.label(mode)} native=${width}x${height} density=$density flags=${DesktopModePolicy.displayFlags(mode, android.os.Build.VERSION.SDK_INT, DelegatedDesktopAccess.canKeepUnlocked(context))}")
                id
            } catch (e: Throwable) {
                releaseCurrent()
                throw e
            }
        } } catch (e: Throwable) {
            // Includes a request cancelled while still queued, before Session was installed.
            surface.release()
            throw e
        }
    }

    override fun injectPointer(action: Int, x: Int, y: Int, buttons: Int) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val active = requireSession()
            val events = mouse.events(action, x, y, buttons)
            val access = checkNotNull(runtime)
            for (event in events) {
                ensureActive(active.epoch)
                if (event.action == MotionEvent.ACTION_DOWN) mouseDownTime = SystemClock.uptimeMillis()
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coords = MotionEvent.PointerCoords().apply {
                    this.x = event.x.toFloat(); this.y = event.y.toFloat()
                    pressure = if (event.buttons != 0) 1f else 0f
                    size = 1f
                }
                val motion = MotionEvent.obtain(mouseDownTime, SystemClock.uptimeMillis(), event.action, 1,
                    arrayOf(properties), arrayOf(coords), 0, event.buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                try {
                    access.inject(motion, checkNotNull(active.display).display.displayId, event.actionButton)
                } finally { motion.recycle() }
            }
        }
    }

    override fun injectKey(action: Int, keycode: Int) {
        enforceClient()
        require(action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) { "Недопустимое действие клавиши" }
        // CarLife NAVI is a vendor code, not an Android key event.
        if (keycode > 1000) return
        require(keycode > 0) { "Недопустимая клавиша" }
        serialized(if (keycode == KeyEvent.KEYCODE_HOME || keycode == KeyEvent.KEYCODE_APP_SWITCH) START_TIMEOUT_MS else INPUT_TIMEOUT_MS) {
            val active = requireSession()
            if (DesktopModePolicy.consumesNavigationKey(active.desktopMode, keycode)) {
                if (action == KeyEvent.ACTION_UP) launchDesktop(active)
            } else injectKeyInto(active, action, keycode)
        }
    }

    override fun injectTouchPoints(action: Int, actionIndex: Int, pointerIds: IntArray,
        xs: FloatArray, ys: FloatArray) {
        enforceClient()
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        serialized(INPUT_TIMEOUT_MS) {
            val active = requireSession()
            check(active.desktopMode != DesktopModePolicy.SAMSUNG_DEX) { "DeX uses mouse input" }
            for (event in active.contacts.events(action, actionIndex, pointerIds, xs, ys, active.width, active.height)) {
                ensureActive(active.epoch)
                if (event.action == MotionEvent.ACTION_DOWN) active.touchDownTime = SystemClock.uptimeMillis()
                injectContacts(checkNotNull(active.display).display.displayId, active.touchDownTime, event)
            }
        }
    }

    override fun injectChildTouchPoints(childToken: IBinder, action: Int, actionIndex: Int,
        pointerIds: IntArray, xs: FloatArray, ys: FloatArray) {
        enforceClient()
        DesktopTouchState.validate(action, actionIndex, pointerIds, xs, ys)
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            child.touch.cancel()?.let { injectChildTouchEvent(child, it) }
            for (event in child.contacts.events(action, actionIndex, pointerIds, xs, ys, child.width, child.height)) {
                ensureChildActive(parent, child)
                if (event.action == MotionEvent.ACTION_DOWN) child.downTime = SystemClock.uptimeMillis()
                injectContacts(checkNotNull(child.display).display.displayId, child.downTime, event)
            }
        }
    }

    private fun injectContacts(displayId: Int, downTime: Long, event: DesktopTouchState.Event) {
        val props = event.points.map { point -> MotionEvent.PointerProperties().apply {
            id = point.id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coords = event.points.mapIndexed { i, point -> MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y; size = 1f
            pressure = if (event.action == 1 || event.action == 3 || (event.action == 6 && event.actionIndex == i)) 0f else 1f
        } }.toTypedArray()
        val action = event.action or (if (event.action == 5 || event.action == 6) event.actionIndex shl 8 else 0)
        val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, props.size,
            props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { checkNotNull(runtime).inject(motion, displayId) } finally { motion.recycle() }
    }

    override fun showDesktop() {
        enforceClient()
        serialized(START_TIMEOUT_MS) { launchDesktop(requireSession()) }
    }

    private fun launchDesktop(active: Session) {
        ensureActive(active.epoch)
        val id = checkNotNull(active.display).display.displayId
        checkNotNull(runtime).launchDesktop(active.desktopMode, id)
        ensureActive(active.epoch)
    }

    private fun injectKeyInto(active: Session, action: Int, keycode: Int) {
        ensureActive(active.epoch)
        val now = SystemClock.uptimeMillis()
        val down = if (action == KeyEvent.ACTION_DOWN) keyDownTimes.getOrPut(keycode) { now }
            else keyDownTimes.remove(keycode) ?: now
        val key = KeyEvent(down, now, action, keycode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        checkNotNull(runtime).inject(key, checkNotNull(active.display).display.displayId)
    }

    override fun createChildDisplay(surface: Surface, childToken: IBinder, width: Int, height: Int, density: Int): Int {
        enforceClient()
        try {
            EmbeddedDisplayPolicy.requireSize(width, height, density)
            require(surface.isValid && childToken.isBinderAlive) { "Поверхность виджета уже закрыта" }
            return serialized(START_TIMEOUT_MS) {
                val parent = requireOwnDesktopSession()
                check(!parent.children.containsKey(childToken)) { "Поверхность виджета уже зарегистрирована" }
                EmbeddedDisplayPolicy.requireCapacity(parent.children.size)
                EmbeddedDisplayPolicy.requirePixelBudget(
                    parent.children.values.sumOf { it.width.toLong() * it.height }, width, height)
                lateinit var child: ChildSession
                val death = IBinder.DeathRecipient {
                    // Never increment the parent's epoch when just one companion Surface dies.
                    handler.postAtFrontOfQueue {
                        if (session === parent && parent.children[childToken] === child) releaseChild(parent, child)
                    }
                }
                child = ChildSession(parent.epoch, surface, childToken, death, width, height, density)
                parent.children[childToken] = child
                try {
                    childToken.linkToDeath(death, 0)
                    child.linked = true
                    ensureChildActive(parent, child, requireDisplay = false)
                    child.display = checkNotNull(runtime).createChildDisplay(surface, width, height, density)
                    ensureChildActive(parent, child)
                    val id = checkNotNull(child.display).display.displayId
                    validateChildDisplay(parent, child)
                    checkNotNull(runtime).registerChildDisplay(checkNotNull(parent.display).display.displayId,
                        id, child.token, width, height)
                    ensureChildActive(parent, child)
                    Log.i(TAG, "child display=$id parent=${parent.display?.display?.displayId} size=${width}x$height density=$density")
                    id
                } catch (error: Throwable) {
                    releaseChild(parent, child)
                    throw error
                }
            }
        } catch (error: Throwable) {
            // Also disposes the received Binder Surface when admission fails before a lease exists.
            surface.release()
            throw error
        }
    }

    override fun launchChildApp(childToken: IBinder, packageName: String, className: String) {
        enforceClient()
        serialized(START_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            // The runtime validates the explicit component and that it is a launcher activity.
            checkNotNull(runtime).launchChild(checkNotNull(parent.display).display.displayId,
                checkNotNull(child.display).display.displayId, packageName, className)
            ensureChildActive(parent, child)
            child.launchedPackage = packageName
            child.taskStateAt = 0L
        }
    }

    override fun resizeChildDisplay(childToken: IBinder, width: Int, height: Int, density: Int) {
        enforceClient()
        EmbeddedDisplayPolicy.requireSize(width, height, density)
        serialized(START_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            EmbeddedDisplayPolicy.requirePixelBudget(
                parent.children.values.filter { it !== child }.sumOf { it.width.toLong() * it.height }, width, height)
            cancelChildTouch(child)
            checkNotNull(child.display).resize(width, height, density)
            ensureChildActive(parent, child)
            child.width = width
            child.height = height
            child.density = density
            child.touch = EmbeddedTouchState(width, height)
            checkNotNull(runtime).resizeRegisteredDisplay(checkNotNull(child.display).display.displayId,
                child.token, width, height)
            ensureChildActive(parent, child)
            // DisplayManager publishes the resized DisplayInfo asynchronously; subsequent
            // input uses the acknowledged requested size, without querying a stale cached mode.
        }
    }

    override fun injectChildTouch(childToken: IBinder, action: Int, x: Float, y: Float) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            child.contacts.cancel()?.let { injectContacts(checkNotNull(child.display).display.displayId, child.downTime, it) }
            for (event in child.touch.events(action, x, y)) {
                ensureChildActive(parent, child)
                injectChildTouchEvent(child, event)
            }
        }
    }

    override fun injectChildBack(childToken: IBinder) {
        enforceClient()
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            val id = checkNotNull(child.display).display.displayId
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                ensureChildActive(parent, child)
                checkNotNull(runtime).inject(KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
            }
            child.taskStateAt = 0L
        }
    }

    override fun injectChildKey(childToken: IBinder, keycode: Int) {
        enforceClient()
        require(keycode in CHILD_KEYS) { "Недопустимая клавиша окна" }
        serialized(INPUT_TIMEOUT_MS) {
            val (parent, child) = requireChild(childToken)
            val id = checkNotNull(child.display).display.displayId
            val down = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                ensureChildActive(parent, child)
                checkNotNull(runtime).inject(KeyEvent(down, SystemClock.uptimeMillis(), action, keycode,
                    0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), id)
            }
        }
    }

    override fun closeChildDisplay(childToken: IBinder) {
        enforceClient(requirePrivileges = false)
        serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) {
            val parent = session ?: return@serialized
            parent.children[childToken]?.let { releaseChild(parent, it) }
        }
    }

    override fun childTaskState(childToken: IBinder, packageName: String): Int {
        enforceClient()
        EmbeddedDisplayPolicy.requirePackage(packageName)
        return serialized(INPUT_TIMEOUT_MS) {
            val (_, child) = requireChild(childToken)
            // A fresh slot is empty. Packages not launched into this slot are absent;
            // do not query unrelated apps or any other display to answer this check.
            if (child.launchedPackage != packageName) return@serialized 0
            val now = SystemClock.uptimeMillis()
            if (child.taskStateAt == 0L || now - child.taskStateAt >= 150) {
                child.taskState = checkNotNull(runtime).childTaskState(checkNotNull(child.display).display.displayId, packageName)
                child.taskStateAt = now
            }
            child.taskState
        }
    }

    override fun childTaskPackages(childToken: IBinder): Array<String>? {
        enforceClient()
        return serialized(INPUT_TIMEOUT_MS) {
            val (_, child) = requireChild(childToken)
            checkNotNull(runtime).childTaskPackages(checkNotNull(child.display).display.displayId)?.toTypedArray()
        }
    }

    override fun childFullscreen(childToken: IBinder, packageName: String): Int {
        enforceClient()
        EmbeddedDisplayPolicy.requirePackage(packageName)
        return serialized(INPUT_TIMEOUT_MS) {
            val (_, child) = requireChild(childToken)
            if (child.launchedPackage != packageName) return@serialized 0
            checkNotNull(runtime).childFullscreen(checkNotNull(child.display).display.displayId, packageName)
        }
    }

    override fun childProducerLifetime(childToken: IBinder): IBinder {
        enforceClient()
        return serialized(INPUT_TIMEOUT_MS) {
            requireChild(childToken)
            producerLifetime
        }
    }

    private fun injectChildTouchEvent(child: ChildSession, event: EmbeddedTouchState.Event) {
        val now = SystemClock.uptimeMillis()
        if (event.action == MotionEvent.ACTION_DOWN) child.downTime = now
        val motion = MotionEvent.obtain(child.downTime, now, event.action, event.x, event.y,
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) 1f else 0f,
            1f, 0, 1f, 1f, 0, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { checkNotNull(runtime).inject(motion, checkNotNull(child.display).display.displayId) }
        finally { motion.recycle() }
    }

    private fun cancelChildTouch(child: ChildSession) {
        child.touch.cancel()?.let { injectChildTouchEvent(child, it) }
        child.contacts.cancel()?.let { injectContacts(checkNotNull(child.display).display.displayId, child.downTime, it) }
        child.downTime = 0L
    }

    override fun stop() {
        enforceClient(requirePrivileges = false)
        stopOwned()
    }

    override fun destroy() {
        shutdownDelegated()
    }

    /** Ordinary Service.onDestroy: release owned producers, without ending the bootstrap process. */
    fun shutdownDelegated() {
        DelegatedDesktopPolicy.requireOwner(Process.myUid(), clientUid, Binder.getCallingUid())
        delegatedShutdown.close {
            try { stopOwned() } finally { worker.quitSafely() }
        }
    }

    private fun enforceClient(requirePrivileges: Boolean = true) {
        DelegatedDesktopPolicy.requireOwner(Process.myUid(), clientUid, Binder.getCallingUid())
        delegatedShutdown.requireOpen()
        // Cleanup must still release displays when permission adoption has ended.
        if (requirePrivileges) requireDelegatedPermissions()
    }

    private fun requireDelegatedPermissions() {
        DelegatedDesktopPolicy.requirePermissions(
            context.checkSelfPermission(DelegatedDesktopPolicy.ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(DelegatedDesktopPolicy.INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun ensureActive(epoch: Long) {
        // A start may have passed its Binder admission before terminal shutdown began.
        // Recheck on worker so it can never create a late producer after shutdown ACK.
        delegatedShutdown.requireOpen()
        requireDelegatedPermissions()
        check(generation.get() == epoch && session?.epoch == epoch && session?.token?.isBinderAlive == true) { "DeX остановлен" }
    }

    private fun requireSession(): Session = checkNotNull(session) { "Экран DeX не запущен" }.also {
        ensureActive(it.epoch)
        check(it.display?.display?.displayId?.let { id -> id > 0 } == true) { "Нет собственного экрана DeX" }
    }

    /** Child displays hang off HeadSwap's own desktop display only. */
    private fun requireOwnDesktopSession(): Session = requireSession().also {
        EmbeddedDisplayPolicy.requireParent(it.desktopMode, checkNotNull(it.display).display.displayId)
    }

    private fun requireChild(token: IBinder): Pair<Session, ChildSession> {
        val parent = requireOwnDesktopSession()
        val child = checkNotNull(parent.children[token]) { "Экран виджета уже закрыт" }
        ensureChildActive(parent, child)
        return parent to child
    }

    private fun ensureChildActive(parent: Session, child: ChildSession, requireDisplay: Boolean = true) {
        ensureActive(parent.epoch)
        check(child.parentEpoch == parent.epoch && parent.children[child.token] === child && child.token.isBinderAlive) {
            "Поверхность виджета уже закрыта"
        }
        if (requireDisplay) {
            val display = checkNotNull(child.display).display
            EmbeddedDisplayPolicy.requireChildId(display.displayId, checkNotNull(parent.display).display.displayId)
            check(display.isValid) { "Экран виджета недоступен" }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateChildDisplay(parent: Session, child: ChildSession) {
        val display = checkNotNull(child.display).display
        EmbeddedDisplayPolicy.requireChildId(display.displayId, checkNotNull(parent.display).display.displayId)
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val mode = display.mode
        check(metrics.widthPixels == child.width && metrics.heightPixels == child.height &&
            mode.physicalWidth == child.width && mode.physicalHeight == child.height && metrics.densityDpi == child.density &&
            display.flags and DexUserServicePolicy.DISPLAY_TRUSTED != 0) { "Android изменил геометрию или доверие экрана виджета" }
    }

    /** Only worker releases children; keep producer-before-Surface ordering on every path. */
    private fun releaseChild(parent: Session, child: ChildSession) {
        if (parent.children[child.token] !== child) return
        parent.children.remove(child.token)
        if (child.display != null) runCatching { cancelChildTouch(child) }
        child.display?.display?.displayId?.let { unregisterInputDisplay(it, child.token) }
        try {
            child.display?.release()
        } catch (error: Throwable) {
            // Do not acknowledge disposal of a Surface that might still have a native producer.
            Log.e(TAG, "child display release failed; retiring owned producer process", error)
            terminateStuckService()
        }
        child.display = null
        try { child.surface.release() }
        finally { if (child.linked) runCatching { child.token.unlinkToDeath(child.death, 0) } }
        Log.i(TAG, "child display released; remaining=${parent.children.size}")
    }

    private fun unregisterInputDisplay(id: Int, token: IBinder) {
        // Revocation is best effort when the main process/gateway has already died;
        // native display release must still run and is the actual producer ACK.
        runCatching { runtime?.unregisterDisplay(id, token) }
            .onFailure { Log.w(TAG, "input lease revoke failed for display=$id", it) }
    }

    @Suppress("DEPRECATION")
    private fun validateDisplay(lease: Session) {
        val display = checkNotNull(lease.display).display
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val mode = display.mode
        DexUserServicePolicy.validateGeometry(display.displayId, metrics.widthPixels, metrics.heightPixels,
            mode.physicalWidth, mode.physicalHeight, metrics.densityDpi, display.flags,
            expectedDensity = lease.density, desktopMode = lease.desktopMode, expectedWidth = lease.width, expectedHeight = lease.height)
    }

    /** Only worker may release. A successful stop response guarantees display release precedes Surface release. */
    private fun releaseCurrent() {
        val lease = session ?: return
        session = null
        var releaseFailure: Throwable? = null
        try {
            for (child in lease.children.values.toList()) releaseChild(lease, child)
            lease.display?.display?.displayId?.let { unregisterInputDisplay(it, lease.token) }
            lease.display?.release()
        } catch (e: Throwable) {
            releaseFailure = e
        } finally {
            lease.display = null
            // Preserve the VD-before-Surface release order.
            // If VD release failed, keep this Surface reference until process death removes the VD.
            try { if (releaseFailure == null) lease.surface.release() } finally {
                if (lease.linked) runCatching { lease.token.unlinkToDeath(lease.death, 0) }
                mouse.reset()
                keyDownTimes.clear()
                mouseDownTime = 0
            }
        }
        if (releaseFailure != null) terminateStuckService()
        Log.i(TAG, "owned display released")
    }

    private fun stopOwned() {
        generation.incrementAndGet()
        serialized(STOP_TIMEOUT_MS, front = true, cancelOnTimeout = false) { releaseCurrent() }
    }

    private fun <T> serialized(timeout: Long, front: Boolean = false, cancelOnTimeout: Boolean = true, block: () -> T): T {
        val task = FutureTask(block)
        check(if (front) handler.postAtFrontOfQueue(task) else handler.post(task)) { "UserService уже завершён" }
        try {
            return task.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = unwrap(checkNotNull(e.cause))
            Log.e(TAG, "UserService operation failed", cause)
            if (cause is SecurityException) throw cause
            if (cause is IllegalArgumentException) throw cause
            if (cause is IllegalStateException) throw cause
            throw IllegalStateException(cause.message ?: "Ошибка системного экрана DeX", cause)
        } catch (e: Exception) {
            if (e !is TimeoutException && e !is InterruptedException) throw e
            task.cancel(false)
            generation.incrementAndGet()
            if (cancelOnTimeout) {
                val cleanup = FutureTask { releaseCurrent() }
                handler.postAtFrontOfQueue(cleanup)
                try { cleanup.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                catch (_: Exception) { terminateStuckService() }
            } else terminateStuckService()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("Системная служба DeX не ответила вовремя; экран остановлен", e)
        }
    }

    private fun terminateStuckService(): Nothing {
        // Binder death destroys the owned virtual display even if a framework call never returns.
        Log.e(TAG, "terminating unresponsive dedicated UserService to release display")
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    companion object {
        /** Only instantiate in a dedicated ordinary app process, never in the UI/bootstrap process.
         * A stuck framework Binder/release may kill this producer process to release its Surface.
         */
        @JvmStatic
        fun forDelegated(context: Context, inputGateway: IDelegatedInputGateway): DexUserService {
            check(inputGateway.asBinder().isBinderAlive) { "Канал ввода рабочего стола недоступен" }
            val app = context.applicationContext ?: context
            DelegatedDesktopPolicy.requireOwner(Process.myUid(), app.applicationInfo.uid, Binder.getCallingUid())
            DelegatedDesktopPolicy.requirePermissions(
                app.checkSelfPermission(DelegatedDesktopPolicy.ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED,
                app.checkSelfPermission(DelegatedDesktopPolicy.INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED)
            return DexUserService(app, inputGateway)
        }

        private const val TAG = "HeadSwapDexService"
        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val INPUT_TIMEOUT_MS = 2_000L
        /** Клавиши шайбы ГУ во встроенное окно: стрелки, центр, Enter, Tab, листание. HOME/BACK/питание сюда не входят. */
        private val CHILD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
        private fun unwrap(error: Throwable): Throwable = if (error is InvocationTargetException && error.cause != null) unwrap(error.cause!!) else error
    }
}
