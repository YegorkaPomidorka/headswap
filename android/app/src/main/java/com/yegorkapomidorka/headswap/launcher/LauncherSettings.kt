package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.about.About
import com.yegorkapomidorka.headswap.about.OpenSource
import com.yegorkapomidorka.headswap.dex.DexPrefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел настроек (колонка слева). ABOUT - «О приложении», вложен в «Система»: своей кнопки нет, подсвечена «Система». */
enum class SettingsPage { LOOK, NAVIGATOR, MUSIC, HOME, SYSTEM, ABOUT }

/** Навигаторы раздела «Навигатор», плитки 2x2 в порядке макета. */
private val NAV_TILES = listOf(
    NavigatorPick.YANDEX_NAVI to R.string.l_nav_yandex_navi,
    "ru.dublgis.dgismobile" to R.string.l_nav_2gis,
    NavigatorPick.YANDEX_MAPS to R.string.l_nav_yandex_maps,
    "com.google.android.apps.maps" to R.string.l_nav_google_maps,
)

/**
 * Размеры экрана настроек: макет Settings.dc.html нарисован на 1280x720 (T 96), остальные разрешения - пропорционально T.
 * Контролы высотой T, но не выше ряда сетки (1280x480: ряд ~66 px при T 92).
 */
private class SettingsDims(s: LauncherSpec, rowH: Int) {
    private val k = s.t / 96.0
    private fun r(v: Double) = max(1, v.roundToInt())
    val catW = r(290 * k)
    val gap = s.g8
    val padStart = r(24 * k)
    val padEnd = r(16 * k)
    val padV = r(10 * k)
    val catIcon = r(34 * k)
    val catGap = r(16 * k)
    val sub = r(s.f2 * 0.8)
    /** Пилюли, переключатель, кнопки: T, а в низком ряду - по высоте ряда. */
    val ctl = min(s.t, rowH - 2 * padV)
    private val kc = ctl / 96.0
    val pillPad = r(26 * kc)
    val trackW = r(120 * kc)
    val trackH = r(64 * kc)
    val knob = r(52 * kc)
    val knobPad = r(6 * kc)
    val switchW = r(136 * kc)
    /** Ступень степпера «Масштаб карты»: влезает «Ещё крупнее» (макет 150 px под цифры, у нас слова). */
    val stepValueW = r(230 * k)
    val tileIcon = min(r(86 * k), rowH - 2 * padV)
    val tileGap = r(20 * k)
    val playerIcon = min(r(68 * k), rowH - 2 * padV - 2 * r(6 * k))
    val ring = max(2, r(3 * k))
    val playerGap = r(20 * k)
}

