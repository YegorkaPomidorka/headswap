package com.yegorkapomidorka.headswap.aa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import androidx.car.app.AppInfo
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Trip
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * ПРОБНИК (не для релиза): Headswap сам - хост Car App Library для навигатора (2ГИС пускает любой хост, HostValidator
 * allowAll). Карта приложения рисуется прямо в поверхность окна навигатора рабочего стола в его размер, маршрут
 * приходит в updateTrip и уходит в мост как подсказки AA ([AaShadowNav.target]). Шаблоны (карточка манёвра, кнопки)
 * пока не рисуем - только пишем в лог.
 * Порядок как у хоста AA: bind -> getAppInfo -> onHandshakeCompleted -> onAppCreate -> onAppStart -> onAppResume ->
 * getManager("app") -> getTemplate; поверхность - через ISurfaceCallback, который приложение отдаёт в setSurfaceCallback.
 * Команды: am start ... --es cmd car_host / car_host_off.
 */
object CarHostProbe {
    private val TARGET = ComponentName("ru.dublgis.dgismobile", "ru.dublgis.car.CarService")

    @Volatile var active = false
        private set

    private val thread = HandlerThread("car-host-probe").apply { start() }
    private val h = Handler(thread.looper)
    private lateinit var app: Context
    private var carApp: ICarApp? = null
    private var appManager: IAppManager? = null
    @Volatile private var surfaceCb: ISurfaceCallback? = null
    private var conn: ServiceConnection? = null

    // поверхность окна навигатора (EmbedHost); только поток h
    private var surface: Surface? = null
    private var sw = 0
    private var shh = 0
    private var sdpi = 160
    private var delivered: Surface? = null

    private fun log(line: String) = AppLog.i("CARHOST $line", false)

    fun start(ctx: Context) {
        if (active) { log("уже запущен"); return }
        active = true
        app = ctx.applicationContext
        h.post { bind() }
    }

    fun stop() {
        if (!active) return
        active = false
        h.post {
            val a = carApp
            // Сначала забрать поверхность: иначе процесс 2ГИС переживает отключение и рисует в мёртвую поверхность
            // (drawSurface IllegalArgumentException), а новая сессия хоста висит на заставке.
            val old = delivered
            if (old != null) runCatching { surfaceCb?.onSurfaceDestroyed(Bundleable.create(SurfaceContainer(old, sw, shh, sdpi)), done("onSurfaceDestroyed")) }
            if (a != null) {
                runCatching { a.onAppPause(done("onAppPause")) }
                runCatching { a.onAppStop(done("onAppStop")) }
            }
            conn?.let { runCatching { app.unbindService(it) } }
            conn = null; carApp = null; appManager = null; surfaceCb = null; delivered = null
            log("остановлен")
        }
    }

    /** Поверхность окна навигатора, размер в px; null - окна нет. С EmbedHost (его рабочий поток). */
    fun setSurface(s: Surface?, w: Int, hgt: Int, dpi: Int) {
        h.post {
            val old = delivered
            if (old != null && old === s && (w != sw || hgt != shh)) {
                // тот же Surface с новым размером (карточка <-> весь экран): приложению - как новая поверхность
                runCatching { surfaceCb?.onSurfaceDestroyed(Bundleable.create(SurfaceContainer(old, sw, shh, sdpi)), done("onSurfaceDestroyed")) }
                delivered = null
            }
            surface = s; sw = w; shh = hgt; sdpi = dpi
            deliverSurface()
        }
    }

    /** Касание окна навигатора, координаты уже в поверхности. Тап = onClick, протяжка = onScroll. */
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    fun touch(action: Int, x: Float, y: Float) {
        val cb = surfaceCb ?: return
        try {
            when (action) {
                0 -> { downX = x; downY = y; lastX = x; lastY = y; dragging = false }
                2 -> {
                    if (!dragging && (kotlin.math.abs(x - downX) > 12 || kotlin.math.abs(y - downY) > 12)) dragging = true
                    if (dragging) { cb.onScroll(lastX - x, lastY - y); lastX = x; lastY = y }
                }
                1 -> if (!dragging) { log("тап ${x.roundToInt()},${y.roundToInt()}"); cb.onClick(x, y) }
            }
        } catch (e: Exception) { log("касание не ушло: ${e.javaClass.simpleName}") }
    }

    // --- поток h ---

