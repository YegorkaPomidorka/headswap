// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.yegorkapomidorka.headswap.AppLog

/** TestApi calls stay in the already instrumented main process, not in ordinary helper ART. */
internal class DelegatedInputGateway(context: Context) : IDelegatedInputGateway.Stub() {
    private val context = context.applicationContext
    private val registry = DelegatedInputRegistry()
    @Volatile private var liveDisplayIds: Set<Int> = emptySet()
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private val inputManager = checkNotNull(context.getSystemService(InputManager::class.java))
    private val inject by lazy { InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val displayEvents = DisplayEventAccess()
    private val wakeLocks = DisplayWakeLocks(context)
    private val imeRoutes = DelegatedImeRoutes()
    private val setActionButton by lazy { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }

    fun preflight() {
        try { inject; displayEvents.preflight(); setActionButton }
        catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Android не разрешил управление отдельным экраном в процессе активации: ${error.message}", error)
        }
    }

    @Synchronized fun beginGeneration(generation: Long, token: IBinder) {
        registry.begin(generation, token)
        imeRoutes.clear()
        liveDisplayIds = emptySet()
        activeGateway.set(this)
    }
    @Synchronized fun deactivate() {
        VdmHostLink.releaseAll()
        wakeLocks.releaseAll()
        imeRoutes.clear()
        liveDisplayIds = emptySet()
        activeGateway.compareAndSet(this, null)
        registry.deactivate()
    }

    @Synchronized override fun registerParent(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.registerParent(displayId, token, width, height)
        liveDisplayIds = registry.registeredDisplayIds()
        holdAwake(displayId)
        localIme(registry.requireDisplay(displayId, token))
    }

    @Synchronized override fun registerChild(parentId: Int, displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(parentId); requireTrustedDisplay(displayId)
        registry.registerChild(parentId, displayId, token, width, height)
        liveDisplayIds = registry.registeredDisplayIds()
        holdAwake(displayId)
        localIme(registry.requireDisplay(displayId, token))
    }

    /** Клавиатура на этом же экране ГУ, а не на телефоне. Сразу после регистрации, до запуска приложений. */
    private fun localIme(entry: DelegatedInputRegistry.Entry) {
        if (!applyIme(entry)) imeRoutes.add(entry)
    }

    private fun applyIme(entry: DelegatedInputRegistry.Entry): Boolean {
        val ok = try {
            check(runCatching { registry.requireDisplay(entry.id, entry.token) }.getOrNull() === entry) { "Экран уже закрыт" }
            requireTrustedDisplay(entry.id)
            check(DisplayIme.available(context)) { "нет ${DisplayIme.PERMISSION}" }
            DisplayIme.enable(entry.id)
            true
        } catch (e: Exception) {
            AppLog.i("DESKTOP IME: дисплей ${entry.id} — LOCAL не применён (${e.javaClass.simpleName}: ${e.message}), повтор", false)
            false
        } catch (e: LinkageError) {
            AppLog.i("DESKTOP IME: дисплей ${entry.id} — скрытый API недоступен: ${e.message}", false)
            false
        }
        if (ok) AppLog.i("DESKTOP IME: дисплей ${entry.id} — LOCAL подтверждён", false)
        return ok
    }

    @Synchronized private fun refreshIme() {
        if (activeGateway.get() !== this || !DelegatedDesktopAccess.isReady(context)) return
        imeRoutes.refresh(SystemClock.elapsedRealtime(), ::applyIme)
    }

    @Synchronized override fun resizeDisplay(displayId: Int, token: IBinder, width: Int, height: Int) {
        requireActive(); requireTrustedDisplay(displayId)
        registry.resize(displayId, token, width, height)
    }

    @Synchronized override fun unregisterDisplay(displayId: Int, token: IBinder) {
        requireCaller() // Cleanup remains possible after activation has ended.
        registry.unregister(displayId, token)
        liveDisplayIds = registry.registeredDisplayIds()
        imeRoutes.retain(liveDisplayIds)
        wakeLocks.retain(liveDisplayIds)
    }