/** Настройки лаунчера на экране ГУ: разделы слева, карточки справа на одной сетке из 5 рядов, без прокрутки. */
@Composable
fun LauncherSettingsScreen() {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Column(
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.m), bottom = m.dp(s.m)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g8)),
    ) {
        StatusBar(title = stringResource(R.string.l_settings), showNet = false)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = constraints.maxHeight
            val gap = s.g8
            val rowH = SettingsGrid.bottom(0, h, gap) - SettingsGrid.top(0, h, gap)
            val d = SettingsDims(s, rowH)
            // карточка на span рядов: от верха первого ряда до низа последнего - границы совпадают слева и справа
            fun cell(row: Int, span: Int = 1): Modifier {
                val top = SettingsGrid.top(row, h, gap)
                return Modifier.offset(y = m.dp(top)).height(m.dp(SettingsGrid.bottom(row + span - 1, h, gap) - top))
            }
            // кнопку (Pressable) в ряд ставит обёртка: рамку шайбы Pressable рисует до своего modifier, и offset из cell()
            // её не сдвигал - рамки всех разделов рисовались поверх «Вид» (h737)
            @Composable
            fun Placed(row: Int, content: @Composable () -> Unit) =
                Box(cell(row).fillMaxWidth(), propagateMinConstraints = true) { content() }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
                Box(Modifier.width(m.dp(d.catW)).fillMaxHeight()) {
                    for ((i, page) in SettingsPage.entries.filter { it != SettingsPage.ABOUT }.withIndex()) {
                        val selected = model.settingsPage == page || (page == SettingsPage.SYSTEM && model.settingsPage == SettingsPage.ABOUT)
                        Placed(i) { CategoryButton(page, selected, d, Modifier) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (model.settingsPage) {
                        SettingsPage.LOOK -> {
                            ThemeCard(d, cell(0, 2).fillMaxWidth())
                            RailSizeCard(d, cell(2, 2).fillMaxWidth())
                            SwitchCard(stringResource(R.string.l_s_anim), model.animations, d, cell(4).fillMaxWidth()) {
                                model.chooseAnimations(it)
                            }
                        }
                        SettingsPage.NAVIGATOR -> {
                            NavigatorTiles(d, rowH, cell(0, 2).fillMaxWidth())
                            val navPkg = model.navigatorPkg
                            if (navPkg != null) {
                                // h800: подсказки на приборке - машинная часть (Яндекс - только с Плюсом) или теневая AA (Google);
                                // выкл - окно как на телефоне, без подписки и без AA. Своё значение у каждого навигатора.
                                val hints = model.navHints(navPkg)
                                val google = navPkg == NavigatorPick.GOOGLE_MAPS
                                val sub = stringResource(when {
                                    hints && google -> R.string.l_nav_hints_on_aa
                                    hints && NavigatorPick.needsPlus(navPkg) -> R.string.l_nav_hints_on_plus
                                    hints -> R.string.l_nav_hints_on
                                    google -> R.string.l_nav_hints_off_aa
                                    NavigatorPick.needsPlus(navPkg) -> R.string.l_nav_hints_off_plus
                                    else -> R.string.l_nav_hints_off
                                })
                                // низкий ряд (экраны высотой 480): подпись не влезает под название - без неё
                                val subFits = rowH >= ((s.f2 + d.sub) * 1.25).roundToInt() + 4 + 2 * d.padV
                                SwitchCard(stringResource(R.string.l_nav_hints), hints, d, cell(2).fillMaxWidth(), sub = sub.takeIf { subFits }) {
                                    model.chooseNavHints(navPkg, it)
                                }
                                var row = 3
                                // масштаб - dpi своего хоста Car App; навигатору окном и через AA он не нужен
                                if (EmbedHost.nav.carHost) MapScaleCard(navPkg, d, cell(row++).fillMaxWidth())
                                Placed(row++) { RestartNavCard(d, Modifier) } // кнопка - через обёртку, рамка шайбы (h737)
                                // Google через AA: сервер ГУ живёт в Android Auto, перезапуск - в её настройках на телефоне
                                if (hints && google) AaServerHintCard(d, cell(row).fillMaxWidth())
                            }
                        }
                        SettingsPage.MUSIC -> {
                            val players by produceState(emptyList<String>()) {
                                value = withContext(Dispatchers.IO) { PlayerChip.localPlayers(MusicController.players(model.context)) }
                            }
                            val local = model.localPlayer?.takeIf { it in players } ?: players.firstOrNull()
                            PlayersCard(stringResource(R.string.l_s_slot1), players.map { PlayerOpt.App(it) },
                                local?.let { PlayerOpt.App(it) }, d, cell(0).fillMaxWidth()) { model.chooseLocalPlayer((it as PlayerOpt.App).pkg) }
                            val online = PlayerChip.ONLINE.filter { it in model.installedPackages }
                            PlayersCard(stringResource(R.string.l_s_slot2), listOf(PlayerOpt.Last) + online.map { PlayerOpt.App(it) },
                                model.onlinePlayer?.takeIf { it in online }?.let { PlayerOpt.App(it) } ?: PlayerOpt.Last, d, cell(1).fillMaxWidth()) {
                                model.chooseOnlinePlayer((it as? PlayerOpt.App)?.pkg)
                            }
                            SwitchCard(stringResource(R.string.l_s_autoplay), model.autoplay, d, cell(2).fillMaxWidth()) {
                                model.chooseAutoplay(it)
                            }
                        }
                        SettingsPage.HOME -> {
                            // «Раскладка» ужата до 0,87 высоты, чтобы снизу выглядывала шапка «Слои» - видно, что есть
                            // куда листать (макет SettingsHomeLayers, Егор 06.11). «Слои» - два ряда в том же масштабе.
                            val layoutH = (h * HOME_SHRINK).roundToInt()
                            val layersH = ((2 * rowH + gap) * HOME_SHRINK).roundToInt()
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                MapSideCard(d, Modifier.fillMaxWidth().height(m.dp(layoutH)))
                                LayersCard(layersH, Modifier.fillMaxWidth().height(m.dp(layersH)))
                            }
                        }
                        SettingsPage.SYSTEM -> {
                            val info = systemInfo()
                            for ((i, row) in info.withIndex()) InfoCard(row.first, row.second, d, cell(i).fillMaxWidth())
                            Placed(info.size) { AboutLinkCard(d, Modifier) }
                        }
                        SettingsPage.ABOUT -> AboutPanel(d, rowH)
                    }
                }
            }
        }
    }
}

/** Кнопка раздела: значок и название; выбранный - золото .32 с обводкой 2px, остальные - поверхность карточки. */
@Composable
private fun CategoryButton(page: SettingsPage, selected: Boolean, d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = stringResource(when (page) {
        SettingsPage.LOOK -> R.string.l_sec_look
        SettingsPage.NAVIGATOR -> R.string.l_sec_nav
        SettingsPage.MUSIC -> R.string.l_sec_music
        SettingsPage.HOME -> R.string.l_sec_home
        SettingsPage.SYSTEM, SettingsPage.ABOUT -> R.string.l_sec_system
    })
    OptionBox(selected, modifier, RoundedCornerShape(m.dp(s.radius)), description = label, unselected = Pal.SURFACE,
        onTap = { LauncherModel.settingsPage = page; LauncherModel.aboutLicense = null }) {
        Row(Modifier.fillMaxSize().padding(horizontal = m.dp(d.padStart)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(d.catGap))) {
            when (page) {
                SettingsPage.LOOK -> VectorIcon(VIco.HALF_CIRCLE, 24f, d.catIcon, Pal.TEXT)
                SettingsPage.NAVIGATOR -> SvgIcon(Ico.NAV, d.catIcon, Pal.TEXT, fill = true)
                SettingsPage.MUSIC -> SvgIcon(Ico.MUSIC, d.catIcon, Pal.TEXT, stroke = 2f)
                SettingsPage.HOME -> SvgIcon(Ico.LAYOUT, d.catIcon, Pal.TEXT, stroke = 2f)
                SettingsPage.SYSTEM, SettingsPage.ABOUT -> SvgIcon(Ico.SCREEN, d.catIcon, Pal.TEXT, stroke = 2f)
            }
            Txt(label, s.f2, color = Pal.TEXT, weight = FontWeight.SemiBold, ellipsis = false)
        }
    }
}

