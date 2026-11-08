package com.yegorkapomidorka.headswap.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.carapp.CarAction
import com.yegorkapomidorka.headswap.carapp.CarActions
import com.yegorkapomidorka.headswap.carapp.CarAppHost
import com.yegorkapomidorka.headswap.carapp.CarEta
import com.yegorkapomidorka.headswap.carapp.CarImage
import com.yegorkapomidorka.headswap.carapp.CarManeuver
import com.yegorkapomidorka.headswap.carapp.CarRow
import com.yegorkapomidorka.headswap.carapp.CarUi
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Шаблоны навигатора на своём хосте Car App поверх его карты (NavHost.dc.html, утверждён 04.11): манёвр слева вверху,
 * прибытие с крестиком «Завершить» слева внизу, действия справа вверху, кнопки карты справа внизу; списки и карточки -
 * панелью слева; сетка и поиск - на всё окно. Стиль Headswap (золото); «как в приложении» - позже, в настройках.
 */

/** Цвета навигатора из макета (LOOKS.headswap). */
private class NavLook(
    val fg: Color, val fg2: Color, val fg3: Color, val card: Color, val maneuverIc: Color, val eta: Color,
    val btn: Color, val btn2: Color, val endFg: Color, val divider: Color, val sel: Color, val tile: Color,
    val gridIcBg: Color, val fullBg: Color,
    /** Акцент: выбранный номер, переключатель, главная кнопка, курсор. */
    val acc: Color = Pal.GOLD, val onAcc: Color = Pal.ON_GOLD,
) {
    /** Свой акцент из «Вид › Цвета»: золотые места макета перекрашиваются им. */
    fun withAccent(a: Color): NavLook = if (a == Pal.GOLD) this else NavLook(
        fg, fg2, fg3, card, maneuverIc = a, eta = a, btn = btn, btn2 = btn2, endFg = endFg, divider = divider,
        sel = a.copy(alpha = sel.alpha), tile = tile, gridIcBg = a.copy(alpha = gridIcBg.alpha), fullBg = fullBg,
        acc = a, onAcc = Pal.onAccent(a),
    )
}

private val DARK = NavLook(
    fg = Color(0xFFF2F5F7), fg2 = Color(0xFFC8D1F0), fg3 = Color(0xFFA9B4D6), card = Color(0xEB0E141A),
    maneuverIc = Color(0xFFD4AF37), eta = Color(0xFFE8C65A), btn = Color(0xE00E141A), btn2 = Color(0x1FFFFFFF),
    endFg = Color(0xFFFFB4A9), divider = Color(0x14FFFFFF), sel = Color(0x29D4AF37), tile = Color(0x12FFFFFF),
    gridIcBg = Color(0x2ED4AF37), fullBg = Color(0xFF0E141A),
)

private val LIGHT = NavLook(
    fg = Color(0xFF1E1A10), fg2 = Color(0xFF5C5340), fg3 = Color(0xFF6E6450), card = Color(0xF5FBF7EC),
    maneuverIc = Color(0xFF9A7A12), eta = Color(0xFF7A5F0C), btn = Color(0xF5FBF7EC), btn2 = Color(0x1F5A4614),
    endFg = Color(0xFFB3361F), divider = Color(0x1F5A4614), sel = Color(0x38D4AF37), tile = Color(0x145A4614),
    gridIcBg = Color(0x40D4AF37), fullBg = Color(0xFFF6F0E0),
)

/** Размеры макета: 1920×720 (wide) и 1280×720, дальше по высоте экрана. */
private class NavDims(private val k: Float, wide: Boolean) {
    private fun px(v: Int) = (v * k).roundToInt()
    val gap = px(21)
    val btn = px(90)
    val btnIc = px(44)
    val card = px(if (wide) 560 else 440)
    val eta = px(if (wide) 660 else 540)
    val cardPadH = px(if (wide) 28 else 22)
    val cardPadV = px(if (wide) 24 else 18)
    val manIc = px(if (wide) 110 else 88)
    val dist = px(if (wide) 64 else 54)
    val road = px(if (wide) 34 else 30)
    val end = px(84)
    val panel = px(if (wide) 780 else 560)
    val header = px(110)
    val title = px(36)
    val rowTitle = px(34)
    val rowText = px(28)
    val rowMin = px(150)
    val pill = px(96)
    val pillText = px(32)
    val gridCols = if (wide) 6 else 4
    val gridIc = px(if (wide) 96 else 72)
    val gridText = px(28)
    val etaBig = px(46)
    val etaMid = px(40)
    val etaSmall = px(26)
    val radius = px(18)
}

