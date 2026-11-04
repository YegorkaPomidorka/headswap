package com.yegorkapomidorka.headswap.carapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.Pane
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.serialization.Bundleable
import androidx.core.graphics.drawable.IconCompat
import com.yegorkapomidorka.headswap.AppLog

/**
 * Шаблон Car App Library -> [CarUi]. Только поток хоста: клики и колбэки уходят приложению через его делегаты,
 * [post] перекладывает их на поток хоста. Неизвестные шаблоны - [CarUi.Unknown] (карта без наших элементов).
 */
internal class CarUiMapper(private val app: Context, private val units: CarUnits, private val post: (() -> Unit) -> Unit) {

    fun map(t: Template?): CarUi? = when (t) {
        null -> null
        is NavigationTemplate -> nav(t)
        is PlaceListNavigationTemplate -> panel(title(t.header, t.title), back(t.header, t.headerAction), t.itemList, emptyList(),
            t.actionStrip?.actions, t.mapActionStrip?.actions, t.isLoading)
        // «В путь» обзора маршрута - всегда главная кнопка, как у AA (2ГИС не ставит ей FLAG_PRIMARY)
        is RoutePreviewNavigationTemplate -> panel(title(t.header, t.title), back(t.header, t.headerAction), t.itemList,
            listOfNotNull(t.navigateAction), t.actionStrip?.actions, t.mapActionStrip?.actions, t.isLoading, primaryButtons = true)
        is MapTemplate -> mapTemplate(t)
        // кнопки шапки: вход в поле поиска (значок) и «История». Яндекс шлёт их дважды - в шапке (новые хосты) и в
        // actionStrip (старые), берём шапку, actionStrip - только без неё
        is GridTemplate -> CarUi.Grid(title(t.header, t.title), back(t.header, t.headerAction),
            t.singleList?.items.orEmpty().filterIsInstance<GridItem>().map(::gridItem), t.isLoading,
            (t.header?.endHeaderActions?.takeIf { it.isNotEmpty() } ?: t.actionStrip?.actions.orEmpty()).map(::action))
        is SearchTemplate -> search(t)
        is ListTemplate -> listTemplate(t, null, null)
        is MessageTemplate -> message(t, null, null)
        is MapWithContentTemplate -> mapWithContent(t)
        else -> CarUi.Unknown(t.javaClass.simpleName)
    }

    // --- шаблоны ---

    private fun nav(t: NavigationTemplate): CarUi.Nav {
        val ri = t.navigationInfo as? RoutingInfo
        val step = ri?.currentStep
        val man = step?.maneuver
        val maneuver = if (step == null || ri.isLoading) null else CarManeuver(
            icon = image(man?.icon), type = man?.type ?: 0,
            distance = ri.currentDistance?.let { CarFormat.distance(it.displayDistance, it.displayUnit, units) }.orEmpty(),
            road = text(step.road).ifEmpty { text(step.cue) }, roundaboutExit = man?.roundaboutExitNumber ?: 0,
        )
        val (actions, end) = CarActions.splitEnd(t.actionStrip?.actions.orEmpty(), { it.icon != null }, { text(it.title) })
        return CarUi.Nav(maneuver, eta(t.destinationTravelEstimate), ri?.isLoading == true, actions.map(::action), end?.let(::action),
            CarActions.visibleMapActions(t.mapActionStrip?.actions.orEmpty()) { it.type }.map(::action), tint(t.backgroundColor))
    }

    private fun mapTemplate(t: MapTemplate): CarUi.Panel {
        val pane: Pane? = t.pane
        return if (pane != null) {
            CarUi.Panel(title(t.header, null), back(t.header, null), pane.rows.map(::row), -1, null,
                pane.actions.map(::action), t.actionStrip?.actions.orEmpty().map(::action), emptyList(), pane.isLoading, "")
        } else panel(title(t.header, null), back(t.header, null), t.itemList, emptyList(), t.actionStrip?.actions, null, false)
    }

    private fun panel(
        title: String, back: CarAction?, list: ItemList?, buttons: List<Action>,
        actions: List<Action>?, mapActions: List<Action>?, loading: Boolean, primaryButtons: Boolean = false,
    ): CarUi.Panel {
        val sel = list?.onSelectedDelegate
        return CarUi.Panel(
            // selectedIndex без делегата выбора - просто 0 по умолчанию (Яндекс: подсвечивались «Мои места»)
            title, back, list?.items.orEmpty().filterIsInstance<Row>().map(::row), if (sel != null) list.selectedIndex else -1,
            sel?.let { d -> { i: Int -> post { log("выбор $i"); runCatching { d.sendSelected(i, done("выбор $i")) } } } },
            buttons.map { action(it, primaryButtons) }, actions.orEmpty().map(::action),
            CarActions.visibleMapActions(mapActions.orEmpty()) { it.type }.map(::action), loading, text(list?.noItemsMessage),
        )
    }