/**
 * Нажимаемая подложка варианта: выбранный - золото .32 и обводка inset 2px, иначе [unselected] (кнопка или поверхность).
 */
@Composable
private fun OptionBox(
    selected: Boolean, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, description: String?,
    unselected: Color, enabled: Boolean = true, onTap: () -> Unit, content: @Composable () -> Unit,
) {
    val m = LocalMetrics.current
    val accent = LocalAccent.current
    Pressable(
        modifier.clip(shape).background(if (selected) accent.copy(alpha = 0.32f) else unselected)
            .then(if (selected) Modifier.border(m.dp(2), accent, shape) else Modifier),
        enabled = enabled, description = description, onTap = onTap,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        content()
    }
}

/** Подложка карточки справа: поверхность, радиус R. */
@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    val m = LocalMetrics.current
    Box(modifier.clip(RoundedCornerShape(m.dp(m.spec.radius))).background(Pal.SURFACE)) { content() }
}

/** «Тема»: три пилюли строкой под названием и подпись одной строкой. 2 ряда. */
@Composable
private fun ThemeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Card(modifier) {
        Column(
            Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd), top = m.dp(d.padV), bottom = m.dp(d.padV)),
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g8), Alignment.CenterVertically),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
                Txt(stringResource(R.string.l_s_theme), s.f2)
                Txt(stringResource(R.string.l_s_theme_sub), d.sub, color = Pal.SEC, ellipsis = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                for ((choice, res) in listOf(ThemeChoice.DARK to R.string.l_s_theme_dark, ThemeChoice.LIGHT to R.string.l_s_theme_light,
                    ThemeChoice.AUTO to R.string.l_s_theme_auto)) {
                    val label = stringResource(res)
                    OptionBox(model.theme == choice, Modifier.height(m.dp(d.ctl)), RoundedCornerShape(m.dp(d.ctl / 2)), label,
                        unselected = LocalPalette.current.btnSet, onTap = { model.chooseTheme(choice) }) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = m.dp(d.pillPad)), contentAlignment = Alignment.Center) {
                            Txt(label, s.f2, weight = FontWeight.SemiBold, ellipsis = false)
                        }
                    }
                }
            }
        }
    }
}

/** «Боковая панель»: размер рейки, миниатюры вариантов на равную ширину. «Обрезанная» - только на экранах высотой 480. 2 ряда. */
@Composable
private fun RailSizeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Card(modifier) {
        Column(
            Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd), top = m.dp(d.padV), bottom = m.dp(d.padV)),
            verticalArrangement = Arrangement.spacedBy(m.dp(s.g4)),
        ) {
            Txt(stringResource(R.string.l_s_rail), s.f2)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                val options = listOfNotNull(
                    if (s.h480) RailSize.CUT to R.string.l_s_rail_cut else null,
                    RailSize.SMALL to R.string.l_s_rail_small,
                    RailSize.MEDIUM to R.string.l_s_rail_medium,
                    RailSize.NORMAL to R.string.l_s_rail_normal,
                )
                for ((v, res) in options) {
                    val label = stringResource(res)
                    OptionBox(model.railSize == v, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(m.dp(s.radius)), label,
                        unselected = LocalPalette.current.btnSet, onTap = { model.chooseRailSize(v) }) {
                        Column(Modifier.fillMaxSize().padding(m.dp(s.g4)), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                            RailMiniature(v, Modifier.weight(1f).fillMaxWidth())
                            Txt(label, (s.f2 * 0.85).roundToInt(), weight = FontWeight.SemiBold, ellipsis = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Миниатюра главного для варианта панели: слева рейка (кнопки точками, золотой блок навигации внизу, часы чертами),
 * справа музыка с картой и нижний ряд. В «Маленькой» и «Обрезанной» время - чертой сверху слева в правой части.
 */
@Composable
private fun RailMiniature(size: RailSize, modifier: Modifier) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    fun r(v: Int) = max(1, (v * k).roundToInt())
    val pal = LocalPalette.current
    val accent = LocalAccent.current
    val gap = m.dp(r(4))
    val small = RoundedCornerShape(gap)
    val frac = when (size) {
        RailSize.NORMAL -> 0.15f
        RailSize.MEDIUM -> 0.11f
        RailSize.SMALL -> 0.07f
        RailSize.CUT -> 0.08f
    }
    val inStatus = size == RailSize.SMALL || size == RailSize.CUT
    val mark = pal.fg2
    Row(modifier.clip(RoundedCornerShape(m.dp(r(6)))).background(pal.mini)) {
        Column(Modifier.weight(frac).fillMaxHeight().background(pal.btn2), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(0.8f).fillMaxWidth().padding(vertical = gap), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly) {
                when (size) {
                    RailSize.NORMAL -> Box(Modifier.fillMaxWidth(0.6f).height(m.dp(r(2))).background(mark))
                    RailSize.MEDIUM -> {
                        Box(Modifier.fillMaxWidth(0.5f).height(m.dp(r(2))).background(mark))
                        Box(Modifier.fillMaxWidth(0.3f).height(m.dp(r(2))).background(accent))
                        Box(Modifier.fillMaxWidth(0.5f).height(m.dp(r(2))).background(mark.copy(alpha = 0.6f)))
                    }
                    else -> Unit
                }
                repeat(4) { Box(Modifier.size(m.dp(r(4))).clip(CircleShape).background(mark.copy(alpha = 0.6f))) }
            }
            Box(Modifier.weight(0.2f).fillMaxWidth().background(accent))
        }
        Column(Modifier.weight(1f - frac).fillMaxHeight().padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (inStatus) Box(Modifier.fillMaxWidth(0.25f).height(m.dp(r(2))).background(mark))
            Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(0.33f).fillMaxHeight().clip(small).background(Color(0xFF6B5520)))
                Box(Modifier.weight(0.67f).fillMaxHeight().clip(small).background(accent))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(0.67f).fillMaxHeight().clip(small).background(pal.btn2))
                Box(Modifier.weight(0.33f).fillMaxHeight().clip(small).background(pal.btn2))
            }
        }
    }
}

