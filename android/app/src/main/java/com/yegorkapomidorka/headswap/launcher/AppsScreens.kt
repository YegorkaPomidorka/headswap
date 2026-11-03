package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.yegorkapomidorka.headswap.R

/** Кнопка-«таблетка» высотой T в шапке экранов (первая кнопка «Главный», «Назад» и т.п.). */
@Composable
fun PillButton(text: String, onTap: () -> Unit, modifier: Modifier = Modifier, icon: List<String>? = null, filled: Boolean = true) {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val fg = if (filled) Pal.onAccent(accent) else Pal.TEXT
    Pressable(
        modifier.height(m.dp(s.t)).clip(RoundedCornerShape(m.dp(s.t / 2))).background(if (filled) accent else Pal.SURFACE_HI),
        description = text, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Color(0x33000000)))
        Row(
            Modifier.padding(horizontal = m.dp((s.t * 0.4f).toInt())).height(m.dp(s.t)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8)),
        ) {
            if (icon != null) SvgIcon(icon, s.ic, fg, stroke = 2.4f)
            Txt(text, s.f1, color = fg, weight = FontWeight.Bold)
        }
    }
}

/** Шапка постраничного экрана: слева кнопки, справа «1 / 3» и стрелки ‹ ›. Скролла нигде нет. */
@Composable
fun PagerHeader(page: Int, pages: Int, onPage: (Int) -> Unit, leading: @Composable () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(Modifier.fillMaxWidth().height(m.dp(s.t)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) { leading() }
        Ltr {
            Txt("${page + 1} / $pages", s.f2, color = Pal.SEC, weight = FontWeight.SemiBold, mono = true)
            PageArrow(Ico.CHEV_L, stringResource(R.string.l_prev_page), enabled = page > 0) { onPage(page - 1) }
            PageArrow(Ico.CHEV_R, stringResource(R.string.l_next_page), enabled = page < pages - 1) { onPage(page + 1) }
        }
    }
}

@Composable
private fun PageArrow(icon: List<String>, description: String, enabled: Boolean, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    RoundButton(s.t, description, onTap, enabled = enabled,
        background = if (enabled) Pal.SURFACE_HI else Pal.SURFACE) {
        SvgIcon(icon, s.ic, if (enabled) Pal.TEXT else LocalPalette.current.backOff, stroke = 2.6f)
    }
}

/**
 * Страницы приложений без скролла. [cell] рисует ячейку, null - пустое место.
 * Размер страницы считается по свободной области и крупной цели касания.
 */
@Composable
internal fun <T> PagedApps(
    items: List<T>, page: Int, onPage: (Int) -> Unit, leading: @Composable () -> Unit,
    cell: @Composable (T, iconPx: Int) -> Unit,
) {
    val m = LocalMetrics.current
    val s = m.spec
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padStart = s.g
        val padTop = s.m
        val padEnd = s.m
        val padBottom = s.m
        val availW = constraints.maxWidth - padStart - padEnd
        val availH = constraints.maxHeight - padTop - padBottom - s.t - s.g
        val iconPx = (s.t * 1.0f).toInt()
        val plan = GridPlan.of(availW, availH, iconPx * 2, iconPx + s.appLbl + s.g * 2, s.g)
        val pages = Pager.pageCount(items.size, plan.perPage)
        val current = Pager.clamp(page, items.size, plan.perPage)
        val shown = Pager.slice(items, current, plan.perPage)
        Column(Modifier.fillMaxSize().padding(start = m.dp(padStart), top = m.dp(padTop), end = m.dp(padEnd), bottom = m.dp(padBottom)),
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            PagerHeader(current, pages, onPage, leading)
            Column(Modifier.weight(1f).fillMaxWidth()) {
                for (r in 0 until plan.rows) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (c in 0 until plan.cols) {
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                shown.getOrNull(r * plan.cols + c)?.let { cell(it, iconPx) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** «Все приложения»: все установленные приложения страницами; тап открывает. Назад - кнопка рейки. */
@Composable
fun AllAppsScreen() {
    val model = LauncherModel
    var page by remember { mutableIntStateOf(model.allAppsPage) }
    val items = CatalogLists.all(model.catalog.map { AppRef(it.packageName, it.className) })
    PagedApps(
        items, page, onPage = { page = it; model.allAppsPage = it },
        leading = {
            Txt(stringResource(R.string.l_all_apps), LocalMetrics.current.spec.f1, color = Pal.SEC2, weight = FontWeight.SemiBold)
        },
    ) { ref, iconPx ->
        AppTile(ref, labelled = true, iconPx = iconPx)
    }
}

/** Выбор приложения для нижнего ряда главного из установленных (страницами со стрелками); выбранное уходит в избранное. */
@Composable
fun PickerScreen() {
    val model = LauncherModel
    val f1 = LocalMetrics.current.spec.f1
    var page by remember { mutableIntStateOf(0) }
    val items = CatalogLists.pickable(model.catalog.map { AppRef(it.packageName, it.className) }, model.apps)
    PagedApps(
        items, page, onPage = { page = it },
        leading = {
            Txt(stringResource(R.string.l_choose_app), f1, color = Pal.SEC2)
        },
    ) { ref, iconPx ->
        AppTile(ref, labelled = true, iconPx = iconPx, onTap = { LauncherActions.addApp(ref) })
    }
}

/** Подтверждение «Убрать?» после долгого тапа по приложению. */
@Composable
fun RemoveConfirm() {
    val ref = LauncherModel.confirmRemove ?: return
    val m = LocalMetrics.current
    val s = m.spec
    val label = LauncherModel.label(ref) ?: ref.pkg
    Pressable(Modifier.fillMaxSize().background(Color(0xAA000000)), onTap = { LauncherModel.confirmRemove = null }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Pressable(
                Modifier.clip(RoundedCornerShape(m.dp(s.radius))).background(LocalPalette.current.bgSolid),
                onTap = {},
            ) {
                Column(Modifier.padding(m.dp(s.g * 2)), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                    Ltr {
                        Txt(stringResource(R.string.l_remove_q), s.f1, weight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                            AppCircle(ref, s.appIc)
                            Txt(label, s.f1, color = Pal.SEC2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                            PillButton(stringResource(R.string.l_yes), { LauncherActions.confirmRemove() })
                            PillButton(stringResource(R.string.l_no), { LauncherModel.confirmRemove = null }, filled = false)
                        }
                    }
                }
            }
        }
    }
}