    /**
     * Карта приложения + контент поверх (Car App API 7). Яндекс Навигатор строит на нём главный экран («Мои места»,
     * «История поездок»), списки мест и выдачу категорий; кнопки (поиск, настройки) - в actionStrip, зум - в mapController.
     * Без него оверлей пустой и кнопок нет (стенд 04.11).
     */
    private fun mapWithContent(t: MapWithContentTemplate): CarUi? {
        val actions = t.actionStrip?.actions
        val mapActions = t.mapController?.mapActionStrip?.actions
        return when (val c = t.contentTemplate) {
            is ListTemplate -> listTemplate(c, actions, mapActions)
            is MessageTemplate -> message(c, actions, mapActions)
            else -> map(c)
        }
    }

    private fun listTemplate(t: ListTemplate, actions: List<Action>?, mapActions: List<Action>?): CarUi.Panel {
        val strip = actions ?: t.actionStrip?.actions
        val sections = t.sectionedLists
        if (t.singleList != null || sections.isEmpty()) {
            return panel(title(t.header, t.title), back(t.header, t.headerAction), t.singleList, emptyList(), strip, mapActions, t.isLoading)
        }
        val rows = sections.flatMap { it.itemList.items.filterIsInstance<Row>() }.map(::row)
        return CarUi.Panel(title(t.header, t.title), back(t.header, t.headerAction), rows, -1, null, emptyList(),
            strip.orEmpty().map(::action), CarActions.visibleMapActions(mapActions.orEmpty()) { it.type }.map(::action), t.isLoading, "")
    }

    /** Сообщение с кнопками (Яндекс без геолокации: «Доступ к местоположению», «В телефон»). */
    private fun message(t: MessageTemplate, actions: List<Action>?, mapActions: List<Action>?): CarUi.Panel {
        val msg = CarRow(text(t.message), emptyList(), image(t.icon), null, false, 0, null)
        return CarUi.Panel(title(t.header, t.title), back(t.header, t.headerAction), listOf(msg), -1, null,
            t.actions.map(::action), (actions ?: t.actionStrip?.actions).orEmpty().map(::action),
            CarActions.visibleMapActions(mapActions.orEmpty()) { it.type }.map(::action), t.isLoading, "")
    }

    private fun search(t: SearchTemplate): CarUi.Search {
        val d = t.searchCallbackDelegate
        return CarUi.Search(
            hint = t.searchHint.orEmpty(), initial = t.initialSearchText.orEmpty(), back = t.headerAction?.let(::action),
            rows = t.itemList?.items.orEmpty().filterIsInstance<Row>().map(::row), showKeyboard = t.isShowKeyboardByDefault,
            loading = t.isLoading,
            changed = { s -> post { runCatching { d.sendSearchTextChanged(s, done("ввод")) } } },
            submitted = { s -> post { runCatching { d.sendSearchSubmitted(s, done("поиск")) } } },
        )
    }

    // --- части ---

    private fun eta(e: TravelEstimate?): CarEta? {
        e ?: return null
        val at = e.arrivalTimeAtDestination
        return CarEta(
            arrival = at?.let { CarFormat.clock(it.timeSinceEpochMillis, it.zoneOffsetSeconds) }.orEmpty(),
            time = CarFormat.duration(e.remainingTimeSeconds, units),
            distance = e.remainingDistance?.let { CarFormat.distance(it.displayDistance, it.displayUnit, units) }.orEmpty(),
            timeColor = tint(e.remainingTimeColor),
        )
    }

    private fun action(a: Action) = action(a, false)

    private fun action(a: Action, primary: Boolean) = CarAction(
        title = text(a.title), icon = image(a.icon) ?: standardIcon(a.type), type = a.type,
        primary = primary || a.flags and Action.FLAG_PRIMARY != 0, enabled = a.isEnabled, background = tint(a.backgroundColor),
        click = click(a.onClickDelegate, "действие ${text(a.title).ifEmpty { a.type.toString() }}"),
    )

    /** BACK / APP_ICON без своей картинки - стандартная иконка хоста. */
    private fun standardIcon(type: Int): CarImage? = when (type) {
        CarActions.TYPE_BACK -> CarImage("std:back", CarIcon.TYPE_BACK, null) { null }
        CarActions.TYPE_APP_ICON -> CarImage("std:app", CarIcon.TYPE_APP_ICON, null) { null }
        else -> null
    }