/** «Масштаб карты» навигатора на своём хосте: dpi для приложения = «Авто» × ступень, у каждого навигатора своя. */
@Composable
private fun MapScaleCard(pkg: String, d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val cur = model.mapScale(pkg)
    val steps = listOf(R.string.l_s_scale_smaller, R.string.l_s_theme_auto, R.string.l_s_scale_larger, R.string.l_s_scale_largest)
    // один ряд (h800: место под «Подсказки на приборке»): название, «−», ступень, «+», как степпер макета
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Txt(stringResource(R.string.l_s_map_scale), s.f2, Modifier.weight(1f), ellipsis = false)
            StepButton("−", stringResource(R.string.l_s_scale_smaller), cur > 0, d) { model.chooseMapScale(pkg, cur - 1) }
            Box(Modifier.width(m.dp(d.stepValueW)), contentAlignment = Alignment.Center) {
                Txt(stringResource(steps[cur.coerceIn(0, steps.size - 1)]), s.f2, weight = FontWeight.SemiBold, ellipsis = false)
            }
            StepButton("+", stringResource(R.string.l_s_scale_larger), cur < steps.size - 1, d) { model.chooseMapScale(pkg, cur + 1) }
        }
    }
}

/** Круглая кнопка степпера «−» / «+» размером с контрол ряда. */
@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, d: SettingsDims, onTap: () -> Unit) {
    val m = LocalMetrics.current
    Pressable(Modifier.size(m.dp(d.ctl)).clip(CircleShape).background(LocalPalette.current.btnSet).alpha(if (enabled) 1f else 0.4f),
        enabled = enabled, description = description, onTap = onTap) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Txt(sign, m.spec.f1, weight = FontWeight.SemiBold, ellipsis = false)
        }
    }
}

/**
 * Подсказка без нажатия (Google Карты через теневую AA): сервер ГУ перезапускают в настройках AA на телефоне. Кнопкой
 * на ГУ нельзя: реестр встраивания берёт только приложения со значком в списке запуска, а у настроек AA его нет
 * (стенд 05.11: «Виджет принимает только доступное приложение из списка запуска»).
 */
@Composable
private fun AaServerHintCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    Card(modifier) {
        Box(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), contentAlignment = Alignment.CenterStart) {
            Txt(stringResource(R.string.l_nav_aa_server_hint), d.sub, color = Pal.SEC, maxLines = 2, ellipsis = false)
        }
    }
}

/** Карточка с переключателем: трек 120x64, ручка 52, тач-зона 136x96 (на 1280x720). */
@Composable
private fun SwitchCard(label: String, on: Boolean, d: SettingsDims, modifier: Modifier, sub: String? = null, onChange: (Boolean) -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val pal = LocalPalette.current
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(8)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            if (sub == null) Txt(label, s.f2, Modifier.weight(1f), ellipsis = false)
            else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
                Txt(label, s.f2, ellipsis = false)
                Txt(sub, d.sub, color = Pal.SEC, ellipsis = true)
            }
            Pressable(Modifier.width(m.dp(d.switchW)).height(m.dp(d.ctl)), description = label, onTap = { onChange(!on) }) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(m.dp(d.trackW)).height(m.dp(d.trackH)).clip(RoundedCornerShape(m.dp(d.trackH / 2)))
                            .background(if (on) accent else pal.btnSet).padding(m.dp(d.knobPad)),
                        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(Modifier.size(m.dp(d.knob)).clip(CircleShape).background(if (on) Pal.WHITE else pal.fg3))
                    }
                }
            }
        }
    }
}

/** «Навигатор»: плитки 2x2 на 2 ряда, круг-иконка и название; не установленный - приглушён и не нажимается. */
@Composable
private fun NavigatorTiles(d: SettingsDims, rowH: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val installed = model.installedPackages
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
        for (r in 0 until 2) {
            Row(Modifier.height(m.dp(rowH)).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                for (c in 0 until 2) {
                    val (pkg, res) = NAV_TILES[r * 2 + c]
                    val has = pkg in installed
                    val label = stringResource(res)
                    OptionBox(has && model.navigatorPkg == pkg, Modifier.weight(1f).fillMaxHeight().alpha(if (has) 1f else 0.4f),
                        RoundedCornerShape(m.dp(s.radius)), label, unselected = Pal.SURFACE, enabled = has,
                        onTap = { model.setNavigator(pkg) }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = m.dp(d.tileGap)), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(m.dp(d.tileGap))) {
                            PackageCircle(pkg, d.tileIcon, has)
                            Txt(label, s.f2, weight = FontWeight.SemiBold, ellipsis = false)
                        }
                    }
                }
            }
        }
    }
}

