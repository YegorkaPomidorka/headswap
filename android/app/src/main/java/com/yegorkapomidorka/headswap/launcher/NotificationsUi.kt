package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.yegorkapomidorka.headswap.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Что показано в оверлее, пока он уезжает: после снятия уведомлений или конца таймера содержимое должно доехать
 * наверх, а не пропасть кадром. Меняется только из композиции, состоянием не является.
 */
private class Shown {
    var head: NotifItem? = null
    var shade = false
    var items: List<NotifItem> = emptyList()
    /**
     * центр панели (язычок или шторка) по X в координатах корня, пишется при раскладке. Состояние, а не поле: по нему
     * сдвигается полоска ([lineDx]), и без чтения состояния Compose не переставит её, когда панель выросла и упёрлась в рейку
     * (Егор 07.11: полоска стояла мимо центра чёлки и мимо стыка в шторке).
     */
    var panelCx by mutableFloatStateOf(0f)
    /** нижний край панели (Y корня, без сдвига graphicsLayer): к нему в режиме чёлки прибита полоска отдельным слоем */
    var panelBottom by mutableFloatStateOf(0f)
    /** X стыка плиток, последний раз виденный на главном: на экранах без стыка полоска стоит там же (Егор 08.11) */
    var seamX: Float? = null
}

/**
 * Язычок и шторка: панель цвета строки статуса, растущая из её нижнего края с вогнутыми «ушами» сверху ([NotchShape]).
 * Золотая полоска - одна на строке статуса и в панели: на строке гаснет, пока панель внизу, в панели лежит на её нижнем крае.
 *
 * Касания: корень оверлея их не берёт. Хит-цели - только полоска на строке (T x 2T вокруг неё), сама панель (контейнер
 * обрезан по нижнему краю строки, спрятанная панель за обрезкой не попадается под палец) и, пока шторка открыта,
 * подложка «тап мимо - свернуть». Всё остальное достаётся лаунчеру и живым окнам приложений под оверлеем.
 */
@Composable
fun NotificationsOverlay() {
    // Язычок и шторка - в своём масштабе: T не больше 2/15 высоты экрана. Низким широким экранам (1280x480, T 92 из таблицы)
    // T лаунчера велик для панели поверх плиток, язычок занимал треть экрана (Егор 08.11). На 720/640/1080 почти без изменений.
    val base = LocalMetrics.current
    val bs = base.spec
    val nt = min(bs.t, Math.round(bs.h * 2f / 15f))
    val spec = if (nt >= bs.t) bs else {
        val q = nt.toFloat() / bs.t
        fun sc(v: Int) = max(1, Math.round(v * q))
        LauncherSpec(bs.w, bs.h, nt, sc(bs.g), sc(bs.m), sc(bs.f1), sc(bs.f2), sc(bs.f3), bs.railSize)
    }
    val density = LocalDensity.current
    val metrics = remember(spec, density) { if (spec === bs) base else LauncherMetrics(spec, density) }
    CompositionLocalProvider(LocalMetrics provides metrics) { NotificationsOverlayBody(bs.statusBandH) }
}