    private fun row(r: Row) = CarRow(
        title = text(r.title), texts = r.texts.map(::text).filter { it.isNotBlank() }, image = image(r.image),
        toggle = r.toggle?.let { tg ->
            val d = tg.onCheckedChangeDelegate
            CarToggle(tg.isChecked, tg.isEnabled, d?.let { { v: Boolean ->
                post { log("переключатель ${text(r.title)} -> $v"); runCatching { it.sendCheckedChange(v, done("переключатель")) } }
            } })
        },
        browsable = r.isBrowsable, number = r.numericDecoration.coerceAtLeast(0),
        click = click(r.onClickDelegate, "строка ${text(r.title)}"),
    )

    private fun gridItem(g: GridItem) = CarGridItem(text(g.title), text(g.text), image(g.image), g.isLoading,
        click(g.onClickDelegate, "плитка ${text(g.title)}"))

    private fun title(h: Header?, fallback: CarText?): String = text(h?.title ?: fallback)
    private fun back(h: Header?, fallback: Action?): CarAction? = (h?.startHeaderAction ?: fallback)?.let(::action)

    private fun click(d: OnClickDelegate?, what: String): CarClick? = d?.let {
        CarClick { post { log("нажато: $what"); runCatching { it.sendClick(done(what)) }.onFailure { e -> log("$what: ${e.javaClass.simpleName}") } } }
    }

    private fun tint(c: CarColor?): CarTint? = c?.takeIf { it.type != CarColor.TYPE_DEFAULT }?.let { CarTint(it.type, it.color, it.colorDark) }

    /**
     * CarText с подставленными спанами: DistanceSpan/DurationSpan в CarText - заглушки поверх символа, сам текст
     * без них теряет «4,3 км  •  10 мин». CarIconSpan пропускаем.
     */
    fun text(t: CarText?): String {
        val cs = t?.toCharSequence() ?: return ""
        if (cs !is Spanned) return cs.toString()
        val spans = cs.getSpans(0, cs.length, Any::class.java)
            .mapNotNull { s ->
                val value = when (s) {
                    is DistanceSpan -> s.distance?.let { CarFormat.distance(it.displayDistance, it.displayUnit, units) }
                    is DurationSpan -> CarFormat.duration(s.durationSeconds, units)
                    is androidx.car.app.model.CarIconSpan -> ""
                    else -> null
                } ?: return@mapNotNull null
                Triple(cs.getSpanStart(s), cs.getSpanEnd(s), value)
            }
            .sortedBy { it.first }
        if (spans.isEmpty()) return cs.toString()
        val sb = StringBuilder()
        var at = 0
        for ((start, end, value) in spans) {
            if (start < at) continue
            sb.append(cs, at, start).append(value)
            at = end
        }
        sb.append(cs, at, cs.length)
        return sb.toString().trim()
    }

    /** Ключ кэша - по содержимому: шаблон с теми же иконками приходит заново каждую секунду. */
    private fun image(ic: CarIcon?): CarImage? {
        ic ?: return null
        val inner: IconCompat? = ic.icon
        val tintC = tint(ic.tint)
        if (inner == null) return if (ic.type != CarIcon.TYPE_CUSTOM) CarImage("std:${ic.type}", ic.type, tintC) { null } else null
        val key = when (inner.type) {
            IconCompat.TYPE_BITMAP, IconCompat.TYPE_ADAPTIVE_BITMAP ->
                runCatching { inner.bitmap?.let { "bmp:${it.width}x${it.height}:${pixelsHash(it)}" } }.getOrNull()
            IconCompat.TYPE_RESOURCE -> runCatching { "res:${inner.resPackage}:${inner.resId}" }.getOrNull()
            else -> null
        } ?: "obj:${System.identityHashCode(inner)}"
        return CarImage(key, 0, tintC) { render(inner) }
    }

    private fun pixelsHash(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.contentHashCode()
    }

    private fun render(inner: IconCompat): Bitmap? = runCatching {
        // картинку приложения - как есть: через Drawable она пересэмплируется по плотности и мылится
        if (inner.type == IconCompat.TYPE_BITMAP) inner.bitmap?.let { return it }
        val d = inner.loadDrawable(app) ?: return null
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, w, h); d.draw(Canvas(it)) }
    }.getOrNull()

    private fun done(what: String) = object : OnDoneCallback {
        override fun onSuccess(response: Bundleable?) {}
        override fun onFailure(response: Bundleable) { log("$what: отказ приложения") }
    }

    private fun log(line: String) = AppLog.i("CARAPP $line", false)
}