    /**
     * Фактические флаги дисплея в лог (видно, принял ли Android ALWAYS_UNLOCKED); дисплей в своей DisplayGroup не должен
     * гаснуть по таймауту экрана, кнопка питания его не усыпляет (goToSleep трогает только общую группу).
     */
    private fun holdAwake(displayId: Int) {
        val display = displays.getDisplay(displayId) ?: return
        val group = VdmDesktop.groupId(display)
        AppLog.i("WAKE экран $displayId: ${DisplayWakeLocks.describe(display.flags)} группа=$group", false)
        if (DisplayWakeLocks.shouldHold(display.flags, group)) wakeLocks.hold(displayId)
    }

    override fun createDeviceDisplay(surface: android.view.Surface, name: String, width: Int, height: Int,
        density: Int, flags: Int, owner: IBinder): Int {
        // Хост мог умереть при подключении ГУ (Samsung перезапускает adbd): ждём перезапуск вне монитора шлюза.
        VdmHostLink.awaitHost(VdmHostRestart.WAIT_MS)
        return createDeviceDisplayLocked(surface, name, width, height, density, flags, owner)
    }

    @Synchronized private fun createDeviceDisplayLocked(surface: android.view.Surface, name: String, width: Int, height: Int,
        density: Int, flags: Int, owner: IBinder): Int {
        requireActive()
        return VdmHostLink.create(surface, name, width, height, density, flags, owner)
    }

    @Synchronized override fun resizeDeviceDisplay(displayId: Int, width: Int, height: Int, density: Int) {
        requireActive()
        VdmHostLink.resize(displayId, width, height, density)
    }

    @Synchronized override fun releaseDeviceDisplay(displayId: Int) {
        requireCaller() // освобождение и после конца активации
        VdmHostLink.release(displayId)
    }