@Composable
fun NavHostOverlay() {
    val model = LauncherModel
    val slot = EmbedHost.nav
    if (model.area != Area.Navigator || !slot.carHost || slot.status != EmbedStatus.READY) return
    val r = model.areaRect ?: return
    var ui by remember { mutableStateOf(CarAppHost.ui) }
    var toast by remember { mutableStateOf<CharSequence?>(null) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        CarAppHost.onChange = { main.post { ui = CarAppHost.ui; CarAppHost.toast?.let { if (it != toast) toast = it } } }
        ui = CarAppHost.ui
        onDispose { CarAppHost.onChange = null }
    }
    val density = LocalDensity.current
    val m = LocalMetrics.current
    val look = (if (LocalPalette.current.light) LIGHT else DARK).withAccent(LocalAccent.current)
    // «Масштаб карты» меняет и наши карточки с кнопками - в одну меру с картой навигатора
    val scale = CarAppHost.pkg?.let { com.yegorkapomidorka.headswap.carapp.CarConfigMath.step(LauncherModel.mapScale(it)) } ?: 1f
    val d = remember(m.spec, scale) { NavDims(m.spec.h / 720f * scale, m.spec.wide) }
    val w = r.width.roundToInt()
    val h = r.height.roundToInt()
    val current = ui
    // видимая и стабильная области для навигатора: карта без наших карточек и кнопок (CarAreas)
    val hasActions = (current as? CarUi.Nav)?.actions?.isNotEmpty() ?: (current as? CarUi.Panel)?.actions?.isNotEmpty() ?: false
    LaunchedEffect(current?.javaClass, current is CarUi.Nav && (current.maneuver != null || current.eta != null), hasActions, w) {
        val left = when (current) {
            is CarUi.Nav -> if (current.maneuver != null || current.eta != null) d.gap + maxOf(d.card, d.eta) + d.gap else 0
            is CarUi.Panel -> d.gap + d.panel + d.gap
            else -> 0
        }
        // ряд действий сверху справа: 2ГИС ставит лимит скорости в правый верхний угол видимой области
        CarAppHost.setAreas(left, d.gap + d.btn + d.gap, if (hasActions) d.gap + d.btn else 0)
        AliceVoice.navLeftPx = left
    }
    // окно навигатора и его карточки физические при любом языке (CarAppHost.setAreas выше считает левый отступ слева):
    // Box со всем содержимым создан внутри Ltr, иначе TopStart/absoluteOffset в RTL считались бы от правого края
    Ltr {
        Box(
            Modifier.absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() }),
        ) {
            when (current) {
                is CarUi.Nav -> NavScreen(current, look, d)
                is CarUi.Panel -> PanelScreen(current, look, d)
                is CarUi.Grid -> GridScreen(current, look, d)
                is CarUi.Search -> SearchScreen(current, look, d)
                is CarUi.Unknown, null -> Unit
            }
            val t = toast
            if (t != null) {
                LaunchedEffect(t) { delay(3500); toast = null }
                Box(Modifier.align(Alignment.TopCenter).padding(top = m.dp(d.gap)).clip(RoundedCornerShape(m.dp(d.radius)))
                    .background(look.card).padding(horizontal = m.dp(d.gap), vertical = m.dp(d.gap / 2))) {
                    Txt(t.toString(), d.rowText, color = look.fg, maxLines = 2)
                }
            }
        }
    }
}

// --- NavigationTemplate ---

@Composable
private fun androidx.compose.foundation.layout.BoxScope.NavScreen(n: CarUi.Nav, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    n.maneuver?.let { ManeuverCard(it, look, d, Modifier.align(Alignment.TopStart).padding(start = m.dp(d.gap), top = m.dp(d.gap))) }
    n.eta?.let { ArrivalCard(it, n.end, look, d, Modifier.align(Alignment.BottomStart).padding(start = m.dp(d.gap), bottom = m.dp(d.gap))) }
    if (n.eta == null && n.end != null) {
        // «Завершить» без оценки прибытия: крестик всё равно доступен
        EndButton(n.end, look, d, Modifier.align(Alignment.BottomStart).padding(start = m.dp(d.gap), bottom = m.dp(d.gap)))
    }
    ActionColumn(n.actions, n.mapActions, look, d)
}