@Composable
private fun NotificationsOverlayBody(barBandH: Int) {
    val model = LauncherModel
    val repo = NotificationsRepo
    val area = model.areaRect
    // оверлей на экране ГУ: без него таймер язычка не идёт, и репозиторий язычок не выпускает ([NotificationsRepo.screenShown])
    DisposableEffect(Unit) { repo.screenShown(); onDispose { repo.screenGone() } }
    if (!model.notifications || area == null) return
    val m = LocalMetrics.current
    val s = m.spec
    val pal = LocalPalette.current
    val k = s.t / 96f
    fun px(v: Float) = Math.round(v * k)

    val liveItems = repo.items
    val head = repo.headsUp
    val open = repo.shadeOpen && liveItems.isNotEmpty()
    val show = open || head != null
    val shown = remember { Shown() }
    if (liveItems.isNotEmpty()) shown.items = liveItems
    if (head != null) shown.head = head.item
    if (open) shown.shade = true else if (head != null) shown.shade = false // язычок после шторки не должен уезжать шторкой

    val p = remember { Animatable(0f) }
    LaunchedEffect(show) {
        p.animateTo(if (show) 1f else 0f, tween(if (show) 420 else 300, easing = if (show) EASE_OUT else FastOutSlowInEasing))
        if (!show) { shown.shade = false; shown.head = null }
    }
    // язычок убирает себя сам: 4 с и секунда на каждые 40 символов
    LaunchedEffect(head?.seq) {
        val h = head ?: return@LaunchedEffect
        kotlinx.coroutines.delay(NotificationsLogic.showMs(h.item.entry.text))
        repo.endHeadsUp(h.seq)
    }

    val topY = model.statusBottom ?: area.top
    val ear = px(32f)
    val barCy = (model.statusBottom ?: area.top) - barBandH / 2f // полоса строки статуса - в масштабе лаунчера
    // Полоска и язычок - над стыком плиток главного (между музыкой и картой), и на всех экранах в том же пикселе (Егор 08.11):
    // в настройках, с картой на весь экран и т.п. X стыка запоминается с главного. Плитка на всю ширину - стыка нет (раньше
    // за него принимался её правый край, и язычок со шторкой улетали вправо). Главного ещё не было - у левого края области.
    val big = model.bigTileRect
    val seam = if (model.area == Area.Home && big != null && big.width > 0f) {
        NotifGeom.seamX(big.left, big.right, area.left, area.right, s.g.toFloat())
    } else null
    if (seam != null) shown.seamX = seam
    val centerX = shown.seamX ?: (area.left + ear + NotifGeom.tongueWidth(s.t, Math.round(area.width), ear, s.g).first / 2f)
    val dragThr = px(30f).toFloat()
    // Строка статуса скрыта (Для разработчиков, Егор 07.11): полоски на строке нет, вместо неё от верха экрана висит чёлка -
    // та же панель, свёрнутая до 168x30 с полоской на нижнем крае. Язычок и шторка растут из неё (animateContentSize), а не
    // выезжают сверху; свернулись - снова чёлка. Непрочитанных нет - нет и чёлки.
    // Полоска не двигается по X (Егор 07.11): чёлка висит над ней, и на язычке и шторке полоска стоит ровно там же.
    val notchMode = model.hideStatusBar && model.statusSlot && model.statusBottom == null
    // и везде - не на тексте строки статуса (дата, значки, часы): на язычке и шторке полоска стоит там же, где на строке
    val busyX = if (notchMode) emptyList() else model.statusBusyX
    fun lineX(): Float = NotifGeom.lineAvoiding(
        centerX,
        s.t / 2f, s.g.toFloat(), busyX, area.left, area.right,
    )
    val lineDx: () -> Int = { Math.round(lineX() - shown.panelCx) }
    val idle = notchMode && liveItems.isNotEmpty()
    // содержимое язычка/шторки - только когда панель уже поехала (p > 0): первый кадр - чёлка, от неё и растёт
    val grown = !notchMode || (show && p.value > 0f)
    val earNow = if (notchMode) Math.round(px(16f) + (ear - px(16f)) * p.value) else ear
    val bottomNow = if (notchMode) Math.round(px(15f) + (px(44f) - px(15f)) * p.value) else px(44f)

    Ltr {
        // полоска на строке статуса (или тач-зона чёлки): только если есть что показать
        if (liveItems.isNotEmpty() && (model.statusBottom != null || idle)) {
            val hitW = 2 * s.t
            val hitY = if (notchMode) Math.round(topY) else Math.round(barCy) - s.t / 2
            // смещение - на обёртке: рамку шайбы Pressable рисует до своего modifier и offset внутри её не сдвинул бы
            Box(
                Modifier.absoluteOffset { IntOffset(Math.round(lineX()) - hitW / 2, hitY) }
                    .graphicsLayer { alpha = if (notchMode) 1f else 1f - p.value },
            ) {
                Pressable(
                    Modifier.size(m.dp(hitW), m.dp(s.t)),
                    enabled = !show && p.value == 0f,
                    description = stringResource(R.string.l_notif_show_all, liveItems.size),
                    focusShape = RoundedCornerShape(m.dp(s.t / 2)),
                    onSwipe = { _, dy -> if (dy > dragThr) { repo.openShade(); true } else false },
                    onTap = { repo.openShade() },
                ) {
                    // в режиме чёлки полоску рисует сама чёлка, здесь только тач-зона
                    if (!notchMode) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { GoldHandle() }
                }
            }
        }
        if (show || p.value > 0f || idle) {
            if (open) {
                // тап мимо шторки - свернуть; чуть затемняет остальное
                Box(
                    // без затемнения: оно темнило и строку статуса, и шторка переставала с ней сливаться (Егор 06.11)
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { repo.closeShade() } },
                )
            }
            val panelAreaH = area.bottom - topY
            if (panelAreaH > 0f) {
                Box(
                    Modifier.absoluteOffset { IntOffset(Math.round(area.left), Math.round(topY)) }
                        .size(m.dp(Math.round(area.width)), m.dp(Math.round(panelAreaH))).clipToBounds(),
                ) {
                    val shape = remember(earNow, bottomNow) { NotchShape(earNow.toFloat(), bottomNow.toFloat()) }
                    val (minW, maxW) = NotifGeom.tongueWidth(s.t, Math.round(area.width), ear, s.g)
                    val inShade = grown && (open || (shown.shade && !show))
                    Column(
                        // по центру над полоской (стыком), не вылезая за область: широкая шторка упирается в рейку
                        Modifier.layout { measurable, constraints ->
                            val pl = measurable.measure(constraints.copy(minWidth = 0))
                            val x = Math.round(centerX - area.left - pl.width / 2f).coerceIn(0, max(0, constraints.maxWidth - pl.width))
                            shown.panelCx = area.left + x + pl.width / 2f
                            shown.panelBottom = topY + pl.height
                            layout(constraints.maxWidth, pl.height) { pl.place(x, 0) }
                        }
                            .graphicsLayer { translationY = if (idle) 0f else -size.height * (1f - p.value) }
                            .shadow(m.dp(px(12f)), shape, clip = false)
                            .clip(shape).background(pal.bar, shape)
                            .animateContentSize(tween(if (notchMode) 420 else 260, easing = EASE_OUT))
                            // тап по пустому месту самой шторки не должен падать на подложку и сворачивать её (кнопки забирают свои касания раньше)
                            .then(if (inShade) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
                            .padding(horizontal = m.dp(earNow)),
                    ) {
                        // в режиме чёлки содержимое проявляется, пока панель растёт
                        val fade = Modifier.graphicsLayer { alpha = if (notchMode) p.value else 1f }
                        // панель стоит физически (Ltr), а тексты и аватары в ней идут по языку: в RTL аватар справа
                        LocaleDir {
                            if (inShade) {
                                val items = liveItems.ifEmpty { shown.items }
                                val listMax = NotifGeom.listMaxHeight(Math.round(area.bottom), Math.round(topY), s.t, px(8f))
                                Box(fade) { ShadeBody(items, maxW, listMax, dragThr, lineDx, showLine = !notchMode) }
                            } else if (grown) {
                                shown.head?.let { Box(fade) { TongueBody(it, minW, maxW, dragThr, lineDx, showLine = !notchMode) } }
                            } else {
                                NotchBody()
                            }
                        }
                    }
                }
                if (notchMode) {
                    // Полоска чёлки - отдельным слоем, прибита по X над стыком (Егор 07.11): пока панель меняет размер,
                    // содержимое в animateContentSize прижато к её левому краю, и полоска внутри него ехала бы вбок.
                    // По Y - на нижнем крае панели: 8 px над ним у чёлки, 14 у язычка, по центру нижнего ряда у шторки.
                    val lineH = max(4, Math.round(s.t / 12f))
                    val pad = lineH * 1.6f
                    val hw = s.t + 2 * pad
                    val hh = lineH + 2 * pad
                    val dNotch = px(8f) + lineH / 2f
                    val dTongue = px(14f) + lineH / 2f
                    val dShade = s.t / 2f
                    Box(
                        Modifier.absoluteOffset {
                            val v = p.value
                            val d = dNotch + ((if (shown.shade) dShade else dTongue) - dNotch) * v
                            val ty = if (idle) 0f else -(shown.panelBottom - topY) * (1f - v)
                            IntOffset(Math.round(centerX - hw / 2f), Math.round(shown.panelBottom + ty - d - hh / 2f))
                        },
                    ) { GoldHandle() }
                }
            }
        }
    }
}

