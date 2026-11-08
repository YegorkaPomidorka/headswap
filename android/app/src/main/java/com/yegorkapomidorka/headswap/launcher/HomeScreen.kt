package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Подложка карточки главного экрана. Содержимое следует направлению языка; физические куски (медиа-ряд) берут [Ltr] сами. */
@Composable
fun CardBox(modifier: Modifier = Modifier, background: Color = Pal.SURFACE, shape: Shape? = null, content: @Composable BoxScope.() -> Unit) {
    val m = LocalMetrics.current
    Box(modifier.clip(shape ?: RoundedCornerShape(m.dp(m.spec.radius))).background(background), content = content)
}

/**
 * Главный экран: строка статуса и плашки. Четыре плашки - верхний ряд (музыка | большая плашка) и нижний
 * (приложения | телефон). Две плашки (2:1, 1:2) - большая и малая на всю высоту, без нижнего ряда.
 * Большая плашка - навигатор, YouTube, музыка или часы (листают «Слои»), ⇄ на стыке меняет плашки местами.
 */
@Composable
fun HomeScreen() {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val layout = model.homeLayout
    val tiles = model.tiles
    // «Карта слева» и «2 : 1» - физические стороны экрана при любом языке: в RTL Row кладёт первую плашку справа
    val rtl = localeRtl()
    // нижний ряд прибит к краю экрана, как блок навигации на рейке: снизу без поля
    Column(Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.m),
        bottom = m.dp(if (s.strip || layout.two) s.m else 0))) {
        StatusBar()
        // без строки статуса (Для разработчиков) плитки поднимаются к верхнему полю
        if (!model.hideStatusBar) Spacer(Modifier.height(m.dp(s.statusGap)))
        val bigMod = Modifier.onGloballyPositioned { model.bigTileRect = it.boundsInRoot() }
        if (layout.two) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                val cells = listOf<@Composable RowScope.() -> Unit>(
                    { TileCard(tiles.big, Modifier.weight(1f).fillMaxHeight().then(bigMod)) },
                    { TileCard(tiles.small, Modifier.width(m.dp(s.tileSmall)).fillMaxHeight()
                        .onGloballyPositioned { model.smallTileRect = it.boundsInRoot() }, small = true) },
                )
                for (cell in if ((layout == HomeLayout.WIDE_LEFT) != rtl) cells else cells.asReversed()) cell(this)
            }
            return@Column
        }
        // «Карта слева»: оба ряда в обратном порядке (row-reverse); в RTL Row уже кладёт первое справа, порядок обратный
        val mirror = model.mapLeft != rtl
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            val top = buildList<@Composable RowScope.() -> Unit> {
                add { MusicCard(Modifier.width(m.dp(s.music)).fillMaxHeight()) }
                add { TileCard(tiles.big, Modifier.weight(1f).fillMaxHeight().then(bigMod)) }
                if (s.strip) add { CardBox(Modifier.fillMaxHeight()) { AppsGrid(columns = 2, rows = 3) } }
            }
            for (cell in if (mirror) top.asReversed() else top) cell(this)
        }
        if (!s.strip) {
            Spacer(Modifier.height(m.dp(s.g)))
            val r = m.dp(s.radius)
            val topOnly = RoundedCornerShape(topStart = r, topEnd = r)
            Row(Modifier.height(m.dp(s.bottomH)).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                val bottom = listOf<@Composable RowScope.() -> Unit>(
                    { CardBox(Modifier.weight(1f).fillMaxHeight(), shape = topOnly) { QuickAppsRow() } },
                    { PhoneCard(Modifier.width(m.dp(s.phone)).fillMaxHeight(), topOnly) },
                )
                for (cell in if (mirror) bottom.asReversed() else bottom) cell(this)
            }
        }
    }
}

/**
 * Слоты быстрого ряда, всего n+1: приложения и «+» сразу за ними. Когда приложений n+1 и больше, «+» уступает место
 * последнему из них (Егор 04.11: пятое приложение добавлялось, но пряталось за закреплённым «+»).
 */
sealed interface QuickSlot {
    data class App(val ref: AppRef) : QuickSlot
    data object Plus : QuickSlot
    data object Empty : QuickSlot
}

fun quickSlots(apps: List<AppRef>, n: Int): List<QuickSlot> {
    val shown = AppList.quick(apps, n + 1)
    return List(n + 1) { i ->
        when {
            i < shown.size -> QuickSlot.App(shown[i])
            i == shown.size -> QuickSlot.Plus
            else -> QuickSlot.Empty
        }
    }
}