/** Настоящая иконка приложения в круге; не установлено - пустой круг. */
@Composable
private fun PackageCircle(pkg: String, sizePx: Int, installed: Boolean) {
    val m = LocalMetrics.current
    Box(Modifier.size(m.dp(sizePx)).clip(CircleShape).background(Pal.SURFACE_HI)) {
        if (installed) rememberPackageIcon(pkg)?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

private sealed interface PlayerOpt {
    data class App(val pkg: String) : PlayerOpt
    /** «Последний» второго слота: подменяется тем, что слушали последним. */
    data object Last : PlayerOpt
}

/**
 * Слот плеера: название слева, справа иконки строкой; выбранная - двойное кольцо (цвет фона + золото), остальные .6.
 * Если не влезают все - последней идёт «…» на экран со всеми плеерами.
 */
@Composable
private fun PlayersCard(label: String, opts: List<PlayerOpt>, selected: PlayerOpt?, d: SettingsDims, modifier: Modifier, onPick: (PlayerOpt) -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    val measurer = rememberTextMeasurer()
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd))) {
            val labelW = measurer.measure(label, TextStyle(fontSize = m.sp(s.f2), fontFamily = LauncherFonts.family(LocalContext.current, false, FontWeight.Normal))).size.width
            val box = d.playerIcon + 4 * d.ring
            val step = box + max(0, d.playerGap - 2 * d.ring)
            val fit = max(1, (constraints.maxWidth - labelW - s.g + step - box) / step)
            val shown: List<PlayerOpt?> = if (opts.size <= fit) opts else opts.take(fit - 1) + null
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.dp(max(0, d.playerGap - 2 * d.ring)))) {
                Txt(label, s.f2, Modifier.weight(1f), ellipsis = false)
                for (o in shown) PlayerIcon(o, o != null && o == selected, d) { if (o == null) LauncherActions.openPlayers() else onPick(o) }
            }
        }
    }
}

@Composable
private fun PlayerIcon(opt: PlayerOpt?, selected: Boolean, d: SettingsDims, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val accent = LocalAccent.current
    val pal = LocalPalette.current
    val desc = when (opt) {
        is PlayerOpt.App -> LauncherModel.packageLabel(opt.pkg)
        PlayerOpt.Last -> stringResource(R.string.l_s_last)
        null -> stringResource(R.string.l_s_more)
    }
    // кольца снаружи круга: 3px цвета фона, затем 3px золота
    Pressable(Modifier.size(m.dp(d.playerIcon + 4 * d.ring)).clip(CircleShape), description = desc, onTap = onTap) { pressed ->
        Box(Modifier.matchParentSize().then(if (selected) Modifier.border(m.dp(d.ring), accent, CircleShape) else Modifier)
            .padding(m.dp(d.ring)).then(if (selected) Modifier.border(m.dp(d.ring), pal.bgSolid, CircleShape) else Modifier)
            .padding(m.dp(d.ring)).alpha(if (selected) 1f else 0.6f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Pal.SURFACE_HI), contentAlignment = Alignment.Center) {
                when (opt) {
                    is PlayerOpt.App -> rememberPackageIcon(opt.pkg)?.let { Image(it, null, Modifier.fillMaxSize()) }
                    PlayerOpt.Last -> SvgIcon(Ico.REPEAT, d.playerIcon / 2, Pal.TEXT, stroke = 2.6f)
                    null -> Txt("…", m.spec.f2, color = Pal.TEXT, weight = FontWeight.Bold)
                }
            }
            if (pressed) Box(Modifier.matchParentSize().clip(CircleShape).background(Pal.SURFACE_PRESSED))
        }
    }
}

/** Вариант «Раскладки»: четыре плашки с картой справа/слева или две плашки 2:1 / 1:2. */
private data class LayoutOpt(val layout: HomeLayout, val left: Boolean, val label: String)

/**
 * «Раскладка» главного на весь экран (5 рядов): сеткой 2x2 сверху четыре плашки «Справа» и «Слева», снизу две плашки
 * «2 : 1» и «1 : 2». Один выбор на все четыре. Что в плашках, выбирают на главном («Слои», ⇄), здесь - форма,
 * а ниже, под прокруткой, - что листают «Слои» ([LayersCard]).
 */
@Composable
private fun MapSideCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96.0
    fun r(v: Int) = max(1, (v * k).roundToInt())
    val model = LauncherModel
    val rows = listOf(
        listOf(LayoutOpt(HomeLayout.QUAD, false, stringResource(R.string.l_s_right)), LayoutOpt(HomeLayout.QUAD, true, stringResource(R.string.l_s_left))),
        listOf(LayoutOpt(HomeLayout.WIDE_LEFT, false, "2 : 1"), LayoutOpt(HomeLayout.WIDE_RIGHT, false, "1 : 2")),
    )
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(start = m.dp(r(24)), end = m.dp(r(24)), top = m.dp(r(14)), bottom = m.dp(r(20))),
            verticalArrangement = Arrangement.spacedBy(m.dp(r(14)))) {
            Txt(stringResource(R.string.l_s_layout), s.f2)
            for (row in rows) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(r(24)))) {
                for (opt in row) {
                    val selected = model.homeLayout == opt.layout && (opt.layout != HomeLayout.QUAD || model.mapLeft == opt.left)
                    OptionBox(selected, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(m.dp(s.radius)), opt.label,
                        unselected = Color.Transparent, onTap = { model.chooseHomeLayout(opt.layout, opt.left) }) {
                        Column(Modifier.fillMaxSize().padding(m.dp(r(10))), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(m.dp(r(8)))) {
                            if (opt.layout == HomeLayout.QUAD) HomeMiniature(opt.left, r(6), Modifier.weight(1f).fillMaxWidth())
                            else TwoMiniature(opt.layout == HomeLayout.WIDE_LEFT, r(6), Modifier.weight(1f).fillMaxWidth())
                            Txt(opt.label, s.f2, weight = FontWeight.SemiBold, ellipsis = false)
                        }
                    }
                }
            }
        }
    }
}