private val EASE_OUT = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Язычок: аватар со значком и одна-две строки текста; на нижнем краю - золотая полоска (тап и потянуть вниз = шторка). */
@Composable
private fun TongueBody(item: NotifItem, minW: Int, maxW: Int, dragThr: Float, lineDx: () -> Int, showLine: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96f
    fun px(v: Float) = Math.round(v * k)
    val repo = NotificationsRepo
    Pressable(
        Modifier.widthIn(min = m.dp(minW), max = m.dp(maxW)).width(IntrinsicSize.Max),
        description = item.entry.who + ": " + item.entry.text,
        focusShape = RoundedCornerShape(bottomStart = m.dp(px(44f)), bottomEnd = m.dp(px(44f))),
        onTap = { repo.openShade(item.key) },
    ) {
        Row(
            Modifier.heightIn(min = m.dp(px(128f))).padding(start = m.dp(px(36f)), end = m.dp(px(44f)), top = m.dp(px(18f)), bottom = m.dp(px(58f))),
            // снизу 58: полоска 14..22 от края и воздух над ней, иначе вторая строка текста ложится прямо на полоску (Егор 08.11)
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotifAvatar(item, px(72f))
            Spacer(Modifier.width(m.dp(px(28f))))
            Txt(item.entry.text, s.f1, color = Pal.TEXT, weight = FontWeight.Medium, maxLines = 2)
        }
        // полоска лежит на нижнем крае вместе с язычком; тач-зона - T x 2T от нижней кромки вверх
        // сдвиг к месту полоски на строке статуса — на обёртке (рамку шайбы Pressable рисует до своего modifier)
        // lineDx - сдвиг в px корня, физический: absoluteOffset, не зеркалящийся offset
        Box(Modifier.align(Alignment.BottomCenter).absoluteOffset { IntOffset(lineDx(), 0) }) {
            Pressable(
                Modifier.size(m.dp(2 * s.t), m.dp(s.t)),
                description = stringResource(R.string.l_notif_show_all, repo.items.size),
                focusShape = RoundedCornerShape(m.dp(s.t / 2)),
                onSwipe = { _, dy -> if (dy > dragThr) { repo.openShade(item.key); true } else false },
                onTap = { repo.openShade(item.key) },
            ) {
                if (showLine) Box(Modifier.matchParentSize().padding(bottom = m.dp(px(14f))), contentAlignment = Alignment.BottomCenter) { GoldHandle() }
            }
        }
    }
}