    @Synchronized override fun injectInput(displayId: Int, token: IBinder, event: InputEvent, actionButton: Int) {
        try {
            requireActive()
            val owned = registry.requireDisplay(displayId, token)
            requireTrustedDisplay(displayId)
            when (event) {
                is MotionEvent -> {
                    require(event.source == InputDevice.SOURCE_MOUSE || event.source == InputDevice.SOURCE_TOUCHSCREEN)
                    require(event.pointerCount in 1..2 &&
                        (event.source != InputDevice.SOURCE_MOUSE || event.pointerCount == 1))
                    require((0 until event.pointerCount).all { i ->
                        event.getX(i).isFinite() && event.getY(i).isFinite() &&
                            event.getX(i) >= 0 && event.getX(i) < owned.width &&
                            event.getY(i) >= 0 && event.getY(i) < owned.height
                    })
                    if (event.source == InputDevice.SOURCE_TOUCHSCREEN) {
                        require(actionButton == 0 && event.buttonState == 0)
                        DesktopTouchState.validate(event.actionMasked, event.actionIndex,
                            IntArray(event.pointerCount) { event.getPointerId(it) },
                            FloatArray(event.pointerCount) { event.getX(it) },
                            FloatArray(event.pointerCount) { event.getY(it) })
                    }
                    require(actionButton == 0 || actionButton == MotionEvent.BUTTON_PRIMARY)
                    if (actionButton != 0) setActionButton.invoke(event, actionButton)
                }
                is KeyEvent -> require(actionButton == 0 && event.keyCode in 0..1000 &&
                    event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                else -> error("Недопустимый тип ввода")
            }
            displayEvents.setDisplayId(event, displayId)
            check(inject.invoke(inputManager, event, 0) == true) { "Android отклонил ввод на отдельный экран" }
        } finally { (event as? MotionEvent)?.recycle() }
    }

    @Synchronized override fun childTaskState(displayId: Int, token: IBinder, packageName: String): Int {
        requireActive()
        check(registry.requireDisplay(displayId, token).parent != null) { "Ожидался экран виджета" }
        requireTrustedDisplay(displayId)
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val permitted = DelegatedDesktopAccess.hasPermissions(context)
        return DelegatedDesktopPolicy.taskState(displayId, context.applicationInfo.uid / 100000, packageName, permitted, readTasks(permitted))
    }

    /** Пакеты задач дочернего экрана для «Недавних»: только этот экран и этот пользователь, null - не удалось узнать. */
    @Synchronized override fun childTaskPackages(displayId: Int, token: IBinder): Array<String>? {
        requireActive()
        check(registry.requireDisplay(displayId, token).parent != null) { "Ожидался экран виджета" }
        requireTrustedDisplay(displayId)
        val permitted = DelegatedDesktopAccess.hasPermissions(context)
        return DelegatedDesktopPolicy.childPackages(displayId, context.applicationInfo.uid / 100000, permitted, readTasks(permitted))
            ?.toTypedArray()
    }

    /** Приложение этого дочернего экрана в полноэкранном режиме: 1 да, 0 нет, -1 не удалось узнать. */
    @Synchronized override fun childFullscreen(displayId: Int, token: IBinder, packageName: String): Int {
        requireActive()
        check(registry.requireDisplay(displayId, token).parent != null) { "Ожидался экран виджета" }
        requireTrustedDisplay(displayId)
        EmbeddedDisplayPolicy.requirePackage(packageName)
        val permitted = DelegatedDesktopAccess.hasPermissions(context)
        val tasks = readTasks(permitted)
        // сырое значение в лог при смене: по первому прогону на ГУ видно, отдаёт ли Android поле
        val raw = tasks?.firstOrNull { it.displayId == displayId }?.let { "${it.topPackage}:${it.visibleTypes}" }
        if (raw != lastVisibleTypes) {
            lastVisibleTypes = raw
            AppLog.i("TASKS экран $displayId: requestedVisibleTypes $raw", false)
        }
        return DelegatedDesktopPolicy.fullscreen(displayId, context.applicationInfo.uid / 100000, packageName, permitted, tasks)
    }
    private var lastVisibleTypes: String? = null

    @Suppress("DEPRECATION")
    private fun readTasks(permitted: Boolean): List<DelegatedDesktopPolicy.Task>? {
        var tasks: List<DelegatedDesktopPolicy.Task>? = null
        if (!permitted) diag("прав", "нет прав рабочего стола в процессе ${Process.myPid()}")
        else try {
            val running = context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
            var unreadable = 0
            tasks = running.map {
                val identity = FrameworkTaskIdentityReader.read(it)
                if (identity == null && unreadable++ == 0) diag("displayId", "displayId/userId задачи ${it.taskId} не прочитаны: " +
                    FrameworkTaskIdentityReader.diagnose(it) + "; дальше по пакету")
                DelegatedDesktopPolicy.Task(identity?.displayId ?: DelegatedDesktopPolicy.UNKNOWN,
                    identity?.userId ?: DelegatedDesktopPolicy.UNKNOWN,
                    it.baseActivity?.packageName, it.topActivity?.packageName,
                    visible = Build.VERSION.SDK_INT < 32 || it.isVisible,
                    visibleTypes = FrameworkTaskIdentityReader.requestedVisibleTypes(it))
            }
            if (unreadable == 0) diag("ok", "задачи читаются: ${running.size}, displayId/userId доступны")
        } catch (e: Exception) {
            diag("исключение", "getRunningTasks: ${e.javaClass.simpleName}: ${e.message}")
        }
        return tasks
    }

    /** Причина, по которой состояние задач определяется плохо: в лог один раз на каждую смену причины. */
    private var lastDiag: String? = null
    private fun diag(key: String, text: String) {
        if (key == lastDiag) return
        lastDiag = key
        AppLog.i("TASKS $text", false)
    }

    private fun requireCaller() {
        check(Binder.getCallingUid() == Process.myUid()) { "Шлюз ввода доступен только HeadSwap" }
    }
    private fun requireActive() { requireCaller(); check(DelegatedDesktopAccess.isReady(context)) { "Доступ завершён" } }
    private fun requireTrustedDisplay(id: Int) {
        require(id > 0) { "Ввод на основной экран телефона запрещён" }
        val display = checkNotNull(displays.getDisplay(id)) { "Экран уже закрыт" }
        check(display.isValid && display.flags and 128 != 0) { "Ожидался доверенный отдельный экран" }
    }

    companion object {
        const val EXTRA_BINDER = "headswap_delegated_input_gateway"
        private val activeGateway = java.util.concurrent.atomic.AtomicReference<DelegatedInputGateway?>()
        /** Пульс активации: повторить LOCAL для дисплеев, где Android не применил его сразу. */
        fun refreshImeRoutes() { activeGateway.get()?.refreshIme() }
        fun ownsFocusedDisplay(id: Int): Boolean = id > 0 && activeGateway.get()?.liveDisplayIds?.contains(id) == true
    }
}
