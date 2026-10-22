package com.yegorkapomidorka.headswap.autosrv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.link.bt.CarBtState
import com.yegorkapomidorka.headswap.link.car.CarProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Точка входа автозапуска сервера ГУ Android Auto: одна автоматизация за раз (повторный вызов
 * присоединяется к идущей), работает на своей нити, ждать результат можно с таймаутом.
 * Все строки лога с префиксом AUTOSRV.
 */
object AutoServer {
    class Run(val trigger: Trigger) {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        @Volatile var outcome: AutoOutcome? = null
    }

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "autosrv").also { it.isDaemon = true } }
    private var current: Run? = null

    /** Подключённая служба спецвозможностей (null = выключена пользователем или убита системой). */
    @Volatile var service: AutoServerService? = null

    @Volatile var last: AutoOutcome? = null
        private set
    @Volatile var lastTrigger: Trigger? = null
        private set

    /** Сколько после обнаружения экрана согласия AFTER_SESSION/RETRY не лезут в настройки AA и не перекрывают диалог. */
    private const val CONSENT_HOLD_MS = 90_000L

    /** elapsedRealtime, когда автоматизация увидела экран первой настройки AA; 0 = не видела (или потом прошла штатно). */
    @Volatile var consentAt: Long = 0L

    val isConnected: Boolean get() = service != null
    val lastLocked: Boolean get() = last?.result == AutoResult.LOCKED

    /** Экран первой настройки AA ждёт пользователя (липкое окно CONSENT_HOLD_MS). */
    fun consentPending(): Boolean {
        val t = consentAt
        return t != 0L && SystemClock.elapsedRealtime() - t < CONSENT_HOLD_MS
    }

    fun log(line: String) = AppLog.i("AUTOSRV $line", true)

    /** Ждёт подключения службы не дольше waitMs; true = подключена. */
    fun awaitService(waitMs: Long): Boolean {
        val end = System.nanoTime() + waitMs * 1_000_000
        while (service == null && System.nanoTime() < end) Thread.sleep(50)
        return service != null
    }

    /** Служба включена в системных настройках (может быть ещё не подключена). */
    fun isListed(ctx: Context): Boolean {
        val cls = AutoServerService::class.java
        val enabled = try {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled && AutoServerPolicy.isListed(list, ctx.packageName, cls.name)
    }

    fun component(ctx: Context) = ComponentName(ctx, AutoServerService::class.java)

    @Synchronized
    fun start(trigger: Trigger): Run {
        current?.let {
            if (it.done.count > 0) {
                log("уже идёт (${it.trigger.label}), присоединяюсь (${trigger.label})")
                return it
            }
        }
        val run = Run(trigger)
        current = run
        val svc = service
        if (svc == null) {
            finish(run, AutoOutcome(AutoResult.SERVICE_OFF, "служба спецвозможностей не подключена", 0))
            return run
        }
        log("запуск: ${trigger.label}")
        exec.execute {
            val outcome = try {
                ServerAutomation(AccessibilityServerUi(svc), { log(it) }, onStarted = { run.started.countDown() }).restart()
            } catch (e: Throwable) {
                AppLog.e("AUTOSRV сбой автоматизации", e)
                AutoOutcome(AutoResult.ERROR, "${e.javaClass.simpleName}: ${e.message}", 0)
            }
            finish(run, outcome)
        }
        return run
    }

    private val hotspotQueued = arrayOf(AtomicBoolean(false), AtomicBoolean(false))   // [0] выключить, [1] включить

    /**
     * Эксперимент h135: включить/выключить точку доступа телефона через спецвозможности (HotspotAutomation).
     * На той же нити, что и сценарий AA, USB-гейтинг не действует. force: выключать, даже если точку включили не мы.
     * Повторный запрос того же направления, пока предыдущий в очереди, отбрасывается (всплески ACL).
     */
    fun hotspot(on: Boolean, reason: String, force: Boolean = false) {
        val svc = service
        val dir = if (on) "включить" else "выключить"
        if (svc == null) {
            log("точка доступа: $dir ($reason): служба спецвозможностей не подключена")
            return
        }
        val queued = hotspotQueued[if (on) 1 else 0]
        if (!queued.compareAndSet(false, true)) {
            log("точка доступа: $dir ($reason): такой запрос уже в очереди")
            return
        }
        log("точка доступа: $dir ($reason)")
        exec.execute {
            queued.set(false)
            try {
                HotspotAutomation.attach(svc)
                val o = HotspotAutomation(AccessibilityHotspotUi(svc), { log("точка: $it") }).run(on, force)
                // шторка на заблокированном экране не помогла: повторим после разблокировки (если не отменит возврат машины)
                if (!on && o.locked && (o.result == HotspotResult.NOT_FOUND || o.result == HotspotResult.TIMEOUT)) deferOffUntilUnlock(svc, reason)
            } catch (e: Throwable) {
                AppLog.e("AUTOSRV сбой сценария точки доступа", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var offTask: Runnable? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var unlockCtx: Context? = null

    /**
     * Выключить точку через [delayMs] после отбоя ACL машины; повторный вызов перезапускает таймер. Не зависит от
     * BridgeService (его могло не быть). Перед выключением проверяет, что машина не вернулась и сессия не идёт.
     */
    @Synchronized
    fun scheduleHotspotOff(ctx: Context, delayMs: Long, reason: String) {
        val app = ctx.applicationContext
        offTask?.let { main.removeCallbacks(it) }
        val task = Runnable {
            synchronized(this) { offTask = null }
            offIfCarGone(app, reason, "таймер")
        }
        offTask = task
        main.postDelayed(task, delayMs)
        log("точка доступа: выключение отложено на $delayMs мс ($reason)")
    }

    /** Машина вернулась (ACL_CONNECTED): снять отложенное выключение и ожидание разблокировки. */
    @Synchronized
    fun cancelHotspotOff() {
        val t = offTask
        if (t != null) {
            main.removeCallbacks(t)
            offTask = null
            log("точка доступа: отложенное выключение отменено (машина подключилась)")
        }
        dropUnlockReceiver("машина подключилась")
    }

    private fun offIfCarGone(ctx: Context, reason: String, source: String) {
        CarBtState.check(ctx, CarProfile(ctx).btMac) { connected ->
            when {
                connected -> log("точка доступа: выключение ($reason, $source) пропущено: машина снова подключена по Bluetooth")
                BridgeService.alive && CarLifeState.running -> log("точка доступа: выключение ($reason, $source) пропущено: идёт сессия")
                else -> hotspot(false, reason)
            }
        }
    }

    @Synchronized
    private fun deferOffUntilUnlock(ctx: Context, reason: String) {
        if (unlockReceiver != null) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                dropUnlockReceiver("разблокировали")
                log("точка доступа: телефон разблокирован, повторяю выключение ($reason)")
                offIfCarGone(c.applicationContext, "unlock", "разблокировка")
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(rcv, filter)
            unlockReceiver = rcv
            unlockCtx = ctx
            log("точка доступа: экран заблокирован, выключение ($reason) отложено до разблокировки")
        } catch (e: Exception) {
            AppLog.e("AUTOSRV ресивер разблокировки", e)
        }
    }

    @Synchronized
    private fun dropUnlockReceiver(why: String) {
        val r = unlockReceiver ?: return
        unlockReceiver = null
        try {
            unlockCtx?.unregisterReceiver(r)
        } catch (_: Exception) {
        }
        unlockCtx = null
        log("точка доступа: ожидание разблокировки снято ($why)")
    }

    private fun finish(run: Run, outcome: AutoOutcome) {
        run.outcome = outcome
        when (outcome.result) {
            AutoResult.CONSENT -> consentAt = SystemClock.elapsedRealtime()
            AutoResult.OK -> consentAt = 0L
            else -> {}
        }
        last = outcome
        lastTrigger = run.trigger
        run.started.countDown()
        run.done.countDown()
    }

    /**
     * Ждёт до waitMs. Возвращает итог, если автоматизация закончилась; если сервер уже запущен, а проверка и
     * возврат в приложение ещё идут - OK «сервер запущен»; по таймауту null.
     */
    fun await(run: Run, waitMs: Long): AutoOutcome? {
        val end = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            run.outcome?.let { return it }
            if (run.started.count == 0L) return AutoOutcome(AutoResult.OK, "сервер запущен, проверка продолжается", 0)
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            run.done.await(minOf(left, 50), TimeUnit.MILLISECONDS)
        }
    }

    fun summary(ctx: Context): String {
        val svc = if (isConnected) "служба подключена" else if (isListed(ctx)) "служба включена, но не подключена" else "служба спецвозможностей выключена"
        val l = last
        return "AUTOSRV: $svc" + if (l != null) "; последний: ${lastTrigger?.label} -> $l" else ""
    }
}
