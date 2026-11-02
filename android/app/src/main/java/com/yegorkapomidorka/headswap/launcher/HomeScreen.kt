package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Подложка карточки главного экрана. */
@Composable
fun CardBox(modifier: Modifier = Modifier, background: Color = Pal.SURFACE, content: @Composable BoxScope.() -> Unit) {
    val m = LocalMetrics.current
    Box(modifier.clip(RoundedCornerShape(m.dp(m.spec.radius))).background(background)) {
        Ltr { content() }
    }
}

/** Главный экран: строка статуса, верхний ряд (музыка | навигация) и нижний (приложения | телефон). */
@Composable
fun HomeScreen() {
    val m = LocalMetrics.current
    val s = m.spec
    Column(Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.m), bottom = m.dp(s.m))) {
        StatusBar()
        Spacer(Modifier.height(m.dp(s.g8)))
        Row(Modifier.weight(if (s.strip) 1f else 2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            MusicCard(Modifier.width(m.dp(s.music)).fillMaxHeight())
            NavCard(Modifier.weight(1f).fillMaxHeight())
            if (s.strip) {
                CardBox(Modifier.fillMaxHeight()) { AppsGrid(columns = 2, rows = 3) }
            }
        }
        if (!s.strip) {
            Spacer(Modifier.height(m.dp(s.g)))
            Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = m.dp(s.bottomMin)),
                horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                CardBox(Modifier.weight(1f).fillMaxHeight()) { QuickAppsRow() }
                PhoneCard(Modifier.width(m.dp(s.phone)).fillMaxHeight())
            }
        }
    }
}

/** Слоты быстрого ряда: первые n приложений списка и «+» сразу за ними. */
sealed interface QuickSlot {
    data class App(val ref: AppRef) : QuickSlot
    data object Plus : QuickSlot
    data object Empty : QuickSlot
}

fun quickSlots(apps: List<AppRef>, n: Int): List<QuickSlot> {
    val shown = AppList.quick(apps, n)
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
                    is QuickSlot.App -> AppTile(slot.ref, labelled = true)
                    QuickSlot.Plus -> PlusTile(labelled = true)
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
                        is QuickSlot.App -> AppTile(slot.ref, labelled = false)
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
    val icon by produceState(LauncherModel.cachedIcon(ref), ref) {
        if (value == null) value = withContext(Dispatchers.IO) { LauncherModel.loadIcon(ref) }
    }
    return icon
}

/** Иконка-кружок приложения (+ подпись в быстром ряду). Тап запускает, долгий тап - только на экране «Все приложения». */
@Composable
fun AppTile(
    ref: AppRef, labelled: Boolean, onLong: (() -> Unit)? = null, iconPx: Int? = null,
    onTap: () -> Unit = { LauncherActions.openApp(ref) },
) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = LauncherModel.label(ref) ?: ref.pkg
    val size = iconPx ?: s.appIc
    Pressable(
        Modifier.clip(RoundedCornerShape(m.dp(s.radius))), description = label,
        onLong = onLong, onTap = onTap,
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
    Box(Modifier.size(m.dp(sizePx)).clip(CircleShape).background(Color(0xFF2E3C45))) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
    }
}

/** Закреплённый «+»: открывает «Все приложения». */
@Composable
fun PlusTile(labelled: Boolean, iconPx: Int? = null, onTap: () -> Unit = { LauncherActions.openAllApps() }) {
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
