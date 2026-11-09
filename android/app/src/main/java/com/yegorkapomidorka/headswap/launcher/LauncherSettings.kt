package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.LaunchedEffect
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
/** Разделы настроек; [COLORS] и [ABOUT] - вложенные страницы «Вида» и «Системы», своей кнопки слева у них нет. */
enum class SettingsPage { LOOK, NAVIGATOR, MUSIC, HOME, SYSTEM, ABOUT, COLORS }

/** Навигаторы раздела «Навигатор», плитки по две в ряд в порядке макета. */
private val NAV_TILES = listOf(
    NavigatorPick.YANDEX_NAVI to R.string.l_nav_yandex_navi,
    NavigatorPick.DGIS to R.string.l_nav_2gis,
    NavigatorPick.YANDEX_MAPS to R.string.l_nav_yandex_maps,
    NavigatorPick.GOOGLE_MAPS to R.string.l_nav_google_maps,
)
/** Арабский интерфейс (h1003): Google Карты и Waze, один ряд. */
private val NAV_TILES_AR = listOf(
    NavigatorPick.GOOGLE_MAPS to R.string.l_nav_google_maps,
    NavigatorPick.WAZE to R.string.l_nav_waze,
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
                    for ((i, page) in SettingsPage.entries.filter { it != SettingsPage.ABOUT && it != SettingsPage.COLORS }.withIndex()) {
                        val selected = model.settingsPage == page || (page == SettingsPage.SYSTEM && model.settingsPage == SettingsPage.ABOUT) ||
                            (page == SettingsPage.LOOK && model.settingsPage == SettingsPage.COLORS)
                        Placed(i) { CategoryButton(page, selected, d, Modifier) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (model.settingsPage) {
                        SettingsPage.LOOK -> {
                            ThemeCard(d, cell(0).fillMaxWidth())
                            Placed(1) { ColorsLinkCard(d, Modifier) }
                            RailSizeCard(d, cell(2, 2).fillMaxWidth())
                            // ряд 4 пополам: анимации | заряд телефона в строке статуса (свободных рядов нет)
                            Row(cell(4).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                                SwitchCard(stringResource(R.string.l_s_anim), model.animations, d, Modifier.weight(1f).fillMaxHeight()) {
                                    model.chooseAnimations(it)
                                }
                                SwitchCard(stringResource(R.string.l_s_battery), model.showBattery, d, Modifier.weight(1f).fillMaxHeight()) {
                                    model.chooseShowBattery(it)
                                }
                            }
                        }
                        SettingsPage.COLORS -> {
                            ColorsModeCard(d, cell(0).fillMaxWidth())
                            val set = if (model.colorsEditDay) model.dayColors else model.nightColors
                            val light = model.colorsEditDay
                            ColorSliderCard(stringResource(R.string.l_s_colors_accent), set.accentHue, ColorSet.HUE_NEUTRAL, d, cell(1).fillMaxWidth(),
                                hueTrack(light, accent = true)) { model.editColors(set.copy(accentHue = it)) }
                            ColorSliderCard(stringResource(R.string.l_s_colors_bg_hue), set.bgHue, ColorSet.HUE_NEUTRAL, d, cell(2).fillMaxWidth(),
                                hueTrack(light, accent = false)) { model.editColors(set.copy(bgHue = it)) }
                            ColorSliderCard(stringResource(R.string.l_s_colors_bg_sat), set.bgSat, 100, d, cell(3).fillMaxWidth(),
                                satTrack(light, set.bgHue)) { model.editColors(set.copy(bgSat = it)) }
                            Card(cell(4).fillMaxWidth()) {
                                Box(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), contentAlignment = Alignment.CenterStart) {
                                    Txt(stringResource(R.string.l_s_colors_hint), d.sub, color = Pal.SEC, maxLines = 2, ellipsis = false)
                                }
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
                                // Waze - только окно, подсказок нет вовсе (h1003): тумблер не показываем
                                val noHints = NavigatorPick.noHints(navPkg)
                                if (!noHints) SwitchCard(stringResource(R.string.l_nav_hints), hints, d, cell(2).fillMaxWidth(), sub = sub.takeIf { subFits }) {
                                    model.chooseNavHints(navPkg, it)
                                }
                                var row = if (noHints) 2 else 3
                                // масштаб - dpi своего хоста Car App; навигатору окном и через AA он не нужен
                                if (EmbedHost.nav.carHost) MapScaleCard(navPkg, d, cell(row++).fillMaxWidth())
                                Placed(row++) { RestartNavCard(d, Modifier) } // кнопка - через обёртку, рамка шайбы (h737)
                                // Google через AA: сервер ГУ живёт в Android Auto, перезапуск - в её настройках на телефоне
                                if (hints && google) AaServerHintCard(d, cell(row).fillMaxWidth())
                            }
                        }
                        SettingsPage.MUSIC -> {
                            // в оба слота - любой плеер телефона, онлайн-сервисы тоже (жалобы юзеров 09.11)
                            val found by produceState(emptyList<String>()) {
                                value = withContext(Dispatchers.IO) { MusicController.players(model.context) }
                            }
                            val players = PlayerChip.allPlayers(found, model.installedPackages, model.onlinePlayers)
                            val local = model.localPlayer?.takeIf { it in players } ?: players.firstOrNull()
                            PlayersCard(stringResource(R.string.l_s_slot1), players.map { PlayerOpt.App(it) },
                                local?.let { PlayerOpt.App(it) }, d, cell(0).fillMaxWidth()) { model.chooseLocalPlayer((it as PlayerOpt.App).pkg) }
                            val second = PlayerChip.secondSlotPlayers(players, model.onlinePlayers)
                            PlayersCard(stringResource(R.string.l_s_slot2), listOf(PlayerOpt.Last) + second.map { PlayerOpt.App(it) },
                                model.onlinePlayer?.takeIf { it in second }?.let { PlayerOpt.App(it) } ?: PlayerOpt.Last, d, cell(1).fillMaxWidth()) {
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
                            // прокрутка живёт в модели: после выбора слоя в Picker возвращаемся к «Слоям», а не наверх
                            val scroll = rememberScrollState(model.settingsHomeScroll)
                            LaunchedEffect(scroll.value) { model.settingsHomeScroll = scroll.value }
                            Column(Modifier.fillMaxSize().verticalScroll(scroll),
                                verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                MapSideCard(d, Modifier.fillMaxWidth().height(m.dp(layoutH)))
                                LayersCard(layersH, Modifier.fillMaxWidth().height(m.dp(layersH)))
                            }
                        }
                        SettingsPage.SYSTEM -> {
                            val info = systemInfo()
                            for ((i, row) in info.withIndex()) InfoCard(row.first, row.second, d, cell(i).fillMaxWidth())
                            SwitchCard(stringResource(R.string.l_s_notifications), model.notifications, d, cell(info.size).fillMaxWidth()) {
                                model.chooseNotifications(it)
                            }
                            SwitchCard(stringResource(R.string.l_s_alice_voice), model.aliceVoiceOnly, d, cell(info.size + 1).fillMaxWidth(),
                                sub = stringResource(if (model.aliceVoiceOnly) R.string.l_s_alice_voice_on else R.string.l_s_alice_voice_off)) {
                                model.chooseAliceVoiceOnly(it)
                            }
                            Placed(info.size + 2) { AboutLinkCard(d, Modifier) }
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
        SettingsPage.LOOK, SettingsPage.COLORS -> R.string.l_sec_look
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
                SettingsPage.LOOK, SettingsPage.COLORS -> VectorIcon(VIco.HALF_CIRCLE, 24f, d.catIcon, Pal.TEXT)
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

/** «Тема»: название с подписью слева, три пилюли справа. 1 ряд (второй отдан «Цветам»). */
@Composable
private fun ThemeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
                Txt(stringResource(R.string.l_s_theme), s.f2, ellipsis = false)
                Txt(stringResource(R.string.l_s_theme_sub), d.sub, color = Pal.SEC)
            }
            for ((choice, res) in listOf(ThemeChoice.DARK to R.string.l_s_theme_dark, ThemeChoice.LIGHT to R.string.l_s_theme_light,
                ThemeChoice.AUTO to R.string.l_s_theme_auto)) {
                val label = stringResource(res)
                Pill(label, model.theme == choice, d) { model.chooseTheme(choice) }
            }
        }
    }
}

/** Пилюля-вариант высотой с контрол ряда. */
@Composable
private fun Pill(label: String, selected: Boolean, d: SettingsDims, onTap: () -> Unit) {
    val m = LocalMetrics.current
    OptionBox(selected, Modifier.height(m.dp(d.ctl)), RoundedCornerShape(m.dp(d.ctl / 2)), label,
        unselected = LocalPalette.current.btnSet, onTap = onTap) {
        Box(Modifier.fillMaxHeight().padding(horizontal = m.dp(d.pillPad)), contentAlignment = Alignment.Center) {
            Txt(label, m.spec.f2, weight = FontWeight.SemiBold, ellipsis = false)
        }
    }
}

/** «Цвета» в «Виде»: подпись, миниатюры ночного и дневного набора, шеврон; тап открывает [SettingsPage.COLORS]. */
@Composable
private fun ColorsLinkCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val label = stringResource(R.string.l_s_colors)
    Pressable(modifier.clip(RoundedCornerShape(m.dp(s.radius))).background(Pal.SURFACE), description = label,
        onTap = { model.openColors() }) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
                Txt(label, s.f2, ellipsis = false)
                Txt(stringResource(R.string.l_s_colors_sub), d.sub, color = Pal.SEC)
            }
            ColorsSwatch(LauncherColors.palette(false, model.nightColors), d)
            ColorsSwatch(LauncherColors.palette(true, model.dayColors), d)
            SvgIcon(Ico.CHEV_R, d.catIcon, Pal.SEC, stroke = 2.6f)
        }
    }
}