/** Доля высоты экрана настроек под «Раскладку» в разделе «Экран»: остаток - шапка «Слоёв» под прокрутку. */
private const val HOME_SHRINK = 0.87

/**
 * «Слои» под «Раскладкой»: значок и подпись, ниже - что листает кнопка «Слои», иконками строкой, как нижняя панель
 * главного. Крестик убирает (последнее не убирается), «+» добавляет: одно свободное - сразу, несколько - показывает их
 * приглушёнными, тап по нужному добавляет. До [LayerList.MAX].
 */
@Composable
private fun LayersCard(heightPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96.0
    fun r(v: Int) = max(1, (v * k).roundToInt())
    val model = LauncherModel
    var adding by remember { mutableStateOf(false) }
    val free = model.freeLayers
    val padV = r(14)
    val headIcon = r(36)
    val headH = max(headIcon, (s.f2 * 1.25).roundToInt())
    val icon = max(1, min(r(112), heightPx - 2 * padV - headH - r(18)))
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = m.dp(r(24)), vertical = m.dp(padV)),
            verticalArrangement = Arrangement.spacedBy(m.dp(r(18)), Alignment.CenterVertically)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(r(14)))) {
                SvgIcon(Ico.LAYERS, headIcon, Pal.TEXT, stroke = 2f)
                Txt(stringResource(R.string.l_s_layers), s.f2, ellipsis = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(r(28)))) {
                for (v in model.layers) LayerSlot(v, icon, removable = model.layers.size > 1) { model.removeLayer(v) }
                if (adding && free.size > 1) {
                    for (v in free) LayerSlot(v, icon, candidate = true) { model.addLayer(v); adding = false }
                } else if (free.isNotEmpty()) {
                    PlusTile(labelled = false, iconPx = icon) { if (free.size == 1) model.addLayer(free[0]) else adding = true }
                }
            }
        }
    }
}

/** Иконка слоя: круг со значком; [removable] - крестик в углу (тап убирает), [candidate] - приглушён, тап добавляет. */
@Composable
private fun LayerSlot(v: CardView, sizePx: Int, removable: Boolean = false, candidate: Boolean = false, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    fun r(x: Int) = max(1, (x * k).roundToInt())
    val pal = LocalPalette.current
    val name = layerName(v)
    val desc = if (candidate) stringResource(R.string.l_s_layer_add, name) else stringResource(R.string.l_s_layer_remove, name)
    val yt = if (v == CardView.YOUTUBE) rememberPackageIcon(FullscreenApp.YOUTUBE) else null
    val (bg, fg) = when (v) {
        CardView.NAVIGATOR -> LocalAccent.current to Pal.ON_GOLD
        CardView.YOUTUBE -> Color.White to Color(0xFFFF0033)
        CardView.MUSIC -> Color(0xFF6B5520) to Color(0xFFF2E2B0)
        CardView.CLOCK -> pal.btn2 to Pal.TEXT
    }
    Box(Modifier.size(m.dp(sizePx))) {
        // тап по иконке слоя ничего не делает - убирает только крестик (Егор 06.11); кандидат добавляется тапом по иконке
        Pressable(Modifier.matchParentSize().clip(CircleShape).alpha(if (candidate) 0.6f else 1f), enabled = candidate,
            description = if (candidate) desc else name, onTap = onTap) { pressed ->
            Box(Modifier.matchParentSize().background(bg), contentAlignment = Alignment.Center) {
                when (v) {
                    CardView.NAVIGATOR -> SvgIcon(Ico.NAV, sizePx / 2, fg, fill = true)
                    CardView.YOUTUBE -> if (yt != null) Image(yt, null, Modifier.fillMaxSize()) else SvgIcon(Ico.VIDEO, sizePx * 3 / 5, fg, stroke = 2.4f)
                    CardView.MUSIC -> SvgIcon(Ico.MUSIC, sizePx / 2, fg, stroke = 2.4f)
                    CardView.CLOCK -> SvgIcon(Ico.CLOCK, sizePx / 2, fg, stroke = 2.4f)
                }
            }
            if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        }
        if (removable) {
            // крестик - отдельная кнопка: зона касания 56 (под палец на ГУ), видимый кружок 38
            Pressable(Modifier.align(Alignment.TopEnd).offset(x = m.dp(r(13)), y = m.dp(-r(13))).size(m.dp(r(56))).clip(CircleShape),
                description = desc, onTap = onTap) { pressed ->
                Box(Modifier.align(Alignment.Center).size(m.dp(r(38))).clip(CircleShape)
                    .background(if (pressed) Pal.SURFACE_PRESSED else pal.bgSolid)
                    .border(m.dp(max(1, r(2))), Pal.SEC, CircleShape), contentAlignment = Alignment.Center) {
                    SvgIcon(Ico.CLOSE, r(20), Pal.SEC2, stroke = 3f)
                }
            }
        }
    }
}

@Composable
private fun layerName(v: CardView): String = when (v) {
    CardView.NAVIGATOR -> stringResource(R.string.l_sec_nav)
    CardView.YOUTUBE -> "YouTube"
    CardView.MUSIC -> stringResource(R.string.l_sec_music)
    CardView.CLOCK -> stringResource(R.string.l_s_clock)
}