/**
 * Чёлка (строка статуса скрыта): тело 168x30 при T 96 со скруглением снизу. Полоску и тач-зону рисует [NotificationsOverlay]
 * отдельно, по абсолютным координатам, - здесь только размер тела.
 */
@Composable
private fun NotchBody() {
    val m = LocalMetrics.current
    val k = m.spec.t / 96f
    fun px(v: Float) = Math.round(v * k)
    Box(Modifier.size(m.dp(px(168f)), m.dp(px(30f))))
}

/** Шторка: строки со списком (прокрутка, если не влезло), внизу золотая полоска (свернуть) и «Очистить всё». */
@Composable
private fun ShadeBody(items: List<NotifItem>, width: Int, listMax: Int, dragThr: Float, lineDx: () -> Int, showLine: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96f
    fun px(v: Float) = Math.round(v * k)
    val repo = NotificationsRepo
    Column(Modifier.width(m.dp(width)).padding(horizontal = m.dp(px(20f)))) {
        Column(Modifier.heightIn(max = m.dp(max(0, listMax))).verticalScroll(rememberScrollState()).padding(top = m.dp(px(8f)))) {
            for (item in items) NotifRow(item, repo.expandedKey == item.key)
        }
        Box(Modifier.fillMaxWidth().height(m.dp(s.t))) {
            Box(Modifier.align(Alignment.Center).absoluteOffset { IntOffset(lineDx(), 0) }) {
                Pressable(
                    Modifier.size(m.dp(2 * s.t), m.dp(s.t)),
                    description = stringResource(R.string.l_notif_collapse),
                    focusShape = RoundedCornerShape(m.dp(s.t / 2)),
                    onSwipe = { _, dy -> if (dy < -dragThr) { repo.closeShade(); true } else false },
                    onTap = { repo.closeShade() },
                ) {
                    if (showLine) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { GoldHandle() }
                }
            }
            val clear = stringResource(R.string.l_notif_clear_all)
            Box(Modifier.align(Alignment.CenterEnd).padding(end = m.dp(px(8f)))) {
                Pressable(
                    Modifier.height(m.dp(s.t)).clip(RoundedCornerShape(m.dp(s.t / 2))),
                    description = clear, focusShape = RoundedCornerShape(m.dp(s.t / 2)), onTap = { repo.clearAll() },
                ) { pressed ->
                    if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                    Box(Modifier.height(m.dp(s.t)).padding(horizontal = m.dp(px(24f))), contentAlignment = Alignment.Center) {
                        Txt(clear, s.f2, color = Pal.SEC2, ellipsis = false)
                    }
                }
            }
        }
    }
}