/** Миниатюра набора: фон палитры и уголок акцента слева снизу, как кнопка навигации. */
@Composable
private fun ColorsSwatch(pal: LauncherPalette, d: SettingsDims) {
    val m = LocalMetrics.current
    val h = d.ctl * 6 / 10
    Box(Modifier.width(m.dp(h * 16 / 9)).height(m.dp(h)).clip(RoundedCornerShape(m.dp(max(2, h / 6))))
        .background(Brush.linearGradient(pal.bgStops.map { it.second }))
        .border(m.dp(1), pal.sep, RoundedCornerShape(m.dp(max(2, h / 6))))) {
        Box(Modifier.align(Alignment.BottomStart).width(m.dp(h * 16 / 9 / 4)).height(m.dp(h * 4 / 10)).background(pal.accent))
    }
}

/** Строка «Цветов»: какой набор настраиваем (ночной / дневной) и «Сбросить» его к золоту. */
@Composable
private fun ColorsModeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
            Pill(stringResource(R.string.l_s_colors_night), !model.colorsEditDay, d) { model.colorsEditDay = false }
            Pill(stringResource(R.string.l_s_colors_day), model.colorsEditDay, d) { model.colorsEditDay = true }
            Spacer(Modifier.weight(1f))
            val reset = stringResource(R.string.l_s_colors_reset)
            Pressable(Modifier.height(m.dp(d.ctl)).clip(RoundedCornerShape(m.dp(d.ctl / 2))).background(LocalPalette.current.btnSet),
                description = reset, onTap = { model.resetColors() }) { pressed ->
                if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                Box(Modifier.fillMaxHeight().padding(horizontal = m.dp(d.pillPad)), contentAlignment = Alignment.Center) {
                    Txt(reset, s.f2, weight = FontWeight.SemiBold, ellipsis = false)
                }
            }
        }
    }
}