/** Большая плашка миниатюры: золото и всё, что в ней бывает, - навигатор, YouTube, часы. */
@Composable
private fun RowScope.BigMiniCell(weight: Float, shape: RoundedCornerShape) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    Row(Modifier.weight(weight).fillMaxHeight().clip(shape).background(LocalAccent.current),
        horizontalArrangement = Arrangement.spacedBy(m.dp(max(1, (10 * k).roundToInt())), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(Ico.NAV, (24 * k).roundToInt(), Pal.ON_GOLD, fill = true)
        SvgIcon(Ico.VIDEO, (26 * k).roundToInt(), Pal.ON_GOLD, stroke = 2.4f)
        SvgIcon(Ico.CLOCK, (24 * k).roundToInt(), Pal.ON_GOLD, stroke = 2.4f)
    }
}

@Composable
private fun RowScope.MusicMiniCell(weight: Float, shape: RoundedCornerShape) {
    val k = LocalMetrics.current.spec.t / 96.0
    Box(Modifier.weight(weight).fillMaxHeight().clip(shape).background(Color(0xFF6B5520)), contentAlignment = Alignment.Center) {
        SvgIcon(Ico.MUSIC, (26 * k).roundToInt(), Color(0xFFF2E2B0), stroke = 2.4f)
    }
}

/** Миниатюра двух плашек: большая (2) и музыка (1); при [bigLeft] большая слева. Рейку не рисуем (Егор 05.11). */
@Composable
private fun TwoMiniature(bigLeft: Boolean, gapPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    val small = RoundedCornerShape(m.dp(gapPx))
    Row(modifier.clip(RoundedCornerShape(m.dp(max(1, (10 * k).roundToInt())))).background(LocalPalette.current.mini)
        .padding(m.dp(gapPx)), horizontalArrangement = Arrangement.spacedBy(m.dp(gapPx))) {
        if (bigLeft) { BigMiniCell(2f, small); MusicMiniCell(1f, small) } else { MusicMiniCell(1f, small); BigMiniCell(2f, small) }
    }
}

/** Миниатюра четырёх плашек: музыка, большая плашка, приложения и телефон; при [left] ряды зеркальные. Без рейки. */
@Composable
private fun HomeMiniature(left: Boolean, gapPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    val pal = LocalPalette.current
    val gap = m.dp(gapPx)
    val small = RoundedCornerShape(m.dp(gapPx))
    Row(modifier.clip(RoundedCornerShape(m.dp(max(1, (10 * k).roundToInt())))).background(pal.mini)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.weight(2f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                if (left) { BigMiniCell(0.67f, small); MusicMiniCell(0.33f, small) } else { MusicMiniCell(0.33f, small); BigMiniCell(0.67f, small) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                val cells = listOf<@Composable androidx.compose.foundation.layout.RowScope.() -> Unit>(
                    { Box(Modifier.weight(0.67f).fillMaxHeight().clip(small).background(pal.btn2)) },
                    {
                        Box(Modifier.weight(0.33f).fillMaxHeight().clip(small).background(pal.btn2), contentAlignment = Alignment.Center) {
                            SvgIcon(Ico.PHONE, (22 * k).roundToInt(), pal.fg2, stroke = 2.4f)
                        }
                    },
                )
                for (c in if (left) cells.asReversed() else cells) c(this)
            }
        }
    }
}

/** Строка «Системы»: название слева, значение справа моноширинным вторичным цветом. */
@Composable
private fun InfoCard(label: String, value: String, d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = m.dp(d.padStart)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            // название целиком, значение занимает остаток и прижато вправо
            Txt(label, s.f2, ellipsis = false)
            Txt(value, s.f2, Modifier.weight(1f), color = Pal.SEC, weight = FontWeight.SemiBold, mono = true,
                align = androidx.compose.ui.text.style.TextAlign.End, ellipsis = false)
        }
    }
}

/** Сведения «Системы» из текущей сессии; ничего не меняется (режим видео - в настройках самого Headswap). Версия - карточкой [AboutLinkCard]. */
@Composable
private fun systemInfo(): List<Pair<String, String>> {
    val dash = "—"
    // Honda Vezel шлёт пустой HU_INFO: тогда имя - канал из STATISTIC_INFO, версия CarLife - его versionName
    val name = CarLifeState.huName ?: CarLifeState.huChannel?.let { stringResource(R.string.l_s_hu_channel, it) }
    val version = CarLifeState.huCarlifeVersion ?: CarLifeState.huStatVersion
    val hu = name?.let { n -> version?.let { "$n · CarLife $it" } ?: n } ?: dash
    val link = if (!CarLifeState.running) dash else if (CarLifeState.wirelessSession) "Wi-Fi" else "USB"
    val fps = DexPrefs(LauncherModel.context).desktopFps.fixed ?: 30
    val res = CarLifeState.videoMode?.let { stringResource(R.string.l_s_fps, "${it.outW}×${it.outH}", fps) } ?: dash
    return listOf(
        stringResource(R.string.l_s_hu) to hu,
        stringResource(R.string.l_s_link) to link,
        // телефон кодирует свой экран один раз и отдаёт ГУ готовый H.264 - по сути прямой проброс
        stringResource(R.string.l_s_video) to stringResource(R.string.set_direct_title),
        stringResource(R.string.l_s_res) to res,
    )
}

/**
 * Карточка-действие: подсветка, пока палец на ней, и вспышка акцентом на 500 мс после касания - короткий тап
 * (мышь стенда, быстрый DOWN/UP ГУ) иначе не видно (Егор 05.11, «Перезапустить навигатор»). Вспышка - как остальные
 * анимации кнопок, выключается настройкой «Анимации кнопок».
 */