@Composable
private fun ManeuverCard(man: CarManeuver, look: NavLook, d: NavDims, modifier: Modifier) {
    val m = LocalMetrics.current
    Row(
        modifier.width(m.dp(d.card)).clip(RoundedCornerShape(m.dp(d.radius))).background(look.card)
            .padding(horizontal = m.dp(d.cardPadH), vertical = m.dp(d.cardPadV)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(d.cardPadV)),
    ) {
        CarIconView(man.icon, d.manIc, look.maneuverIc, fallback = Ico.NAV)
        Column(verticalArrangement = Arrangement.spacedBy(m.dp(6))) {
            Txt(man.distance, d.dist, color = look.fg, weight = FontWeight.Bold, mono = true)
            if (man.road.isNotEmpty()) Txt(man.road, d.road, color = look.fg, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ArrivalCard(eta: CarEta, end: CarAction?, look: NavLook, d: NavDims, modifier: Modifier) {
    val m = LocalMetrics.current
    Row(
        modifier.width(m.dp(d.eta)).clip(RoundedCornerShape(m.dp(d.radius))).background(look.card)
            .padding(horizontal = m.dp(d.cardPadH), vertical = m.dp(d.cardPadV)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap)),
    ) {
        if (end != null) EndButton(end, look, d, Modifier)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
            Txt(eta.arrival, d.etaBig, color = look.eta, weight = FontWeight.Bold, mono = true)
            Txt(stringResource(R.string.l_nh_arrival), d.etaSmall, color = look.fg2)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
            Txt(eta.time, d.etaMid, color = look.fg, weight = FontWeight.SemiBold, mono = true)
            Txt(eta.distance, d.etaSmall, color = look.fg2, weight = FontWeight.SemiBold, mono = true)
        }
    }
}

@Composable
private fun EndButton(end: CarAction, look: NavLook, d: NavDims, modifier: Modifier) {
    RoundButton(d.end, end.title.ifEmpty { stringResource(R.string.l_nh_end) }, onTap = { end.click?.send() }, modifier,
        background = look.btn2, enabled = end.enabled) {
        SvgIcon(Ico.CLOSE, d.btnIc, look.endFg, stroke = 2.6f)
    }
}

/** Действия справа вверху (в ряд) и кнопки карты справа внизу (столбиком). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ActionColumn(actions: List<CarAction>, mapActions: List<CarAction>, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    if (actions.isNotEmpty()) {
        Row(Modifier.align(Alignment.TopEnd).padding(top = m.dp(d.gap), end = m.dp(d.gap)), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
            actions.forEach { CircleAction(it, look, d) }
        }
    }
    if (mapActions.isNotEmpty()) {
        Column(Modifier.align(Alignment.BottomEnd).padding(bottom = m.dp(d.gap), end = m.dp(d.gap)), verticalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
            mapActions.forEach { CircleAction(it, look, d) }
        }
    }
}

@Composable
private fun CircleAction(a: CarAction, look: NavLook, d: NavDims) {
    RoundButton(d.btn, a.title, onTap = { a.click?.send() }, background = look.btn, enabled = a.enabled) {
        if (a.icon != null) CarIconView(a.icon, d.btnIc, look.fg)
        else Txt(a.title, d.rowText, color = look.fg, weight = FontWeight.SemiBold)
    }
}

/**
 * Кнопка шапки экрана на всё окно: со значком - круглая, только с подписью - «таблетка» (в круг «История» не влезает).
 * Фон как у плиток: look.btn на fullBg не видно (стенд 04.11).
 */
@Composable
private fun HeaderAction(a: CarAction, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    if (a.icon != null || a.title.isEmpty()) {
        RoundButton(d.btn, a.title, onTap = { a.click?.send() }, background = look.tile, enabled = a.enabled) {
            if (a.icon != null) CarIconView(a.icon, d.btnIc, look.fg)
        }
        return
    }
    Pressable(Modifier.height(m.dp(d.btn)).clip(RoundedCornerShape(m.dp(d.btn / 2))).background(look.tile),
        description = a.title, onTap = { if (a.enabled) a.click?.send() }) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Box(Modifier.fillMaxHeight().padding(horizontal = m.dp(28)), contentAlignment = Alignment.Center) {
            Txt(a.title, d.rowText, color = look.fg, weight = FontWeight.SemiBold)
        }
    }
}

// --- панель слева: PlaceList, RoutePreview, MapTemplate+Pane ---

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PanelScreen(p: CarUi.Panel, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    Column(
        Modifier.align(Alignment.TopStart).padding(m.dp(d.gap)).width(m.dp(d.panel)).fillMaxHeight()
            .clip(RoundedCornerShape(m.dp(d.radius))).background(look.card),
    ) {
        // Pressable забирает протяжку, поэтому список листается кнопками в заголовке (макет: «Листать вниз»)
        val scroll = rememberScrollState()
        Header(p.title, p.back, look, d, Modifier.height(m.dp(d.header))) { PageButtons(scroll, look, d) }
        Box(Modifier.fillMaxWidth().height(m.dp(2)).background(look.divider))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            if (p.loading) Txt(stringResource(R.string.l_nh_loading), d.rowText, Modifier.padding(m.dp(d.gap)), color = look.fg2)
            else if (p.rows.isEmpty() && p.empty.isNotEmpty()) Txt(p.empty, d.rowText, Modifier.padding(m.dp(d.gap)), color = look.fg2, maxLines = 3)
            // выбор держит хост, как AA: selectedIndex в шаблоне - только начальный, 2ГИС после выбора его не меняет
            var selected by remember(p.title, p.rows.map { it.title }, p.selected) { mutableStateOf(p.selected) }
            p.rows.forEachIndexed { i, row ->
                RowView(row, i == selected, look, d) { if (p.select != null) { selected = i; p.select.invoke(i) } }
            }
        }
        if (p.buttons.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(m.dp(d.gap)), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                p.buttons.forEach { b -> Pill(b, look, d, Modifier.weight(1f)) }
            }
        }
    }
    ActionColumn(p.actions, p.mapActions, look, d)
}

