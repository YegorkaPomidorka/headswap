package com.yegorkapomidorka.headswap.carapp

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.model.TemplateWrapper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * Headswap - хост Car App Library для навигатора рабочего стола: карта приложения рисуется прямо в поверхность окна
 * навигатора в его размер, шаблоны (манёвр, кнопки, списки) рисует лаунчер, маршрут из updateTrip уходит на ГУ
 * подсказками ([com.yegorkapomidorka.headswap.aa.DesktopNavHints]). Навигатор любой, лишь бы на Car App Library
 * ([CarAppCatalog]); отказ хоста или приложения - навигатор открывается обычным окном.
 * Одна сессия за раз, всё на своём потоке; вызовы - с любого потока.
 */
object CarAppHost {
    enum class Status { IDLE, CONNECTING, READY, REFUSED }

    private val thread = HandlerThread("car-app-host").apply { start() }
    private val h = Handler(thread.looper)
    private lateinit var app: Context
    private var session: CarAppSession? = null

    /** Пакет навигатора, с которым работает хост; null - хост выключен. */
    @Volatile var pkg: String? = null
        private set
    @Volatile var status = Status.IDLE
        private set
    /** Последний шаблон приложения (рисует лаунчер); null - ещё нет или сессия оборвалась. */
    @Volatile var template: TemplateWrapper? = null
        private set
    @Volatile var toast: CharSequence? = null
        private set
    /** Последний шаблон для отрисовки лаунчером; null - шаблона нет. */
    @Volatile var ui: CarUi? = null
        private set
    private var mapper: CarUiMapper? = null

    /** Изменились status / template / toast. Зовётся на потоке хоста. */
    @Volatile var onChange: (() -> Unit)? = null
    /** Навигатор отказал (пакет): показать его окном. Зовётся на потоке хоста. */
    @Volatile var onRefused: ((String) -> Unit)? = null

    // что отдаём приложению; только поток h
    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
    private var scale = 1f
    /**
     * Ночь для навигатора; null - UI_MODE_NIGHT_UNDEFINED: день/ночь решает сам навигатор по своим настройкам (Егор 04.11:
     * фар в CarLife нет, CHANGE_MD_THEME ГУ не шлют). Стенд 04.11: 2ГИС с темой «авто» при UNDEFINED берёт тему телефона.
     */
    private var night: Boolean? = null
    private var areas: Triple<Int, Int, Int>? = null

    /** cmd car_host_off / car_host: навигаторы на Car App Library - обычным окном (сравнить на стенде). */
    @Volatile var enabled = true

    /** «Подсказки на приборке» навигатора (настройка лаунчера, h800): false - этот пакет окном, как на телефоне. */
    @Volatile var allowFor: (String) -> Boolean = { true }

    /** cmd car_dump: писать шаблоны целиком в лог (для разбора новых навигаторов). */
    @Volatile var dumpTemplates = false

    private fun log(line: String) = AppLog.i("CARAPP $line", false)

    /** Поднять хост для [target]; тот же пакет уже поднят - ничего. Прежний навигатор закрывается полностью. */
    fun start(ctx: Context, target: CarAppTarget) {
        h.post {
            app = ctx.applicationContext
            val cur = session
            if (cur != null && cur.target.pkg == target.pkg) return@post
            cur?.stop()
            log("старт ${target.pkg}/${target.service}")
            pkg = target.pkg
            scaleFor?.let { scale = it(target.pkg) }
            template = null; toast = null; ui = null
            if (mapper == null) mapper = CarUiMapper(app, units(app)) { r -> h.post(r) }
            setStatus(Status.CONNECTING)
            val s = CarAppSession(app, target, h, events)
            session = s
            s.start()
            s.update(view())
        }
    }

    fun stop() {
        h.post {
            // после отказа сессии уже нет, но pkg и REFUSED надо снять
            session?.stop()
            session = null
            pkg = null; template = null; toast = null; ui = null
            setStatus(Status.IDLE)
        }
    }

    /** Поверхность окна навигатора в px; null - окна нет. dpi от ступени масштаба, не от дисплея (CarConfigMath.BASE_DPI). */
    fun setSurface(s: Surface?, w: Int, hgt: Int) {
        h.post { surface = s; sw = w; sh = hgt; push() }
    }