/** Строка шторки: тап - раскрыть/свернуть текст, смахнуть вбок или × - убрать; в раскрытой - «Открыть в приложении». */
@Composable
private fun NotifRow(item: NotifItem, expanded: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96f
    fun px(v: Float) = Math.round(v * k)
    val repo = NotificationsRepo
    val e = item.entry
    val launch = if (expanded) repo.launchRef(e.pkg) else null
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // cooperative: список под строками прокручивается пальцем; сдвиг по горизонтали дальше порога убирает строку
            Pressable(
                Modifier.weight(1f).heightIn(min = m.dp(s.t)),
                description = e.who + ": " + e.text,
                cooperative = true,
                onSwipe = { dx, dy -> if (abs(dx) > px(80f) && abs(dx) > 2 * abs(dy)) { repo.dismiss(item.key); true } else false },
                onTap = { repo.toggleExpanded(item.key) },
            ) { pressed ->
                if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                Row(
                    Modifier.fillMaxWidth().padding(start = m.dp(px(16f)), end = m.dp(px(8f)), top = m.dp(px(14f)), bottom = m.dp(px(14f))),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(px(24f))),
                ) {
                    NotifAvatar(item, px(64f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(2))) {
                        Txt(e.who, s.f2, color = Pal.TEXT, weight = FontWeight.Bold)
                        Txt(e.text, s.f2, color = Pal.SEC2, maxLines = if (expanded) 40 else 2)
                    }
                    Txt(NotificationsLogic.time(e.postTime), max(14, Math.round(s.f2 * 0.8f)), color = Pal.SEC2, weight = FontWeight.SemiBold, mono = true)
                }
            }
            val dismiss = stringResource(R.string.l_notif_dismiss)
            RoundButton(s.t, dismiss, onTap = { repo.dismiss(item.key) }) {
                SvgIcon(Ico.CLOSE, s.ic / 2 + 2, Pal.SEC2, stroke = 2.4f)
            }
        }
        if (launch != null) {
            Box(Modifier.padding(start = m.dp(px(16f + 64f + 24f)), bottom = m.dp(px(16f)))) {
                PillButton(stringResource(R.string.l_notif_open_in, item.appLabel), { repo.open(item) }, filled = false)
            }
        }
        Box(Modifier.fillMaxWidth().height(m.dp(1)).background(LocalPalette.current.sep))
    }
}