/** «Вверх»/«вниз» на высоту видимой части, только когда список не помещается. */
@Composable
private fun PageButtons(scroll: androidx.compose.foundation.ScrollState, look: NavLook, d: NavDims) {
    if (scroll.maxValue <= 0) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val m = LocalMetrics.current
    val page = scroll.viewportSize * 4 / 5
    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(12))) {
        RoundButton(d.btn, stringResource(R.string.l_prev_page), onTap = { scope.launch { scroll.animateScrollBy(-page.toFloat()) } },
            background = look.btn2, enabled = scroll.value > 0) {
            SvgIcon(listOf("m6 15 6-6 6 6"), d.btnIc * 36 / 44, if (scroll.value > 0) look.fg else look.fg3, stroke = 2.4f)
        }
        RoundButton(d.btn, stringResource(R.string.l_next_page), onTap = { scope.launch { scroll.animateScrollBy(page.toFloat()) } },
            background = look.btn2, enabled = scroll.value < scroll.maxValue) {
            SvgIcon(listOf("m6 9 6 6 6-6"), d.btnIc * 36 / 44, if (scroll.value < scroll.maxValue) look.fg else look.fg3, stroke = 2.4f)
        }
    }
}

@Composable
private fun Header(title: String, back: CarAction?, look: NavLook, d: NavDims, modifier: Modifier, end: @Composable () -> Unit = {}) {
    val m = LocalMetrics.current
    Row(modifier.fillMaxWidth().padding(horizontal = m.dp(d.gap)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(16))) {
        // APP_ICON в шапке - не «Назад», а значок приложения без нажатия (как AA): 2ГИС ставит его на стартовую страницу
        if (back != null && back.type == CarActions.TYPE_APP_ICON && back.click == null) AppIcon(d.btn)
        else if (back != null) {
            RoundButton(d.btn, back.title.ifEmpty { stringResource(R.string.l_back) }, onTap = { back.click?.send() ?: CarAppHost.back() }) {
                if (back.type == CarActions.TYPE_BACK || back.icon == null || back.icon.standard != 0) SvgIcon(Ico.CHEV_BACK, d.btnIc, look.fg, stroke = 2.4f)
                else CarIconView(back.icon, d.btnIc, null)
            }
        }
        Txt(title, d.title, Modifier.weight(1f), color = look.fg, weight = FontWeight.Bold)
        end()
    }
}