    /**
     * Что лаунчер рисует поверх карты, px поверхности: [leftPx] карточки/панель слева, [rightPx] полоса кнопок справа,
     * [topPx] полоса сверху. null - по умолчанию ([CarAreas.default]).
     */
    fun setAreas(leftPx: Int, rightPx: Int, topPx: Int) { h.post { areas = Triple(leftPx, rightPx, topPx); push() } }
    fun clearAreas() { h.post { areas = null; push() } }

    /**
     * Карточка на главном показывает вырезку [crop] полноэкранной поверхности 1:1; null - навигатор на весь экран.
     * Размер поверхности при этом не меняется: пересдача той же Surface с новым размером вешает 2ГИС (его заставка
     * берёт lockCanvas, пока движок в другом процессе ещё держит на ней EGL; стенд 04.11).
     */
    fun setCrop(crop: Box?) { h.post { if (this.crop != crop) { this.crop = crop; push() } } }
    private var crop: Box? = null

    /** cmd car_night: true/false - ночь/день от хоста, null - как в настройках навигатора. */
    fun setNight(value: Boolean?) { h.post { if (night != value) { night = value; push() } } }

    /** Множитель «Масштаба карты» (1 = «Авто»). */
    fun setScale(value: Float) { h.post { if (scale != value) { scale = value; push() } } }
    /** Сохранённый «Масштаб карты» навигатора (пакет -> множитель); берётся при старте сессии. */
    @Volatile var scaleFor: ((String) -> Float)? = null

    /** Касание окна навигатора, координаты уже в поверхности. */
    fun touch(action: Int, x: Float, y: Float) { h.post { session?.touch(action, x, y) } }

    /** «Назад» хоста: приложение само решает, что закрыть. */
    fun back() { h.post { session?.back() } }

    /** Intent навигатору хоста (маршрут от Алисы); хост не поднят - false, и intent не ушёл. */
    fun newIntent(i: android.content.Intent): Boolean {
        if (pkg == null) return false
        h.post { session?.newIntent(i) ?: log("onNewIntent: сессии нет") }
        return true
    }

    /** cmd car_click --ei i N: нажать N-й кликабельный элемент текущего шаблона. */
    fun click(i: Int) { h.post { CarTemplateDump.click(i) } }

    private fun push() { session?.update(view()) }

    private fun units(c: Context) = CarUnits(
        c.getString(R.string.l_u_m), c.getString(R.string.l_u_km), c.getString(R.string.l_u_ft), c.getString(R.string.l_u_mi),
        c.getString(R.string.l_u_yd), c.getString(R.string.l_u_min), c.getString(R.string.l_u_h),
    )

    private fun view(): CarAppSession.View {
        val c = crop
        val (visible, stable) = when {
            c != null -> CarAreas.of(c, 0, 0, 0) // в карточке поверх карты ничего своего
            else -> areas?.let { (l, r, t) -> CarAreas.of(sw, sh, l, r, t) } ?: CarAreas.default(sw, sh)
        }
        return CarAppSession.View(surface, sw, sh, CarConfigMath.dpi(scale), night, visible, stable)
    }

    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        onChange?.invoke()
    }

    private val events = object : CarAppSession.Events {
        override fun onReady(s: CarAppSession) { if (s === session) setStatus(Status.READY) }

        override fun onRefused(s: CarAppSession, why: CarAppRefusal) {
            if (s !== session) return
            CarAppCatalog.verdicts.refuse(s.target, why)
            session = null
            s.stop()
            template = null; ui = null
            setStatus(Status.REFUSED)
            onRefused?.invoke(s.target.pkg)
        }

        override fun onTemplate(s: CarAppSession, t: TemplateWrapper?) {
            if (s !== session) return
            template = t
            ui = runCatching { mapper?.map(t?.template) }.onFailure { log("шаблон не разобран: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            // рефлексия по всему дереву - только для разбора на стенде, NavigationTemplate приходит каждую секунду
            if (dumpTemplates) CarTemplateDump.collect(t, ::log)
            onChange?.invoke()
        }

        override fun onToast(s: CarAppSession, text: CharSequence) {
            if (s !== session) return
            log("тост: $text")
            toast = text
            onChange?.invoke()
        }
    }
}