/** Аватар отправителя кругом и значок приложения в углу; аватара нет - иконка приложения скруглённым квадратом без значка. */
@Composable
private fun NotifAvatar(item: NotifItem, sizePx: Int) {
    val m = LocalMetrics.current
    val pal = LocalPalette.current
    val size = m.dp(sizePx)
    val av = item.avatar
    val icon = item.appIcon
    Box(Modifier.size(size)) {
        when {
            av != null -> Image(av, null, Modifier.fillMaxSize().clip(CircleShape))
            icon != null -> Image(icon, null, Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.22f)))
            else -> Box(Modifier.fillMaxSize().clip(CircleShape).background(Pal.SURFACE_HI), contentAlignment = Alignment.Center) {
                Txt(item.entry.who.trim().take(1).uppercase(), Math.round(sizePx * 0.45f), weight = FontWeight.Bold)
            }
        }
        if (av != null && icon != null) {
            val badge = m.dp(Math.round(sizePx * 0.42f))
            Image(
                icon, null,
                Modifier.align(Alignment.BottomEnd).offset(m.dp(Math.round(sizePx * 0.06f)), m.dp(Math.round(sizePx * 0.06f)))
                    .size(badge).clip(CircleShape).border(m.dp(max(2, Math.round(sizePx * 0.04f))), pal.bar, CircleShape),
            )
        }
    }
}

/** Золотая переливающаяся полоска 96x8 (при T 96) с мягким свечением; «Анимации кнопок» выключены - стоит спокойно. */
@Composable
private fun GoldHandle(modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val t = m.spec.t
    val lineW = t.toFloat()
    val lineH = max(4, Math.round(t / 12f)).toFloat()
    val pad = lineH * 1.6f
    val anim = LauncherModel.animations
    // язычок цвета акцента; перелив - акцент, высветленный к белому (у золота - #FFF1B8)
    val gold = LocalAccent.current
    val goldLight = if (gold == Pal.GOLD) GOLD_LIGHT else androidx.compose.ui.graphics.lerp(gold, Color.White, 0.7f)
    val tr = rememberInfiniteTransition(label = "gold-handle")
    val shimmer = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "shimmer")
    val glow = tr.animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val wDp = with(density) { (lineW + 2 * pad).toDp() }
    val hDp = with(density) { (lineH + 2 * pad).toDp() }
    Canvas(modifier.size(wDp, hDp)) {
        val g = if (anim) glow.value else 0.55f
        val x = if (anim) shimmer.value else 0.5f
        val left = pad
        val top = pad
        // свечение: несколько расширяющихся слоёв всё прозрачнее
        for (i in 3 downTo 1) {
            val grow = lineH * 0.5f * i
            drawRoundRect(
                gold.copy(alpha = g * 0.10f), Offset(left - grow, top - grow),
                Size(lineW + 2 * grow, lineH + 2 * grow), CornerRadius(lineH / 2 + grow),
            )
        }
        // перелив: светлая полоса идёт слева направо, как background-position в макете
        val cx = left + lineW * (x * 1.4f - 0.2f)
        val half = lineW * 0.9f
        val brush = Brush.horizontalGradient(
            0f to gold, 0.35f to gold, 0.5f to goldLight, 0.65f to gold, 1f to gold,
            startX = cx - half, endX = cx + half,
        )
        drawRoundRect(brush, Offset(left, top), Size(lineW, lineH), CornerRadius(lineH / 2))
    }
}

private val GOLD_LIGHT = Color(0xFFFFF1B8)

/**
 * Панель из-под строки статуса: верхняя кромка во всю ширину (вровень с полосой), от неё вогнутые «уши» радиусом [ear]
 * к телу, тело со скруглением [bottom] внизу. Размер области включает уши: тело уже на 2 * ear.
 */
private class NotchShape(private val ear: Float, private val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = min(ear, h / 2)
        val b = max(0f, min(bottom, min((w - 2 * r) / 2, h - r)))
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            arcTo(Rect(w - r, 0f, w + r, 2 * r), 270f, -90f, false)
            lineTo(w - r, h - b)
            arcTo(Rect(w - r - 2 * b, h - 2 * b, w - r, h), 0f, 90f, false)
            lineTo(r + b, h)
            arcTo(Rect(r, h - 2 * b, r + 2 * b, h), 90f, 90f, false)
            lineTo(r, r)
            arcTo(Rect(-r, 0f, r, 2 * r), 0f, -90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}