@Composable
private fun RowView(row: CarRow, selected: Boolean, look: NavLook, d: NavDims, onSelect: () -> Unit) {
    val m = LocalMetrics.current
    // переключатель держит хост, как AA: 2ГИС после смены новое состояние не присылает
    var checked by remember(row.title, row.toggle?.checked) { mutableStateOf(row.toggle?.checked == true) }
    val tap = {
        val t = row.toggle
        if (t != null && t.change != null && t.enabled) { checked = !checked; t.change.invoke(checked) }
        else row.click?.send() ?: onSelect()
    }
    Pressable(Modifier.fillMaxWidth().heightIn(min = m.dp(if (row.texts.isEmpty()) 92 * d.btn / 90 else d.rowMin))
        .background(if (selected) look.sel else Color.Transparent), onTap = tap) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Row(Modifier.fillMaxWidth().padding(horizontal = m.dp(28), vertical = m.dp(16)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(24))) {
            if (row.image != null) CarIconView(row.image, d.btnIc + d.gap, null)
            else if (row.number > 0) {
                Box(Modifier.size(m.dp(64)).clip(CircleShape).background(if (selected) look.acc else look.btn2), contentAlignment = Alignment.Center) {
                    Txt(row.number.toString(), d.rowText, color = if (selected) look.onAcc else look.fg, weight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(6))) {
                // строку без нажатия (текст MessageTemplate: «Оформите подписку Яндекс Плюс, чтобы…») - целиком
                val passive = row.click == null && row.toggle == null && !row.browsable
                Txt(row.title, d.rowTitle, color = look.fg, weight = FontWeight.Bold, maxLines = if (passive) 6 else 2)
                row.texts.forEachIndexed { i, t -> Txt(t, d.rowText, color = if (i == 0) look.fg2 else look.fg3) }
            }
            val tg = row.toggle
            if (tg != null) Switch(checked, look, d)
            else if (row.browsable) SvgIcon(Ico.CHEV_R, d.btnIc * 36 / 44, look.fg2, stroke = 2.4f)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(m.dp(2)).background(look.divider))
    }
}

@Composable
private fun Switch(on: Boolean, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    Box(Modifier.size(m.dp(104), m.dp(56)).clip(RoundedCornerShape(m.dp(28))).background(if (on) look.acc else look.btn2).padding(m.dp(6)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(m.dp(44)).clip(CircleShape).background(if (on) look.onAcc else look.fg2))
    }
}

@Composable
private fun Pill(a: CarAction, look: NavLook, d: NavDims, modifier: Modifier) {
    val m = LocalMetrics.current
    Pressable(modifier.height(m.dp(d.pill)).clip(RoundedCornerShape(m.dp(d.pill / 2))).background(if (a.primary) look.acc else look.btn2),
        enabled = a.enabled, description = a.title, onTap = { a.click?.send() }) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(if (a.primary) Color(0x33000000) else Pal.SURFACE_PRESSED))
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (a.icon != null && a.title.isEmpty()) CarIconView(a.icon, d.btnIc, if (a.primary) look.onAcc else look.fg)
            Txt(a.title, d.pillText, color = if (a.primary) look.onAcc else look.fg, weight = if (a.primary) FontWeight.Bold else FontWeight.SemiBold)
        }
    }
}

// --- на всё окно: GridTemplate и SearchTemplate ---

