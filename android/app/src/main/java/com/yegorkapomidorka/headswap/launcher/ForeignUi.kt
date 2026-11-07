package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.yegorkapomidorka.headswap.R
import kotlin.math.roundToInt

/** Панель и оверлей чужого лаунчера всегда тёмные и светлые по тексту: тема лаунчера Headswap их не касается. */
private val BAR_BG = Color(0xFF0E1419)
private val BAR_FG = Color(0xFFF2F5F7)
private val BAR_PRESSED = Color(0x38FFFFFF)
private val BAR_ACTIVE = Color(0x24FFFFFF)
private val RECENTS_SCRIM = Color(0xF00A0F14)

/**
 * Режим чужого лаунчера: окно [EmbedHost.app] на всю ширину над нашей панелью «Назад / Домой / Недавние». Рейки, главного
 * экрана и наших оверлеев (входящий звонок) здесь нет. Окно живёт в корне, как и у наших приложений.
 */
@Composable
internal fun ForeignRoot() {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val rect = model.areaRect
    val appKey = model.appKey
    CompositionLocalProvider(LocalPalette provides Palettes.DARK) {
        val solid = Palettes.DARK.bgSolid
        // окно навигатора здесь не нужно, а держит одну из двух аренд
        LaunchedEffect(Unit) { EmbedHost.want(EmbedHost.nav, null, 0, 0) }
        LaunchedEffect(appKey, rect?.width, rect?.height) {
            EmbedHost.want(EmbedHost.app, appKey, rect?.width?.roundToInt() ?: 0, rect?.height?.roundToInt() ?: 0)
        }
        val status = EmbedHost.app.status
        LaunchedEffect(status) { if (status == EmbedStatus.FAILED) ForeignActions.windowFailed() }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(solid).onGloballyPositioned { model.areaRect = it.boundsInRoot() })
            ForeignBar()
        }
        if (appKey != null) {
            // пока открыты «Недавние», касания в дочернее окно не идут
            // окно ставится пиксельным rect-ом (absoluteOffset): Box-родитель физический, иначе в RTL сдвиг уедет от правого края
            Ltr {
                Box(Modifier.fillMaxSize()) {
                    EmbedLayer(EmbedHost.app, rect, shown = true, interactive = !model.recentsOpen, rounded = false, background = solid,
                        starting = stringResource(R.string.l_starting, model.starting ?: ""), onCardTap = null)
                }
            }
        }
        if (model.recentsOpen) Box(Modifier.fillMaxSize().padding(bottom = m.dp(s.barH))) { RecentsOverlay() }
    }
}

/** Тёмная полоса высотой [LauncherSpec.barH] с тремя кнопками по центру: ◀ ● ■, как трёхкнопочная навигация Android. */
@Composable
private fun ForeignBar() {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    // три кнопки Android (назад, домой, недавние) стоят физически слева направо при любом языке; Row создан внутри Ltr
    Ltr {
        Row(
            Modifier.fillMaxWidth().height(m.dp(s.barH)).background(BAR_BG),
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.barH), Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Ico.NAV_BACK, stringResource(R.string.l_back), active = false) { ForeignActions.press(ForeignDesktop.NavKey.BACK) }
            BarButton(Ico.NAV_HOME, stringResource(R.string.l_home), active = false) { ForeignActions.press(ForeignDesktop.NavKey.HOME) }
            BarButton(Ico.NAV_RECENTS, stringResource(R.string.l_recents), active = model.recentsOpen) {
                ForeignActions.press(ForeignDesktop.NavKey.RECENTS)
            }
        }
    }
}

/** Цель касания: высота панели на две высоты в ширину, подсветка на DOWN. */
@Composable
private fun BarButton(icon: List<String>, description: String, active: Boolean, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Pressable(
        Modifier.width(m.dp(s.barH * 2)).height(m.dp(s.barH)).clip(RoundedCornerShape(m.dp(s.barH / 2)))
            .background(if (active) BAR_ACTIVE else Color.Transparent),
        description = description, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(BAR_PRESSED))
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { SvgIcon(icon, s.barIc, BAR_FG, stroke = 2.2f) }
    }
}

/** «Недавние» поверх окна: иконки и названия, тап открывает приложение, тап мимо закрывает. */
@Composable
private fun RecentsOverlay() {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    val items = model.recents
    Pressable(
        Modifier.fillMaxSize().background(RECENTS_SCRIM),
        description = stringResource(R.string.l_recents), onTap = { model.recentsOpen = false },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(m.dp(s.m))) {
            val iconPx = s.t
            val plan = GridPlan.of(constraints.maxWidth, constraints.maxHeight - s.f1 - s.g, iconPx * 2, iconPx + s.appLbl + s.g * 2, s.g)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                Txt(stringResource(R.string.l_recents), s.f1, color = BAR_FG, weight = FontWeight.SemiBold)
                when {
                    items == null -> Unit
                    items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.l_recents_empty), s.f1, color = Pal.SEC2)
                    }
                    else -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                        for (row in items.take(plan.perPage).chunked(plan.cols)) {
                            Row(Modifier.fillMaxWidth()) {
                                for (ref in row) Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                    AppTile(ref, labelled = true, iconPx = iconPx, onTap = { ForeignActions.openRecent(ref) })
                                }
                                repeat(plan.cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