@Composable
private fun QuickAppsRow() {
    val m = LocalMetrics.current
    val s = m.spec
    val slots = quickSlots(LauncherModel.visibleApps, s.quickApps)
    Row(
        Modifier.fillMaxSize().padding(horizontal = m.dp(s.g), vertical = m.dp(s.g8)),
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4)), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (slot in slots) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                when (slot) {
                    // без подписей: в быстром ряду иконок достаточно, подписи только обрезались («Доба…»)
                    is QuickSlot.App -> AppTile(slot.ref, labelled = false, onLong = { LauncherModel.confirmRemove = slot.ref })
                    QuickSlot.Plus -> PlusTile(labelled = false)
                    QuickSlot.Empty -> Unit
                }
            }
        }
    }
}

/** Узкая лента: сетка приложений без подписей, первые 5 + «+». */
@Composable
private fun AppsGrid(columns: Int, rows: Int) {
    val m = LocalMetrics.current
    val s = m.spec
    val slots = quickSlots(LauncherModel.visibleApps, columns * rows - 1)
    Column(
        Modifier.fillMaxHeight().padding(m.dp(s.g)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                for (c in 0 until columns) {
                    when (val slot = slots.getOrNull(r * columns + c)) {
                        is QuickSlot.App -> AppTile(slot.ref, labelled = false, onLong = { LauncherModel.confirmRemove = slot.ref })
                        QuickSlot.Plus -> PlusTile(labelled = false)
                        else -> Spacer(Modifier.size(m.dp(s.appIc)))
                    }
                }
            }
        }
    }
}

@Composable
fun rememberAppIcon(ref: AppRef): ImageBitmap? {
    // initialValue produceState берёт только при первой композиции: при смене ref (листание страниц,
    // слот переиспользуется) value осталась бы иконкой прошлого приложения - сбрасываем сами.
    val icon by produceState(LauncherModel.cachedIcon(ref), ref) {
        value = LauncherModel.cachedIcon(ref)
        if (value == null) value = withContext(Dispatchers.IO) { LauncherModel.loadIcon(ref) }
    }
    return icon
}

/** Иконка-кружок приложения (+ подпись в быстром ряду). Тап запускает; долгий тап в нижнем ряду главного - «Убрать?». */
@Composable
fun AppTile(
    ref: AppRef, labelled: Boolean, onLong: (() -> Unit)? = null, iconPx: Int? = null,
    selected: Boolean = false, onTap: () -> Unit = { LauncherActions.openApp(ref) },
) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = LauncherModel.label(ref) ?: ref.pkg
    val size = iconPx ?: s.appIc
    Pressable(
        Modifier.clip(RoundedCornerShape(m.dp(s.radius)))
            .then(if (selected) Modifier.border(m.dp(s.b4), LocalAccent.current, RoundedCornerShape(m.dp(s.radius))) else Modifier),
        description = label, onLong = onLong, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
            modifier = Modifier.padding(m.dp(s.g4))) {
            AppCircle(ref, size)
            if (labelled) Txt(label, s.appLbl, Modifier.widthIn(max = m.dp(size * 2)), color = Pal.TEXT, weight = FontWeight.Medium,
                align = TextAlign.Center)
        }
    }
}

@Composable
fun AppCircle(ref: AppRef, sizePx: Int) {
    val m = LocalMetrics.current
    val icon = rememberAppIcon(ref)
    Box(Modifier.size(m.dp(sizePx)).clip(CircleShape).background(Pal.SURFACE_HI)) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
    }
}

/** Закреплённый «+»: открывает выбор приложения для нижнего ряда. */
@Composable
fun PlusTile(labelled: Boolean, iconPx: Int? = null, onTap: () -> Unit = { LauncherActions.openPicker() }) {
    val m = LocalMetrics.current
    val s = m.spec
    val size = iconPx ?: s.appIc
    Pressable(Modifier.clip(RoundedCornerShape(m.dp(s.radius))), description = stringResource(R.string.l_add), onTap = onTap) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
            modifier = Modifier.padding(m.dp(s.g4))) {
            Box(Modifier.size(m.dp(size)).border(m.dp(s.b4), Color(0xFF5F6C99), CircleShape), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.PLUS, s.appSvg, Pal.SEC2, stroke = 2.4f)
            }
            if (labelled) Txt(stringResource(R.string.l_add), s.appLbl, color = Pal.SEC2, weight = FontWeight.Medium)
        }
    }
}