    private fun bind() {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                log("подключён к $name")
                h.post { handshake(ICarApp.Stub.asInterface(binder)) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                log("отключён от $name")
                carApp = null; surfaceCb = null; delivered = null
            }
        }
        val intent = Intent("androidx.car.app.CarAppService").setComponent(TARGET)
        val ok = try { app.bindService(intent, c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) } catch (e: Exception) {
            log("bindService упал: ${e.javaClass.simpleName}: ${e.message}"); false
        }
        log("bindService $TARGET -> $ok")
        if (ok) conn = c else active = false
    }

    private fun handshake(a: ICarApp) {
        carApp = a
        try {
            val latch = CountDownLatch(1)
            var info: AppInfo? = null
            a.getAppInfo(object : IOnDoneCallback.Stub() {
                override fun onSuccess(r: Bundleable?) { info = runCatching { r?.get() as AppInfo }.getOrNull(); latch.countDown() }
                override fun onFailure(r: Bundleable?) { log("getAppInfo: отказ"); latch.countDown() }
            })
            latch.await(5, TimeUnit.SECONDS)
            val i = info
            val level = minOf(i?.latestCarAppApiLevel ?: 4, CarAppApiLevels.getLatest())
            log("AppInfo: min=${i?.minCarAppApiLevel} latest=${i?.latestCarAppApiLevel} lib=${i?.libraryDisplayVersion}; беру API $level")
            a.onHandshakeCompleted(Bundleable.create(HandshakeInfo(app.packageName, level)), done("handshake"))
            // следующий шаг жизненного цикла - только после ответа на предыдущий: иначе «Lifecycle is not at least created»
            a.onAppCreate(host, Intent(), carConfig(), then("onAppCreate") {
                a.onAppStart(then("onAppStart") { a.onAppResume(done("onAppResume")) })
            })
            a.getManager("app", object : IOnDoneCallback.Stub() {
                override fun onSuccess(r: Bundleable?) {
                    val b = runCatching { r?.get() }.getOrNull()
                    appManager = (b as? IAppManager) ?: (b as? IBinder)?.let { IAppManager.Stub.asInterface(it) }
                    log("AppManager: ${if (appManager != null) "есть" else "нет (${b?.javaClass?.name})"}")
                    h.post { pullTemplate() }
                }
                override fun onFailure(r: Bundleable?) { log("getManager(app): отказ ${failText(r)}") }
            })
        } catch (e: Exception) {
            log("рукопожатие упало: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun carConfig(): Configuration = Configuration(app.resources.configuration).apply {
        val dpi = sdpi.takeIf { it > 0 } ?: 160
        if (sw > 0 && shh > 0) {
            screenWidthDp = sw * 160 / dpi
            screenHeightDp = shh * 160 / dpi
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        }
        densityDpi = dpi
        // День/ночь навигатору - явно, не от темы Headswap (иначе 2ГИС днём тёмный). Пробник: всегда день;
        // в фиче - по теме лаунчера / фарам машины.
        uiMode = (uiMode and (Configuration.UI_MODE_TYPE_MASK or Configuration.UI_MODE_NIGHT_MASK).inv()) or
            Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO
    }

    private fun deliverSurface() {
        val cb = surfaceCb ?: return
        val s = surface
        try {
            val old = delivered
            if (old != null && (old !== s || s == null)) {
                cb.onSurfaceDestroyed(Bundleable.create(SurfaceContainer(old, sw, shh, sdpi)), done("onSurfaceDestroyed"))
                delivered = null
            }
            if (s != null && s.isValid && delivered !== s) {
                cb.onSurfaceAvailable(Bundleable.create(SurfaceContainer(s, sw, shh, sdpi)), done("onSurfaceAvailable"))
                // Как у AA: visible - карта без наших карточек слева, stable - ещё без полос кнопок. Если области равны,
                // 2ГИС (libv4android, calculatePosition) прячет спидометр с камерами и мини-карту.
                val visible = Rect((sw * 0.36f).roundToInt(), 0, sw, shh)
                val stable = Rect(visible.left, (shh * 0.15f).roundToInt(), sw - (sw * 0.1f).roundToInt(), shh)
                cb.onVisibleAreaChanged(visible, done("onVisibleAreaChanged"))
                cb.onStableAreaChanged(stable, done("onStableAreaChanged"))
                delivered = s
                log("поверхность ${sw}x$shh dpi=$sdpi отдана")
            }
        } catch (e: Exception) {
            log("поверхность не отдана: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pullTemplate() {
        val m = appManager ?: return
        try {
            m.getTemplate(object : IOnDoneCallback.Stub() {
                override fun onSuccess(r: Bundleable?) {
                    val w = runCatching { r?.get() as? TemplateWrapper }.getOrNull()
                    log("шаблон: ${describe(w)}")
                    h.post { dumpTemplate(w) }
                }
                override fun onFailure(r: Bundleable?) { log("getTemplate: отказ ${failText(r)}") }
            })
        } catch (e: Exception) { log("getTemplate упал: ${e.javaClass.simpleName}") }
    }

    private fun describe(w: TemplateWrapper?): String {
        val t = w?.template ?: return "null"
        val base = t.javaClass.simpleName
        if (t is NavigationTemplate) {
            val ri = t.navigationInfo as? RoutingInfo
            val step = ri?.currentStep
            return "$base routing: ${step?.maneuver?.type} \"${text(step?.cue)}\" дорога \"${text(step?.road)}\" " +
                "${ri?.currentDistance?.let { meters(it) }} м, loading=${ri?.isLoading}"
        }
        return base
    }

    // --- дамп шаблона целиком (поля моделей рефлексией) и клики по нему; только поток h ---

    private val clicks = mutableListOf<androidx.car.app.model.OnClickDelegate>()
    private var lastDump = ""

    private fun dumpTemplate(w: TemplateWrapper?) {
        val t = w?.template ?: return
        val found = mutableListOf<androidx.car.app.model.OnClickDelegate>()
        val sb = StringBuilder()
        dumpObj(t, 0, sb, found, java.util.IdentityHashMap())
        val s = sb.toString()
        clicks.clear(); clicks.addAll(found)
        // NavigationTemplate перерисовывается каждые секунды: пишем только изменения без метров/секунд
        val key = s.replace(Regex("\\d+"), "#")
        if (key == lastDump) return
        lastDump = key
        log("дамп ${t.javaClass.simpleName} (кликов ${found.size}):")
        s.lineSequence().filter { it.isNotBlank() }.forEach { log("  $it") }
    }

    private fun dumpObj(o: Any?, depth: Int, sb: StringBuilder, found: MutableList<androidx.car.app.model.OnClickDelegate>,
                        seen: java.util.IdentityHashMap<Any, Boolean>) {
        val pad = "  ".repeat(depth)
        if (o == null || depth > 9 || seen.put(o, true) != null) return
        for (c in generateSequence<Class<*>>(o.javaClass) { it.superclass }.takeWhile { it != Any::class.java }) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull() ?: continue
                val name = f.name.removePrefix("m").replaceFirstChar { it.lowercase() }
                val leaf = leafText(v, found)
                when {
                    leaf != null -> if (leaf.isNotEmpty()) sb.append(pad).append(name).append(" = ").append(leaf).append('\n')
                    v is Collection<*> -> {
                        if (v.isEmpty()) continue
                        sb.append(pad).append(name).append(" [").append(v.size).append("]\n")
                        v.forEachIndexed { i, e ->
                            val l = leafText(e, found)
                            if (l != null) sb.append(pad).append("  #").append(i).append(' ').append(l).append('\n')
                            else {
                                sb.append(pad).append("  #").append(i).append(' ').append(e?.javaClass?.simpleName).append('\n')
                                dumpObj(e, depth + 2, sb, found, seen)
                            }
                        }
                    }
                    v.javaClass.name.startsWith("androidx.car.app.") -> {
                        sb.append(pad).append(name).append(": ").append(v.javaClass.simpleName).append('\n')
                        dumpObj(v, depth + 1, sb, found, seen)
                    }
                }
            }
        }
    }

    /** Значение-лист одной строкой; "" - пропустить; null - не лист, раскрывать дальше. */
    private fun leafText(v: Any?, found: MutableList<androidx.car.app.model.OnClickDelegate>): String? = when (v) {
        null -> ""
        is String -> "\"$v\""
        is Number, is Boolean, is Enum<*> -> v.toString()
        is CarText -> "\"${text(v)}\""
        is CharSequence -> "\"$v\""
        is Distance -> "${v.displayDistance} unit${v.displayUnit}"
        is androidx.car.app.model.CarColor -> "color${v.type}"
        is androidx.car.app.model.CarIcon -> "icon(type ${v.type}${saveIcon(v)})"
        is androidx.car.app.model.OnClickDelegate -> { found.add(v); "КЛИК #${found.size - 1}" }
        is IBinder, is android.os.IInterface -> ""
        else -> if (v.javaClass.simpleName.contains("Delegate")) "делегат ${v.javaClass.simpleName}" else null
    }

    /** Своя иконка приложения -> files/carhost/icon_<хеш>.png (для разбора кнопок); в дамп - имя файла. */
    private fun saveIcon(ic: androidx.car.app.model.CarIcon): String = runCatching {
        val d = ic.icon?.loadDrawable(app) ?: return ""
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val hh = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, hh); d.draw(android.graphics.Canvas(bmp))
        val px = IntArray(w * hh).also { bmp.getPixels(it, 0, w, 0, 0, w, hh) }
        val name = "icon_%08x.png".format(px.contentHashCode())
        val dir = java.io.File(app.filesDir, "carhost").apply { mkdirs() }
        val f = java.io.File(dir, name)
        if (!f.exists()) f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        " $name ${w}x$hh"
    }.getOrElse { " иконка не сохранена: ${it.javaClass.simpleName}" }

    /** cmd car_click --ei i N: нажать N-й кликабельный элемент последнего дампа. */
    fun click(i: Int) {
        h.post {
            val d = clicks.getOrNull(i)
            if (d == null) { log("клик #$i: нет такого (всего ${clicks.size})"); return@post }
            log("клик #$i")
            runCatching {
                d.sendClick(object : androidx.car.app.OnDoneCallback {
                    override fun onSuccess(response: Bundleable?) { log("клик #$i: ок") }
                    override fun onFailure(response: Bundleable) { log("клик #$i: отказ ${failText(response)}") }
                })
            }.onFailure { log("клик #$i упал: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** cmd car_back: кнопка «Назад» хоста. */
    fun back() {
        h.post {
            val m = appManager ?: return@post
            runCatching { m.onBackPressed(done("onBackPressed")) }.onFailure { log("назад упал: ${it.javaClass.simpleName}") }
        }
    }

    private val host = object : ICarHost.Stub() {
        override fun startCarApp(intent: Intent?) { log("startCarApp $intent") }
        override fun getHost(type: String?): IBinder? {
            log("getHost($type)")
            return when (type) {
                "app" -> appHost
                "navigation" -> navHost
                else -> null
            }
        }
        override fun finish() { log("приложение просит закрыть хост (finish)") }
    }

    private val appHost = object : IAppHost.Stub() {
        override fun invalidate() { h.post { pullTemplate() } }
        override fun showToast(text: CharSequence?, duration: Int) { log("тост: $text") }
        override fun setSurfaceCallback(cb: ISurfaceCallback?) {
            log("setSurfaceCallback ${cb != null}")
            h.post { surfaceCb = cb; delivered = null; deliverSurface() }
        }
        override fun sendLocation(location: android.location.Location?) {}
        override fun showAlert(alert: Bundleable?) { log("showAlert") }
        override fun dismissAlert(id: Int) {}
        override fun openMicrophone(request: Bundleable?): Bundleable? { log("openMicrophone - нет"); return null }
    }

    private val navHost = object : INavigationHost.Stub() {
        override fun navigationStarted() { log("navigationStarted"); sink { it.onNavStatus(1) } }
        override fun navigationEnded() { log("navigationEnded"); sink { it.onNavStatus(0) } }
        override fun updateTrip(b: Bundleable?) {
            val trip = runCatching { b?.get() as? Trip }.onFailure { log("Trip не разобран: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() ?: return
            onTrip(trip)
        }
    }

    private var lastLog = 0L

    private fun onTrip(t: Trip) {
        val step = t.steps.firstOrNull()
        val est = t.stepTravelEstimates.firstOrNull()
        val dest = t.destinationTravelEstimates.firstOrNull()
        val man = step?.maneuver
        val meters = est?.remainingDistance?.let { meters(it) }
        val now = System.currentTimeMillis()
        if (now - lastLog > 3000) {
            lastLog = now
            log("Trip: манёвр ${man?.type} exit=${man?.roundaboutExitNumber} cue=\"${text(step?.cue)}\" дорога=\"${text(step?.road)}\" " +
                "до манёвра ${meters} м ${est?.remainingTimeSeconds} с, до цели ${dest?.remainingDistance?.let { meters(it) }} м, " +
                "текущая \"${text(t.currentRoad)}\" loading=${t.isLoading}")
        }
        if (man == null || meters == null) return
        val road = text(step.road).ifEmpty { text(step.cue) }
        val exit = man.roundaboutExitNumber.takeIf { it > 0 }
        sink {
            it.onNavTurn(AaNavTurn(turnFor(man.type), road, "CARAPP/${man.type}", exit, null))
            it.onNavDistance(AaNavDistance(meters, est.remainingTimeSeconds.toInt().takeIf { s -> s >= 0 },
                dest?.remainingDistance?.let { d -> meters(d) }, text(t.currentRoad).ifEmpty { null }))
        }
    }

    private inline fun sink(block: (AaMetaSink) -> Unit) {
        val t = AaShadowNav.target ?: return
        try { block(t) } catch (e: Throwable) { log("в мост не ушло: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Maneuver Car App Library -> nextTurn CarLife (та же таблица, что для AA 0x8006). */
    private fun turnFor(type: Int): Int = when (type) {
        Maneuver.TYPE_DEPART -> AaMeta.T_START
        Maneuver.TYPE_KEEP_LEFT -> AaMeta.T_BRANCH_LEFT_STRAIGHT
        Maneuver.TYPE_KEEP_RIGHT -> AaMeta.T_BRANCH_RIGHT_STRAIGHT
        Maneuver.TYPE_TURN_SLIGHT_LEFT -> AaMeta.T_LEFT_FRONT
        Maneuver.TYPE_TURN_SLIGHT_RIGHT -> AaMeta.T_RIGHT_FRONT
        Maneuver.TYPE_TURN_NORMAL_LEFT -> AaMeta.T_LEFT
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> AaMeta.T_RIGHT
        Maneuver.TYPE_TURN_SHARP_LEFT -> AaMeta.T_LEFT_BACK
        Maneuver.TYPE_TURN_SHARP_RIGHT -> AaMeta.T_RIGHT_BACK
        Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT,
        Maneuver.TYPE_ON_RAMP_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> AaMeta.T_BACK
        Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT -> AaMeta.T_LEFT_SIDE_IC
        Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT -> AaMeta.T_RIGHT_SIDE_IC
        Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> AaMeta.T_LEFT_SIDE
        Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> AaMeta.T_RIGHT_SIDE
        Maneuver.TYPE_FORK_LEFT -> AaMeta.T_BRANCH_LEFT
        Maneuver.TYPE_FORK_RIGHT -> AaMeta.T_BRANCH_RIGHT
        Maneuver.TYPE_MERGE_LEFT -> AaMeta.T_LEFT_SIDE_MAIN
        Maneuver.TYPE_MERGE_RIGHT -> AaMeta.T_RIGHT_SIDE_MAIN
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> AaMeta.T_RING
        Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW -> AaMeta.T_RING_OUT
        Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT,
        Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> AaMeta.T_IN_FERRY
        Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT, Maneuver.TYPE_DESTINATION_LEFT,
        Maneuver.TYPE_DESTINATION_RIGHT -> AaMeta.T_DEST
        else -> AaMeta.T_FRONT
    }

    private fun meters(d: Distance): Int {
        val v = d.displayDistance
        return when (d.displayUnit) {
            Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> v * 1000
            Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> v * 1609.344
            Distance.UNIT_FEET -> v * 0.3048
            Distance.UNIT_YARDS -> v * 0.9144
            else -> v
        }.roundToInt()
    }

    private fun text(t: CarText?): String = t?.toCharSequence()?.toString().orEmpty()

    private fun failText(r: Bundleable?): String = runCatching {
        val f = r?.get()
        if (f is androidx.car.app.FailureResponse) "type=${f.errorType} ${f.stackTrace.lineSequence().take(4).joinToString(" | ")}" else f.toString()
    }.getOrNull() ?: "?"

    private fun then(what: String, next: () -> Unit) = object : IOnDoneCallback.Stub() {
        override fun onSuccess(r: Bundleable?) { log("$what: ок"); h.post { runCatching(next).onFailure { log("после $what: ${it.javaClass.simpleName}") } } }
        override fun onFailure(r: Bundleable?) { log("$what: отказ ${failText(r)}") }
    }

    private fun done(what: String) = object : IOnDoneCallback.Stub() {
        override fun onSuccess(r: Bundleable?) { if (what != "onVisibleAreaChanged" && what != "onStableAreaChanged") log("$what: ок") }
        override fun onFailure(r: Bundleable?) { log("$what: отказ ${failText(r)}") }
    }
}
