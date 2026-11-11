package com.yegorkapomidorka.headswap.dex

import android.app.KeyguardManager
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * h1007 (Android 13–15): тач рабочего стола при погашенном экране телефона. Пока телефон interactive, тач идёт как
 * раньше (injectInputEvent); на касание при погашенном экране жест целиком уходит в тачскрин shell-хоста
 * [UinputHostMain]. Путь выбирается на ACTION_DOWN и не меняется до конца жеста. Нет хоста - всё как раньше.
 * Тачскрин есть у экрана рабочего стола и у каждого дочернего (h1081: приложение на весь экран).
 */
internal object UinputTouch {
    const val METHOD = "uinput_host"
    val supported: Boolean get() = VdmHostPolicy.uinputSdk(Build.VERSION.SDK_INT)

    private val client = Binder()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "headswap-uinput").apply { isDaemon = true } }
    @Volatile private var host: IUinputHost? = null
    @Volatile private var appContext: Context? = null
    private val wanted = HashMap<Int, Pair<Int, Int>>()
    private val opened = HashSet<Int>()
    /** Путь текущего жеста по дисплею: true - uinput. */
    private val gestures = HashMap<Int, Boolean>()
    private val converters = HashMap<Int, UinputTouchEvents>()
    private var describedDowns = 0
    @Volatile private var lastHostError = ""

    /** Из [VdmHostProvider.call]; вызывающий уже проверен как uid 2000. */
    fun accept(context: Context, extras: Bundle?): Bundle {
        val binder = extras?.getBinder(VdmDesktop.EXTRA_HOST)
        val status = extras?.getString(VdmDesktop.EXTRA_STATUS) ?: "?"
        if (binder == null || !supported || !DelegatedDesktopAccess.isReady(context)) {
            log("хост отклонён (активация не готова или Android не 13–15): $status")
            return Bundle().apply { putBoolean(VdmDesktop.EXTRA_ACCEPTED, false) }
        }
        appContext = context.applicationContext
        val next = IUinputHost.Stub.asInterface(binder)
        val pending = synchronized(this) {
            host = next
            opened.clear()
            runCatching { binder.linkToDeath({ onHostDied(next) }, 0) }
            wanted.keys.toList()
        }
        log("хост подключён: $status")
        pending.forEach(::openAsync)
        return Bundle().apply { putBoolean(VdmDesktop.EXTRA_ACCEPTED, true); putBinder(VdmDesktop.EXTRA_CLIENT, client) }
    }

    private fun onHostDied(dead: IUinputHost) {
        synchronized(this) { if (host === dead) { host = null; opened.clear() } }
        log("хост завершился: тач при погашенном экране снова не работает (${screenState()})")
    }

    /** Экран рабочего стола зарегистрирован или сменил размер. */
    fun want(context: Context, displayId: Int, width: Int, height: Int) {
        if (!supported) return
        appContext = context.applicationContext
        synchronized(this) { wanted[displayId] = width to height; opened.remove(displayId) }
        if (host == null) { log("дисплей $displayId ${width}x$height: жду хост"); watchdog() }
        openAsync(displayId)
    }

    private val watchdogStarted = AtomicBoolean(false)

    /**
     * h1044 (Xiaomi A15): хост запущен активацией с телефона, но приложению так и не отозвался, причина осталась в
     * его логе под shell. Через [HOST_WAIT_MS] без хоста один раз за процесс читаем этот лог через свою беспроводную
     * отладку (если активация была с телефона) и пишем в наш лог.
     */
    private fun watchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        thread(name = "headswap-uinput-watchdog", isDaemon = true) {
            Thread.sleep(HOST_WAIT_MS)
            if (host != null) return@thread
            val ctx = appContext ?: return@thread
            val state = PhoneActivationState(ctx)
            log("хост не подключился за ${HOST_WAIT_MS / 1000} с (${screenState()}); активация с телефона=${state.paired && PhoneAdb.hasIdentity(ctx)} " +
                "порт=${state.connectPort} adb_wifi=${Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0)}")
            if (!state.paired || !PhoneAdb.hasIdentity(ctx) || state.connectPort !in 1..65535) return@thread
            val out = runCatching {
                PhoneAdb.session(ctx, LocalAddressPolicy.LOOPBACK, state.connectPort) { adb ->
                    adb.shell(HOST_REPORT).output
                }
            }
            out.onSuccess { text ->
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(40).forEach { log("лог хоста| $it") }
            }.onFailure { e ->
                log("лог хоста не прочитан: ${(e as? AdbAccessException)?.reason?.name ?: "${e.javaClass.simpleName}: ${e.message}"}")
            }
        }
    }

    private const val HOST_WAIT_MS = 10_000L
    private val HOST_REPORT = "ls -l ${VdmHostPolicy.UINPUT_LOG} 2>&1; tail -c 2000 ${VdmHostPolicy.UINPUT_LOG} 2>&1; " +
        "echo procs:; ps -A -o PID,USER,NAME 2>/dev/null | grep app_process"

    fun drop(displayId: Int) {
        if (!supported) return
        val h = synchronized(this) {
            wanted.remove(displayId); opened.remove(displayId); gestures.remove(displayId); converters.remove(displayId)
            host
        } ?: return
        worker.execute { runCatching { h.close(displayId) } }
    }

    fun dropAll() { synchronized(this) { wanted.keys.toList() }.forEach(::drop) }

    private fun openAsync(displayId: Int) = worker.execute {
        val (h, size) = synchronized(this) { host to wanted[displayId] }
        if (h == null || size == null) return@execute
        val ok = runCatching { h.open(displayId, size.first, size.second) }
        synchronized(this) { if (ok.getOrNull() == true && host === h && wanted[displayId] == size) opened += displayId }
        val reason = ok.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: if (ok.getOrNull() == true) null else runCatching { h.lastError() }.getOrNull()
        log("тачскрин дисплея $displayId ${size.first}x${size.second}: ${if (reason == null) "создан" else "не создан ($reason)"}")
        if (reason == null) {
            Thread.sleep(1_000)
            log("тачскрин дисплея $displayId через 1 с: ${runCatching { h.describe(displayId) }.getOrElse { it.message }}")
        }
    }

    /**
     * Тач рабочего стола (уже проверен шлюзом). true - событие ушло в uinput, false - вызывающий инжектит как раньше.
     */
    fun route(displayId: Int, event: MotionEvent, width: Int, height: Int): Boolean {
        if (!supported) return false
        val action = event.actionMasked
        val h: IUinputHost
        synchronized(this) {
            val use = if (action == MotionEvent.ACTION_DOWN) {
                val ready = host?.asBinder()?.isBinderAlive == true && displayId in opened
                val interactive = interactive()
                (ready && interactive == false).also { gestures[displayId] = it; logDown(displayId, it, ready, interactive) }
            } else gestures[displayId] ?: false
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestures.remove(displayId)
            if (!use) return false
            h = host ?: return true // хост умер посреди жеста: событие теряем, не инжектим половину жеста
        }
        val n = event.pointerCount
        val triplets = synchronized(this) {
            converters.getOrPut(displayId) { UinputTouchEvents() }.convert(action, event.actionIndex,
                IntArray(n) { event.getPointerId(it) }, FloatArray(n) { event.getX(it) }, FloatArray(n) { event.getY(it) }, width, height)
        }
        if (triplets.isNotEmpty()) runCatching { h.events(displayId, triplets) }
            .onFailure { log("событие не отправлено: ${it.javaClass.simpleName}: ${it.message}") }
        // events() oneway: ошибку записи в хосте видно только через lastError (h1025: test1 молча не писал на A15).
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) worker.execute {
            val error = runCatching { h.lastError() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (error.isNotEmpty() && error != lastHostError) { lastHostError = error; log("ошибка хоста: $error") }
        }
        return true
    }

    private fun logDown(displayId: Int, uinput: Boolean, ready: Boolean, interactive: Boolean?) {
        log("тач DOWN дисплей $displayId -> ${if (uinput) "uinput" else "inject"} (тачскрин ${if (ready) "готов" else "нет"}, ${screenState(interactive)})")
        val h = host
        if (uinput && h != null && describedDowns++ < 3) worker.execute {
            log("тачскрин дисплея $displayId на касании: ${runCatching { h.describe(displayId) }.getOrElse { it.message }}")
        }
    }

    private fun interactive(): Boolean? = runCatching { appContext?.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull()

    private fun screenState(interactive: Boolean? = interactive()): String {
        val locked = runCatching { appContext?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull()
        return "экран телефона ${when (interactive) { true -> "вкл"; false -> "выкл"; null -> "?" }}, keyguard=$locked"
    }

    private fun log(text: String) = AppLog.i("UINPUT $text", false)
}
