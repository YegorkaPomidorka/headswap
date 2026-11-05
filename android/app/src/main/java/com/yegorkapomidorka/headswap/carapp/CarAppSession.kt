package com.yegorkapomidorka.headswap.carapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppInfo
import androidx.car.app.FailureResponse
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.CarText
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.model.Trip
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.aa.AaMetaSink
import com.yegorkapomidorka.headswap.aa.AaNavDistance
import com.yegorkapomidorka.headswap.aa.AaNavTurn
import com.yegorkapomidorka.headswap.aa.DesktopNavHints

/**
 * Одна сессия хоста с одним навигатором. Порядок как у хоста AA: bind -> getAppInfo -> onHandshakeCompleted ->
 * onAppCreate -> onAppStart -> onAppResume, каждый шаг после ответа на предыдущий (иначе «Lifecycle is not at least
 * created»); getManager("app") -> getTemplate. Поверхность - через ISurfaceCallback из setSurfaceCallback.
 * Всё - на потоке [h]; бинды отвечают на binder-потоках и перекладывают работу на [h].
 */
internal class CarAppSession(
    private val app: Context,
    val target: CarAppTarget,
    private val h: Handler,
    private val events: Events,
) {
    interface Events {
        fun onReady(s: CarAppSession)
        fun onRefused(s: CarAppSession, why: CarAppRefusal)
        fun onTemplate(s: CarAppSession, t: TemplateWrapper?)
        fun onToast(s: CarAppSession, text: CharSequence)
    }

    /** Что хост отдаёт приложению сейчас (пишет CarAppHost, только поток h). */
    class View(
        val surface: Surface?, val w: Int, val h: Int, val dpi: Int, val night: Boolean?,
        val visible: Box, val stable: Box,
    )

    private var view: View? = null
    private var stopped = false
    private var conn: ServiceConnection? = null
    private var carApp: ICarApp? = null
    private var appManager: IAppManager? = null
    private var surfaceCb: ISurfaceCallback? = null
    /** Поверхность, отданная приложению, и с какими размерами/dpi. */
    private var delivered: SurfaceContainerKey? = null
    private var sentVisible: Box? = null
    private var sentStable: Box? = null
    private var sentConfig: String? = null
    private var ready = false
    private var navigating = false
    private var deaths = ArrayDeque<Long>()
    private val touch = CarTouch()
    /** Поколение бинда: ответы старого подключения после переподключения - мимо. */
    private var gen = 0

    private data class SurfaceContainerKey(val surface: Surface, val w: Int, val h: Int, val dpi: Int)

    private fun log(line: String) = AppLog.i("CARAPP ${target.pkg}: $line", false)

    // --- управление (поток h) ---

    fun start() {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                h.post { if (!stopped) connected(ICarApp.Stub.asInterface(binder)) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                h.post { if (!stopped) disconnected() }
            }
            override fun onBindingDied(name: ComponentName) {
                // force-stop или обновление пакета: этот бинд больше не оживёт, биндимся заново
                h.post { if (!stopped && conn === this) rebind() }
            }
        }
        val intent = Intent(CarAppCatalog.ACTION).setComponent(ComponentName(target.pkg, target.service))
        val ok = try {
            app.bindService(intent, c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (e: SecurityException) {
            log("bindService: нет доступа (${e.message})"); refuse(CarAppRefusal.PERMISSION); return
        } catch (e: Exception) {
            log("bindService упал: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        log("bindService ${target.service} -> $ok")
        if (ok) conn = c else refuse(CarAppRefusal.BIND)
    }

    /** Порядок важен: сначала забрать поверхность, иначе процесс приложения переживает хост и рисует в мёртвую
     *  поверхность, а новая сессия висит на заставке (2ГИС, 04.11). */
    fun stop() {
        if (stopped) return
        stopped = true
        gen++
        val a = carApp
        releaseSurface()
        if (a != null && ready) {
            runCatching { a.onAppPause(done("onAppPause")) }
            runCatching { a.onAppStop(done("onAppStop")) }
        }
        endNavigation()
        conn?.let { runCatching { app.unbindService(it) } }
        conn = null; carApp = null; appManager = null; surfaceCb = null; ready = false
        log("остановлен")
    }

    fun update(v: View) {
        view = v
        if (stopped || !ready) return
        val a = carApp ?: return
        val cfg = configKey(v)
        if (cfg != sentConfig) {
            sentConfig = cfg
            runCatching { a.onConfigurationChanged(carConfig(v), done("onConfigurationChanged")) }
                .onFailure { log("onConfigurationChanged упал: ${it.javaClass.simpleName}") }
        }
        deliverSurface()
    }

    fun touch(action: Int, x: Float, y: Float) {
        val cb = surfaceCb ?: return
        try {
            when (val o = touch.feed(action, x, y)) {
                is CarTouch.Click -> cb.onClick(o.x, o.y)
                is CarTouch.Scroll -> cb.onScroll(o.dx, o.dy)
                null -> {}
            }
        } catch (e: Exception) { log("касание не ушло: ${e.javaClass.simpleName}") }
    }

    /** Intent приложению (маршрут от ассистента: ACTION_NAVIGATE + geo:); сессия ещё не поднялась - отдадим после onAppResume. */
    fun newIntent(i: Intent) {
        val a = carApp
        if (a == null || !ready) { pendingIntent = i; log("onNewIntent отложен до готовности"); return }
        pendingIntent = null
        val g = gen
        runCatching { a.onNewIntent(i, done("onNewIntent")) }
            .onSuccess { log("onNewIntent ${i.action} ${i.dataString}") }
            .onFailure { log("onNewIntent упал: ${it.javaClass.simpleName}") }
        // Яндекс Навигатор строит маршрут по следующему getTemplate корневого экрана (сигнал «экран готов» без
        // повтора, декомпиляция 05.11), а сам invalidate не зовёт: просим шаблон ещё раз.
        for (ms in TEMPLATE_AFTER_INTENT_MS) h.postDelayed({ if (g == gen && !stopped) pullTemplate(g) }, ms)
    }
    private var pendingIntent: Intent? = null

    fun back() {
        val m = appManager ?: return
        runCatching { m.onBackPressed(done("onBackPressed")) }.onFailure { log("назад упал: ${it.javaClass.simpleName}") }
    }

    // --- жизненный цикл приложения ---

    private fun connected(a: ICarApp) {
        val g = ++gen
        log("подключён")
        carApp = a; appManager = null; surfaceCb = null; delivered = null; sentVisible = null; sentStable = null
        sentConfig = null; ready = false
        var answered = false
        h.postDelayed({ if (g == gen && !answered) { log("getAppInfo: нет ответа"); refuse(CarAppRefusal.NO_APP_INFO) } }, APP_INFO_TIMEOUT_MS)
        // рукопожатие или create/start/resume без ответа - иначе пустая карта навсегда и окно не откроется
        h.postDelayed({ if (g == gen && !ready) { log("не поднялся за ${READY_TIMEOUT_MS / 1000} с"); refuse(CarAppRefusal.CREATE) } }, READY_TIMEOUT_MS)
        try {
            a.getAppInfo(callback(g, "getAppInfo", onFail = { answered = true; refuse(CarAppRefusal.NO_APP_INFO) }) { r ->
                answered = true
                val info = runCatching { r?.get() as? AppInfo }.getOrNull()
                val level = CarAppPick.apiLevel(info?.minCarAppApiLevel, info?.latestCarAppApiLevel, CarAppApiLevels.getLatest())
                log("AppInfo min=${info?.minCarAppApiLevel} latest=${info?.latestCarAppApiLevel} lib=${info?.libraryDisplayVersion}; API $level")
                if (level == null) refuse(CarAppRefusal.API_LEVEL) else handshake(g, a, level)
            })
        } catch (e: Exception) {
            log("getAppInfo упал: ${e.javaClass.simpleName}"); refuse(CarAppRefusal.NO_APP_INFO)
        }
    }

    private fun handshake(g: Int, a: ICarApp, level: Int) {
        try {
            // HostValidator приложения отвечает здесь: отказ = навигатор не пускает чужой хост
            a.onHandshakeCompleted(Bundleable.create(HandshakeInfo(app.packageName, level)),
                callback(g, "handshake", onFail = { refuse(CarAppRefusal.HANDSHAKE) }) {
                    val v = view
                    sentConfig = v?.let(::configKey)
                    a.onAppCreate(host, Intent(), carConfig(v), callback(g, "onAppCreate", onFail = { refuse(CarAppRefusal.CREATE) }) {
                        a.onAppStart(callback(g, "onAppStart", onFail = { refuse(CarAppRefusal.CREATE) }) {
                            a.onAppResume(callback(g, "onAppResume", onFail = { refuse(CarAppRefusal.CREATE) }) { resumed(g, a) })
                        })
                    })
                })
        } catch (e: Exception) {
            log("рукопожатие упало: ${e.javaClass.simpleName}: ${e.message}"); refuse(CarAppRefusal.HANDSHAKE)
        }
    }

    private fun resumed(g: Int, a: ICarApp) {
        ready = true
        events.onReady(this)
        deliverSurface()
        pendingIntent?.let(::newIntent)
        a.getManager("app", callback(g, "getManager(app)") { r ->
            val b = runCatching { r?.get() }.getOrNull()
            appManager = (b as? IAppManager) ?: (b as? IBinder)?.let { IAppManager.Stub.asInterface(it) }
            pullTemplate(g)
        })
    }

    /** Процесс приложения умер (MIUI режет :androidauto_*): BIND_AUTO_CREATE поднимет сервис заново, сессия - с нуля. */
    private fun disconnected() {
        log("отключён")
        lost()
        noteDeath()
    }

    private fun rebind() {
        log("бинд умер, привязываюсь заново")
        // смерть уже посчитал onServiceDisconnected; без него (сервис не успел подключиться) - считаем здесь
        if (carApp != null) { lost(); if (noteDeath()) return }
        conn?.let { runCatching { app.unbindService(it) } }
        conn = null
        start()
    }

    private fun lost() {
        gen++
        carApp = null; appManager = null; surfaceCb = null; delivered = null; ready = false
        endNavigation()
        events.onTemplate(this, null)
    }

    /** Ещё одна смерть приложения; true - падает слишком часто, сессии отказано. */
    private fun noteDeath(): Boolean {
        val now = SystemClock.uptimeMillis()
        deaths.addLast(now)
        while (deaths.isNotEmpty() && now - deaths.first() > DEATH_WINDOW_MS) deaths.removeFirst()
        if (deaths.size < MAX_DEATHS) return false
        log("падает ${deaths.size} раз за минуту")
        refuse(CarAppRefusal.DIED)
        return true
    }

    private fun refuse(why: CarAppRefusal) {
        if (stopped) return
        log("отказ: $why")
        events.onRefused(this, why)
    }

    private fun carConfig(v: View?): Configuration = Configuration(app.resources.configuration).apply {
        val dpi = v?.dpi?.takeIf { it > 0 } ?: 160
        if (v != null && v.w > 0 && v.h > 0) {
            screenWidthDp = CarConfigMath.dp(v.w, dpi)
            screenHeightDp = CarConfigMath.dp(v.h, dpi)
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        }
        densityDpi = dpi
        // День/ночь: тему Headswap не копируем (2ГИС днём был тёмным). null - UNDEFINED, решает сам навигатор.
        val night = when (v?.night) {
            true -> Configuration.UI_MODE_NIGHT_YES
            false -> Configuration.UI_MODE_NIGHT_NO
            null -> Configuration.UI_MODE_NIGHT_UNDEFINED
        }
        uiMode = (uiMode and (Configuration.UI_MODE_TYPE_MASK or Configuration.UI_MODE_NIGHT_MASK).inv()) or
            Configuration.UI_MODE_TYPE_CAR or night
    }

    private fun configKey(v: View) = "${v.w}x${v.h}@${v.dpi} night=${v.night}"

    // --- поверхность и области ---

    private fun deliverSurface() {
        val cb = surfaceCb ?: return
        if (!ready) return
        val v = view
        val s = v?.surface?.takeIf { it.isValid && v.w > 0 && v.h > 0 }
        val want = s?.let { SurfaceContainerKey(it, v.w, v.h, v.dpi) }
        try {
            // та же Surface с новым размером или dpi (карточка <-> весь экран, масштаб): приложению - как новая
            if (delivered != null && delivered != want) releaseSurface()
            if (want != null && delivered == null) {
                cb.onSurfaceAvailable(Bundleable.create(SurfaceContainer(want.surface, want.w, want.h, want.dpi)), done("onSurfaceAvailable"))
                delivered = want
                sentVisible = null; sentStable = null
                log("поверхность ${want.w}x${want.h} dpi=${want.dpi} отдана")
            }
            if (want != null) {
                if (v.visible != sentVisible) { cb.onVisibleAreaChanged(v.visible.rect(), done("onVisibleAreaChanged")); sentVisible = v.visible }
                if (v.stable != sentStable) { cb.onStableAreaChanged(v.stable.rect(), done("onStableAreaChanged")); sentStable = v.stable }
            }
        } catch (e: Exception) {
            log("поверхность не отдана: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun releaseSurface() {
        val d = delivered ?: return
        delivered = null
        runCatching { surfaceCb?.onSurfaceDestroyed(Bundleable.create(SurfaceContainer(d.surface, d.w, d.h, d.dpi)), done("onSurfaceDestroyed")) }
    }

    private fun Box.rect() = Rect(left, top, right, bottom)

    // --- шаблоны ---

    private fun pullTemplate(g: Int) {
        val m = appManager ?: return
        try {
            m.getTemplate(callback(g, "getTemplate", quiet = true) { r ->
                events.onTemplate(this, runCatching { r?.get() as? TemplateWrapper }.getOrNull())
            })
        } catch (e: Exception) { log("getTemplate упал: ${e.javaClass.simpleName}") }
    }

    // --- навигация -> подсказки на ГУ ---

    private fun endNavigation() {
        if (!navigating) return
        navigating = false
        sink { it.onNavStatus(0) } // иначе ГУ держит последнюю стрелку
    }

    private var lastTripLog = 0L

    private fun onTrip(t: Trip) {
        val step = t.steps.firstOrNull()
        val est = t.stepTravelEstimates.firstOrNull()
        val dest = t.destinationTravelEstimates.firstOrNull()
        val man = step?.maneuver
        val meters = est?.remainingDistance?.let { CarTrip.meters(it.displayDistance, it.displayUnit) }
        val now = SystemClock.uptimeMillis()
        if (now - lastTripLog > TRIP_LOG_MS) {
            lastTripLog = now
            log("Trip: манёвр ${man?.type} cue=\"${text(step?.cue)}\" дорога=\"${text(step?.road)}\" до манёвра $meters м, loading=${t.isLoading}, " +
                "иконка ${runCatching { man?.icon?.icon?.bitmap?.let { "${it.width}x${it.height}" } }.getOrNull()}")
        }
        if (man == null || meters == null) return
        val road = text(step.road).ifEmpty { text(step.cue) }
        sink {
            it.onNavTurn(AaNavTurn(CarTrip.turnFor(man.type), road, "CARAPP/${man.type}", man.roundaboutExitNumber.takeIf { n -> n > 0 }, null))
            it.onNavDistance(AaNavDistance(meters, est.remainingTimeSeconds.toInt().takeIf { s -> s >= 0 },
                dest?.remainingDistance?.let { d -> CarTrip.meters(d.displayDistance, d.displayUnit) },
                text(t.currentRoad).ifEmpty { null }))
        }
    }

    private inline fun sink(block: (AaMetaSink) -> Unit) {
        val t = DesktopNavHints.target ?: return
        try { block(t) } catch (e: Throwable) { log("в мост не ушло: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun text(t: CarText?): String = t?.toCharSequence()?.toString().orEmpty()

    // --- то, что приложение зовёт у хоста (binder-потоки -> h) ---

    private val host = object : ICarHost.Stub() {
        override fun startCarApp(intent: Intent?) { log("startCarApp $intent - не поддерживаем") }
        override fun getHost(type: String?): IBinder? = when (type) {
            "app" -> appHost
            "navigation" -> navHost
            else -> null
        }
        override fun finish() {
            h.post { if (!stopped) { log("приложение закрывает хост (finish)"); refuse(CarAppRefusal.FINISHED) } }
        }
    }

    private val appHost = object : IAppHost.Stub() {
        override fun invalidate() { h.post { if (!stopped) pullTemplate(gen) } }
        override fun showToast(text: CharSequence?, duration: Int) {
            if (text != null) h.post { if (!stopped) events.onToast(this@CarAppSession, text) }
        }
        override fun setSurfaceCallback(cb: ISurfaceCallback?) {
            h.post {
                if (stopped) return@post
                surfaceCb = cb; delivered = null; sentVisible = null; sentStable = null
                deliverSurface()
            }
        }
        override fun sendLocation(location: android.location.Location?) {}
        override fun showAlert(alert: Bundleable?) { log("showAlert - пока не рисуем") }
        override fun dismissAlert(id: Int) {}
        override fun openMicrophone(request: Bundleable?): Bundleable? = null
    }

    private val navHost = object : INavigationHost.Stub() {
        override fun navigationStarted() {
            h.post { if (!stopped) { log("navigationStarted"); navigating = true; sink { it.onNavStatus(1) } } }
        }
        override fun navigationEnded() {
            h.post { if (!stopped) { log("navigationEnded"); endNavigation() } }
        }
        override fun updateTrip(b: Bundleable?) {
            val trip = runCatching { b?.get() as? Trip }.onFailure { log("Trip не разобран: ${it.javaClass.simpleName}") }.getOrNull() ?: return
            h.post { if (!stopped) onTrip(trip) }
        }
    }

    // --- ответы приложения ---

    private fun failText(r: Bundleable?): String = runCatching {
        val f = r?.get()
        if (f is FailureResponse) "type=${f.errorType} ${f.stackTrace.lineSequence().take(3).joinToString(" | ")}" else f.toString()
    }.getOrNull() ?: "?"

    /** Ответ на шаг: дальше - на потоке h и только если подключение то же ([g]). */
    private fun callback(g: Int, what: String, quiet: Boolean = false, onFail: (() -> Unit)? = null, next: (Bundleable?) -> Unit) =
        object : IOnDoneCallback.Stub() {
            override fun onSuccess(r: Bundleable?) {
                if (!quiet) log("$what: ок")
                h.post { if (g == gen && !stopped) runCatching { next(r) }.onFailure { log("после $what: ${it.javaClass.simpleName}: ${it.message}") } }
            }
            override fun onFailure(r: Bundleable?) {
                log("$what: отказ ${failText(r)}")
                if (onFail != null) h.post { if (g == gen && !stopped) onFail() }
            }
        }

    private fun done(what: String) = object : IOnDoneCallback.Stub() {
        override fun onSuccess(r: Bundleable?) {}
        override fun onFailure(r: Bundleable?) { log("$what: отказ ${failText(r)}") }
    }

    private companion object {
        val TEMPLATE_AFTER_INTENT_MS = longArrayOf(150L, 600L, 1500L)
        const val APP_INFO_TIMEOUT_MS = 5_000L
        const val READY_TIMEOUT_MS = 15_000L
        const val DEATH_WINDOW_MS = 60_000L
        const val MAX_DEATHS = 3
        const val TRIP_LOG_MS = 3_000L
    }
}