/**
 * Радуга оттенков для слайдера: акцент - как он будет на экране, фон - приглушённый. Хвост после 360° -
 * «без цвета» ([ColorSet.HUE_NEUTRAL]): чёрный или белый, резкой границей.
 */
private fun hueTrack(light: Boolean, accent: Boolean): Brush {
    val end = 360f / ColorSet.HUE_NEUTRAL
    val rainbow = (0..360 step 60).map { h ->
        h / 360f * end to if (accent) LauncherColors.accent(light, h)
        else Color.hsl((h % 360).toFloat(), if (light) 0.5f else 0.35f, if (light) 0.85f else 0.24f)
    }
    val none = if (accent) LauncherColors.accent(light, ColorSet.HUE_NEUTRAL) else if (light) Color.White else Color.Black
    return Brush.horizontalGradient(*(rainbow + listOf(end + 0.001f to none, 1f to none)).toTypedArray())
}

/** Насыщенность фона: от серого до цветного того же оттенка. */
private fun satTrack(light: Boolean, hue: Int): Brush {
    val l = if (light) 0.86f else 0.2f
    // фон без цвета: насыщенность ни на что не влияет - дорожка серая
    if (ColorSet.neutral(hue)) return Brush.horizontalGradient(listOf(Color.hsl(0f, 0f, l), Color.hsl(0f, 0f, l)))
    return Brush.horizontalGradient(listOf(Color.hsl(hue.toFloat() % 360f, 0f, l), Color.hsl(hue.toFloat() % 360f, if (light) 0.6f else 0.45f, l)))
}

