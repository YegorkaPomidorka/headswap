package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «Слои» в настройках «Экран», под «Раскладкой»: значок и подпись, ниже - что листает кнопка «Слои», иконками
 * строкой, как нижняя панель главного. Крестик убирает (последний не убирается), «+» открывает выбор: убранные
 * встроенные виды и установленные приложения ([LayerPickerScreen]). До [LayerList.MAX].
 */
@Composable
internal fun LayersCard(heightPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96.0
    fun r(v: Int) = max(1, (v * k).roundToInt())
    val model = LauncherModel
    val padV = r(14)
    val headIcon = r(36)
    val headH = max(headIcon, (s.f2 * 1.25).roundToInt())
    val icon = max(1, min(r(112), heightPx - 2 * padV - headH - r(18)))
    Box(modifier.clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE)) {
        Column(Modifier.fillMaxSize().padding(horizontal = m.dp(r(24)), vertical = m.dp(padV)),
            verticalArrangement = Arrangement.spacedBy(m.dp(r(18)))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(r(14)))) {
                SvgIcon(Ico.LAYERS, headIcon, Pal.TEXT, stroke = 2f)
                Txt(stringResource(R.string.l_s_layers), s.f2, ellipsis = false)
            }
            // ряд фиксированной высоты: «+» и иконки одного размера, шапка не скачет при добавлении (Егор 06.11)
            Row(Modifier.height(m.dp(icon)), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.dp(r(28)))) {
                val removable = model.layers.size > 1
                for (l in model.layers) LayerSlot(l, icon, removable) { model.removeLayer(l) }
                if (model.layers.size < LayerList.MAX) LayerPlus(icon) { LauncherActions.openLayerPicker() }
            }
        }
    }
}

/** Иконка слоя; тап по ней ничего не делает - убирает только крестик в углу (Егор 06.11). */
@Composable
private fun LayerSlot(l: Layer, sizePx: Int, removable: Boolean, onRemove: () -> Unit) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    fun r(x: Int) = max(1, (x * k).roundToInt())
    val pal = LocalPalette.current
    val name = layerName(l)
    Box(Modifier.size(m.dp(sizePx))) {
        LayerCircle(l, sizePx, Modifier.align(Alignment.Center))
        if (removable) {
            // крестик - отдельная кнопка: зона касания 56 (под палец на ГУ), видимый кружок 38
            Pressable(Modifier.align(Alignment.TopEnd).offset(x = m.dp(r(13)), y = m.dp(-r(13))).size(m.dp(r(56))).clip(CircleShape),
                description = stringResource(R.string.l_s_layer_remove, name), onTap = onRemove) { pressed ->
                Box(Modifier.align(Alignment.Center).size(m.dp(r(38))).clip(CircleShape)
                    .background(if (pressed) Pal.SURFACE_PRESSED else pal.bgSolid)
                    .border(m.dp(max(1, r(2))), Pal.SEC, CircleShape), contentAlignment = Alignment.Center) {
                    SvgIcon(Ico.CLOSE, r(20), Pal.SEC2, stroke = 3f)
                }
            }
        }
    }
}

/** Круг слоя: встроенный вид - значок на своём цвете, приложение - его иконка. */
@Composable
internal fun LayerCircle(l: Layer, sizePx: Int, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val pal = LocalPalette.current
    when (l) {
        is Layer.App -> {
            val icon = rememberPackageIcon(l.pkg)
            Box(modifier.size(m.dp(sizePx)).clip(CircleShape).background(Pal.SURFACE_HI)) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            }
        }
        is Layer.View -> {
            val (bg, fg) = when (l.view) {
                CardView.NAVIGATOR -> LocalAccent.current.let { it to Pal.onAccent(it) }
                CardView.MUSIC -> Color(0xFF6B5520) to Color(0xFFF2E2B0)
                else -> pal.btn2 to Pal.TEXT
            }
            Box(modifier.size(m.dp(sizePx)).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                when (l.view) {
                    CardView.NAVIGATOR -> SvgIcon(Ico.NAV, sizePx / 2, fg, fill = true)
                    CardView.MUSIC -> SvgIcon(Ico.MUSIC, sizePx / 2, fg, stroke = 2.4f)
                    else -> SvgIcon(Ico.CLOCK, sizePx / 2, fg, stroke = 2.4f)
                }
            }
        }
    }
}

/** «+» слоёв: как на нижней панели (обводка 0xFF5F6C99, плюс), но ровно [sizePx], без полей и подписи. */
@Composable
private fun LayerPlus(sizePx: Int, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Pressable(Modifier.size(m.dp(sizePx)).clip(CircleShape), description = stringResource(R.string.l_add), onTap = onTap) { pressed ->
        Box(Modifier.matchParentSize().border(m.dp(s.b4), Color(0xFF5F6C99), CircleShape)
            .then(if (pressed) Modifier.background(Pal.SURFACE_PRESSED) else Modifier), contentAlignment = Alignment.Center) {
            SvgIcon(Ico.PLUS, sizePx * 2 / 5, Pal.SEC2, stroke = 2.4f)
        }
    }
}

@Composable
internal fun layerName(l: Layer): String = when (l) {
    is Layer.App -> remember(l.pkg) { LauncherModel.packageLabel(l.pkg) }
    is Layer.View -> when (l.view) {
        CardView.NAVIGATOR -> stringResource(R.string.l_sec_nav)
        CardView.MUSIC -> stringResource(R.string.l_sec_music)
        else -> stringResource(R.string.l_s_clock)
    }
}

/**
 * Выбор слоя по «+» в «Слоях»: экран Picker в режиме слоёв. Сначала убранные встроенные виды (навигатор, музыка,
 * часы), потом установленные приложения - без уже выбранных и без навигатора. Тап добавляет и возвращает в настройки.
 */
@Composable
internal fun LayerPickerScreen() {
    val model = LauncherModel
    val s = LocalMetrics.current.spec
    var page by remember { mutableIntStateOf(0) }
    val apps = model.catalog.map { AppRef(it.packageName, it.className) }.distinctBy { it.pkg }
    val pickable = LayerList.pickableApps(apps.map { it.pkg }, model.layers, model.navigatorPkg).toSet()
    val items: List<Pair<Layer, AppRef?>> =
        LayerList.freeBuiltins(model.layers).map { Layer.View(it) to null } +
            CatalogLists.all(apps).filter { it.pkg in pickable }.map { Layer.App(it.pkg) to it }
    PagedApps(
        items, page, onPage = { page = it },
        leading = { Txt(stringResource(R.string.l_choose_app), s.f1, color = Pal.SEC2) },
    ) { (layer, ref), iconPx ->
        if (ref != null) AppTile(ref, labelled = true, iconPx = iconPx, onTap = { LauncherActions.addLayer(layer) })
        else BuiltinLayerTile(layer, iconPx)
    }
}

/** Плитка встроенного вида в выборе слоя: круг и подпись, как [AppTile]. */
@Composable
private fun BuiltinLayerTile(l: Layer, iconPx: Int) {
    val m = LocalMetrics.current
    val s = m.spec
    val name = layerName(l)
    Pressable(Modifier.clip(RoundedCornerShape(m.dp(s.radius))), description = name, onTap = { LauncherActions.addLayer(l) }) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Column(Modifier.padding(m.dp(s.g4)), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
            LayerCircle(l, iconPx)
            Txt(name, s.appLbl, color = Pal.TEXT, weight = FontWeight.Medium)
        }
    }
}