@Composable
private fun GridScreen(g: CarUi.Grid, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    Column(Modifier.fillMaxSize().background(look.fullBg)) {
        Header(g.title, g.back, look, d, Modifier.height(m.dp(d.header + 22))) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap)), verticalAlignment = Alignment.CenterVertically) {
                g.actions.forEach { HeaderAction(it, look, d) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = m.dp(29), vertical = m.dp(8)),
            verticalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
            g.items.chunked(d.gridCols).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                    line.forEach { item ->
                        Pressable(Modifier.weight(1f).height(m.dp(d.gridIc * 2 + d.gap)).clip(RoundedCornerShape(m.dp(d.radius))).background(look.tile),
                            description = item.title, onTap = { item.click?.send() }) { pressed ->
                            if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                            Column(Modifier.matchParentSize().padding(m.dp(8)), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(m.dp(8), Alignment.CenterVertically)) {
                                Box(Modifier.size(m.dp(d.gridIc)).clip(CircleShape).background(look.gridIcBg), contentAlignment = Alignment.Center) {
                                    CarIconView(item.image, d.gridIc * 2 / 3, null)
                                }
                                Txt(item.title, d.gridText, color = look.fg, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                    repeat(d.gridCols - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(s: CarUi.Search, look: NavLook, d: NavDims) {
    val m = LocalMetrics.current
    var text by remember(s.initial) { mutableStateOf(s.initial) }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(look.fullBg)) {
        Row(Modifier.fillMaxWidth().height(m.dp(d.header + 22)).padding(horizontal = m.dp(29)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
            RoundButton(d.btn, s.back?.title?.ifEmpty { null } ?: stringResource(R.string.l_back),
                onTap = { s.back?.click?.send() ?: CarAppHost.back() }, background = look.btn2) {
                SvgIcon(Ico.CHEV_BACK, d.btnIc, look.fg, stroke = 2.4f)
            }
            Box(Modifier.weight(1f).height(m.dp(d.btn)).clip(RoundedCornerShape(m.dp(d.btn / 2))).background(look.btn2)
                .padding(horizontal = m.dp(32)), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Txt(s.hint.ifEmpty { stringResource(R.string.l_nh_search) }, d.title, color = look.fg3)
                BasicTextField(
                    value = text,
                    onValueChange = { v -> text = v; s.changed?.invoke(v) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = TextStyle(color = look.fg, fontSize = m.sp(d.title), fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(look.acc),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { s.submitted?.invoke(text) }),
                )
            }
            PageButtons(scroll, look, d)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = m.dp(29))) {
            s.rows.forEach { row -> RowView(row, false, look, d) {} }
        }
    }
    LaunchedEffect(Unit) { if (s.showKeyboard) runCatching { focus.requestFocus() } }
}

// --- иконки приложения ---

/** Картинки приложения по ключу содержимого: шаблон со теми же иконками приходит каждую секунду. */
private object CarImages {
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 96
    }

    @Synchronized fun get(img: CarImage): ImageBitmap? {
        if (cache.containsKey(img.key)) return cache[img.key]
        val b = runCatching { img.load() as? Bitmap }.getOrNull()?.asImageBitmap()
        cache[img.key] = b
        return b
    }
}

/** Значок навигатора хоста (Action.APP_ICON): иконка пакета из лаунчера телефона. */
@Composable
private fun AppIcon(sizePx: Int) {
    val m = LocalMetrics.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pkg = CarAppHost.pkg
    val px = m.dp(sizePx)
    val bmp = remember(pkg) {
        pkg?.let { p -> runCatching { ctx.packageManager.getApplicationIcon(p).let { d ->
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
                d.setBounds(0, 0, 128, 128); d.draw(android.graphics.Canvas(b))
            }.asImageBitmap()
        } }.getOrNull() }
    }
    if (bmp != null) Image(bmp, null, Modifier.size(px).clip(CircleShape)) else Spacer(Modifier.size(px))
}

/** Иконка приложения; [tint] - перекрасить (глифы кнопок и манёвра в цвет стиля), null - как есть. */
@Composable
private fun CarIconView(img: CarImage?, sizePx: Int, tint: Color?, fallback: List<String>? = null) {
    val m = LocalMetrics.current
    val bmp = remember(img?.key) { img?.let(CarImages::get) }
    when {
        bmp != null -> Image(bmp, null, Modifier.size(m.dp(sizePx)), colorFilter = tint?.let { ColorFilter.tint(it) })
        img?.standard == androidx.car.app.model.CarIcon.TYPE_BACK -> SvgIcon(Ico.CHEV_BACK, sizePx, tint ?: Pal.TEXT, stroke = 2.4f)
        fallback != null -> SvgIcon(fallback, sizePx, tint ?: Pal.TEXT, fill = true)
        else -> Spacer(Modifier.size(m.dp(sizePx)))
    }
}