/**
 * Карточка-слайдер «Цветов»: название слева, трек с градиентом и белая ручка справа. Тап ставит значение сразу,
 * перетаскивание двигает; в настройки пишется по отпусканию ([LauncherModel.saveColors]).
 */
@Composable
private fun ColorSliderCard(label: String, value: Int, top: Int, d: SettingsDims, modifier: Modifier, track: Brush, onChange: (Int) -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Txt(label, s.f2, Modifier.weight(0.34f), maxLines = 2, ellipsis = false)
            ColorSlider(value, top, d, track, Modifier.weight(0.66f).fillMaxHeight(), onChange)
        }
    }
}

@Composable
private fun ColorSlider(value: Int, top: Int, d: SettingsDims, track: Brush, modifier: Modifier, onChange: (Int) -> Unit) {
    val m = LocalMetrics.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val knobDp = m.dp(d.knob)
    val knobPx = with(density) { knobDp.toPx() }
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val span = (w - knobPx).coerceAtLeast(1f)
        fun at(x: Float) = (((x - knobPx / 2) / span).coerceIn(0f, 1f) * top).roundToInt()
        Box(Modifier.fillMaxSize()
            .pointerInput(top, span) {
                detectTapGestures(onTap = { change(at(it.x)); LauncherModel.saveColors() })
            }
            .pointerInput(top, span) {
                detectHorizontalDragGestures(
                    onDragEnd = { LauncherModel.saveColors() },
                    onDragCancel = { LauncherModel.saveColors() },
                ) { c, _ -> c.consume(); change(at(c.position.x)) }
            },
        ) {
            Box(Modifier.align(Alignment.CenterStart).padding(horizontal = knobDp / 2).fillMaxWidth().height(m.dp(d.trackH * 4 / 10))
                .clip(RoundedCornerShape(m.dp(d.trackH / 5))).background(track))
            val x = with(density) { (span * value / top.toFloat()).toDp() }
            Box(Modifier.align(Alignment.CenterStart).offset(x = x).size(knobDp).clip(CircleShape).background(Pal.WHITE)
                .border(m.dp(max(2, d.knob / 12)), Color(0x8C000000), CircleShape))
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
 * Миниатюра главного для варианта панели: у начала строки (слева, в RTL справа) рейка (кнопки точками, золотой блок
 * навигации внизу, часы чертами), за ней музыка с картой и нижний ряд. В «Маленькой» и «Обрезанной» время - чертой сверху слева в правой части.
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
            // рейка стоит у начала строки (в RTL справа), плашки за ней - физически, карта справа, как в главном по умолчанию
            Ltr {
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
            // два переключателя в половинной ширине: длинная подпись переносится, а не обрезается
            if (sub == null) Txt(label, s.f2, Modifier.weight(1f), maxLines = 2, ellipsis = false)
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
        for (row in (if (model.arabic) NAV_TILES_AR else NAV_TILES).chunked(2)) {
            Row(Modifier.height(m.dp(rowH)).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                for ((pkg, res) in row) {
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
                            // миниатюры показывают физическую сторону карты, как сам главный: рисуем их слева направо при любом языке
                            Ltr {
                                if (opt.layout == HomeLayout.QUAD) HomeMiniature(opt.left, r(6), Modifier.weight(1f).fillMaxWidth())
                                else TwoMiniature(opt.layout == HomeLayout.WIDE_LEFT, r(6), Modifier.weight(1f).fillMaxWidth())
                            }
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

/** Большая плашка миниатюры: золото и всё, что в ней бывает, - навигатор, YouTube, часы. */
@Composable
private fun RowScope.BigMiniCell(weight: Float, shape: RoundedCornerShape) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    val accent = LocalAccent.current
    val on = Pal.onAccent(accent)
    Row(Modifier.weight(weight).fillMaxHeight().clip(shape).background(accent),
        horizontalArrangement = Arrangement.spacedBy(m.dp(max(1, (10 * k).roundToInt())), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(Ico.NAV, (24 * k).roundToInt(), on, fill = true)
        SvgIcon(Ico.VIDEO, (26 * k).roundToInt(), on, stroke = 2.4f)
        SvgIcon(Ico.CLOCK, (24 * k).roundToInt(), on, stroke = 2.4f)
    }
}

@Composable
private fun RowScope.MusicMiniCell(weight: Float, shape: RoundedCornerShape) {
    val k = LocalMetrics.current.spec.t / 96.0
    Box(Modifier.weight(weight).fillMaxHeight().clip(shape).background(Color(0xFF6B5520)), contentAlignment = Alignment.Center) {
        SvgIcon(Ico.MUSIC, (26 * k).roundToInt(), Color(0xFFF2E2B0), stroke = 2.4f)
    }
}

/** Миниатюра двух плашек: большая (2) и музыка (1); при [bigLeft] большая физически слева (звать внутри [Ltr]). Рейку не рисуем (Егор 05.11). */
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

/** Миниатюра четырёх плашек: музыка, большая плашка, приложения и телефон; при [left] ряды зеркальные (звать внутри [Ltr]). Без рейки. */
@Composable
private fun HomeMiniature(left: Boolean, gapPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    val pal = LocalPalette.current
    val gap = m.dp(gapPx)
    val small = RoundedCornerShape(m.dp(gapPx))
    Row(modifier.clip(RoundedCornerShape(m.dp(max(1, (10 * k).roundToInt())))).background(pal.mini)) {
        // лента 1280x480: на главном один ряд - обложка, большая плашка и сетка приложений, нижнего ряда нет (Егор 09.11)
        if (m.spec.strip) {
            Row(Modifier.fillMaxSize().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                val cells = listOf<@Composable RowScope.() -> Unit>(
                    { MusicMiniCell(0.25f, small) },
                    { BigMiniCell(0.5f, small) },
                    {
                        Box(Modifier.weight(0.25f).fillMaxHeight().clip(small).background(pal.btn2), contentAlignment = Alignment.Center) {
                            SvgIcon(Ico.PLUS, (22 * k).roundToInt(), pal.fg2, stroke = 2.4f)
                        }
                    },
                )
                for (c in if (left) cells.asReversed() else cells) c(this)
            }
            return@Row
        }
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
    val res = CarLifeState.videoMode?.let { stringResource(R.string.l_s_fps, "${it.outW}×${it.outH}", fps) }
    return listOf(
        stringResource(R.string.l_s_hu) to hu,
        // «Подключение» и «Разрешение и частота» - одна строка: в пятирядной сетке без прокрутки иначе «О приложении»
        // уезжала за край после «Алиса только голосом». Раньше так же ушла строка «Видео: прямой проброс».
        stringResource(R.string.l_s_link) to (if (link == dash || res == null) link else "$link · $res"),
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
            SvgIcon(Ico.CHEV_R, d.catIcon, Pal.SEC, Modifier.mirrorRtl(), stroke = 2.6f)
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