@Composable
private fun ActionCard(label: String, modifier: Modifier, onTap: () -> Unit, content: @Composable () -> Unit) {
    val m = LocalMetrics.current
    val accent = LocalAccent.current
    val flash = rememberPressAnim(500)
    Pressable(modifier.clip(RoundedCornerShape(m.dp(m.spec.radius))).background(Pal.SURFACE), description = label,
        onPress = { flash.play() }, onTap = onTap) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Box(Modifier.matchParentSize().background(accent.copy(alpha = 0.32f * (1f - flash.progress))))
        content()
    }
}

/** «Перезапустить навигатор»: новая сессия своего хоста или окно на свежей текстуре. */
@Composable
private fun RestartNavCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = stringResource(R.string.l_nav_restart)
    ActionCard(label, modifier, onTap = { EmbedHost.restartNav() }) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, s.f2, ellipsis = false)
        }
    }
}

@Composable
private fun AboutLinkCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = stringResource(R.string.l_s_version)
    val value = "Headswap ${BuildConfig.VERSION_NAME} · ${stringResource(R.string.about_title)}"
    Pressable(modifier.clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE), description = value,
        onTap = { LauncherModel.settingsPage = SettingsPage.ABOUT }) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Txt(label, s.f2, ellipsis = false)
            Txt(value, s.f2, Modifier.weight(1f), color = Pal.SEC, weight = FontWeight.SemiBold, mono = true,
                align = androidx.compose.ui.text.style.TextAlign.End)
            SvgIcon(Ico.CHEV_R, d.catIcon, Pal.SEC, stroke = 2.6f)
        }
    }
}

/**
 * «О приложении» без шапки (назад - «Назад» рейки и аппаратная): авторы и лицензии плитками в две колонки. Плитки
 * сжимаются, чтобы уместились все; ниже двух строк текста не сжимаются - тогда список прокручивается. Тап по лицензии
 * открывает полный текст с прокруткой.
 */
@Composable
private fun AboutPanel(d: SettingsDims, rowH: Int) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val ctx = LocalContext.current
    val open = model.aboutLicense?.let { About.licenses.getOrNull(it) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = d.gap
        if (open != null) {
            val text = remember(open.asset) { About.reflow(About.readText(ctx, open.asset)) }
            Card(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = m.dp(d.padStart), end = m.dp(d.padEnd), top = m.dp(d.padV), bottom = m.dp(d.padV)),
                    verticalArrangement = Arrangement.spacedBy(m.dp(d.padV))) {
                    Txt(open.title, s.f2, Modifier.fillMaxWidth(), weight = FontWeight.SemiBold, maxLines = Int.MAX_VALUE)
                    Txt("${open.license} · ${open.holder}", d.sub, Modifier.fillMaxWidth(), color = Pal.SEC, maxLines = Int.MAX_VALUE)
                    Txt(open.url, d.sub, Modifier.fillMaxWidth(), color = Pal.SEC, mono = true, maxLines = Int.MAX_VALUE)
                    Txt(text.ifEmpty { stringResource(R.string.about_license_unavailable) }, d.sub, Modifier.fillMaxWidth(),
                        color = Pal.SEC2, maxLines = Int.MAX_VALUE)
                }
            }
        } else {
            val cols = 2
            val authorRows = (About.authors.size + cols - 1) / cols
            val licenseRows = (About.licenses.size + cols - 1) / cols
            val rows = authorRows + licenseRows
            val captionH = d.sub + s.g4
            // две подписи («Авторы», «Открытый код») и зазоры между всеми рядами
            val fitH = (constraints.maxHeight - 2 * captionH - (rows + 1) * gap) / rows
            val minH = ((s.f2 + d.sub) * 1.35).roundToInt() + 2 * s.g8
            val tileH = fitH.coerceIn(minH, rowH)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                Txt(stringResource(R.string.about_authors), d.sub, color = Pal.SEC, weight = FontWeight.SemiBold)
                for (row in About.authors.chunked(cols)) {
                    Row(Modifier.fillMaxWidth().height(m.dp(tileH)), horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                        for (a in row) AuthorCard(stringResource(a.nameRes), a.handle, d, Modifier.weight(1f).fillMaxHeight())
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Txt(stringResource(R.string.about_opensource), d.sub, color = Pal.SEC, weight = FontWeight.SemiBold)
                for (row in About.licenses.chunked(cols)) {
                    Row(Modifier.fillMaxWidth().height(m.dp(tileH)), horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                        for (lic in row) LicenseCard(lic, d, Modifier.weight(1f).fillMaxHeight()) { model.aboutLicense = About.licenses.indexOf(lic) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Автор: имя крупно, ник моноширинным вторичным цветом. Не нажимается: браузера на ГУ нет. */
@Composable
private fun AuthorCard(name: String, handle: String, d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = m.dp(d.padStart)), verticalArrangement = Arrangement.Center) {
            Txt(name, s.f2, weight = FontWeight.SemiBold)
            Txt(handle, d.sub, color = Pal.SEC, mono = true)
        }
    }
}

/** Лицензия плиткой: «название версия», ниже лицензия и правообладатель; адрес проекта - в полном тексте. */
@Composable
private fun LicenseCard(lic: OpenSource, d: SettingsDims, modifier: Modifier, onTap: () -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Pressable(modifier.clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE), description = lic.title, onTap = onTap) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Column(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalArrangement = Arrangement.Center) {
            Txt(lic.title, s.f2, weight = FontWeight.SemiBold)
            Txt("${lic.license} · ${lic.holder}", d.sub, color = Pal.SEC)
        }
    }
}
