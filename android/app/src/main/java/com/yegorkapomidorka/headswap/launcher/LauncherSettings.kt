package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
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
import com.yegorkapomidorka.headswap.carapp.CarAppLocation
import com.yegorkapomidorka.headswap.dex.DexPrefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел настроек (колонка слева). ABOUT - «О приложении», вложен в «Система»: своей кнопки нет, подсвечена «Система». */
/** Разделы настроек; [COLORS] и [ABOUT] - вложенные страницы «Вида» и «Системы», своей кнопки слева у них нет. */
enum class SettingsPage { LOOK, NAVIGATOR, MUSIC, HOME, SYSTEM, ABOUT, COLORS }

/** Подписи плиток раздела «Навигатор»; какие плитки и в каком порядке - [NavigatorPick.tiles]. */
private val NAV_LABELS = mapOf(
    NavigatorPick.YANDEX_NAVI to R.string.l_nav_yandex_navi,
    NavigatorPick.DGIS to R.string.l_nav_2gis,
    NavigatorPick.YANDEX_MAPS to R.string.l_nav_yandex_maps,
    NavigatorPick.GOOGLE_MAPS to R.string.l_nav_google_maps,
    NavigatorPick.WAZE to R.string.l_nav_waze,
    NavigatorPick.AMAP to R.string.l_nav_amap,
    NavigatorPick.AMAP_AUTO to R.string.l_nav_amap_auto,
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
        Modifier.fillMaxSize().padding(start = m.dp(s.g), top = m.dp(s.g8), end = m.dp(s.padEnd), bottom = m.dp(s.padBottom)),
        verticalArrangement = Arrangement.spacedBy(m.dp(s.g8)),
    ) {
        StatusBar(title = stringResource(R.string.l_settings), showNet = false)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = s.g8
            // портрет: вкладки разделов сверху (T и зазор), сетка рядов - на остаток высоты
            val h = constraints.maxHeight - (if (s.portrait) s.t + gap else 0)
            val rows = if (s.portrait) SettingsGrid.PORTRAIT_ROWS else SettingsGrid.ROWS
            val rowH = SettingsGrid.bottom(0, h, gap, rows) - SettingsGrid.top(0, h, gap, rows)
            val d = SettingsDims(s, rowH)
            // карточка на span рядов: от верха первого ряда до низа последнего - границы совпадают слева и справа
            fun cellH(row: Int, span: Int = 1): Int = SettingsGrid.bottom(row + span - 1, h, gap, rows) - SettingsGrid.top(row, h, gap, rows)
            fun cell(row: Int, span: Int = 1): Modifier =
                Modifier.offset(y = m.dp(SettingsGrid.top(row, h, gap, rows))).height(m.dp(cellH(row, span)))
            // кнопку (Pressable) в ряд ставит обёртка: рамку шайбы Pressable рисует до своего modifier, и offset из cell()
            // её не сдвигал - рамки всех разделов рисовались поверх «Вид» (h737)
            @Composable
            fun Placed(row: Int, content: @Composable () -> Unit) =
                Box(cell(row).fillMaxWidth(), propagateMinConstraints = true) { content() }
            fun isSelected(page: SettingsPage) = model.settingsPage == page || (page == SettingsPage.SYSTEM && model.settingsPage == SettingsPage.ABOUT) ||
                (page == SettingsPage.LOOK && model.settingsPage == SettingsPage.COLORS)
            val pages = SettingsPage.entries.filter { it != SettingsPage.ABOUT && it != SettingsPage.COLORS }
            // «Цвета и обои» - отдельная страница «Вида» и в портрете (ADR 0054)
            val shownPage = model.settingsPage
            SettingsFrame(
                portrait = s.portrait,
                tabs = {
                    Row(Modifier.fillMaxWidth().height(m.dp(s.t)), horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                        for (page in pages) TabButton(page, isSelected(page), Modifier.weight(1f))
                    }
                },
                categories = {
                    Box(Modifier.width(m.dp(d.catW)).fillMaxHeight()) {
                        for ((i, page) in pages.withIndex()) Placed(i) { CategoryButton(page, isSelected(page), d, Modifier) }
                    }
                },
            ) {
                    when (shownPage) {
                        SettingsPage.LOOK -> if (s.portrait) {
                            // шесть рядов из семи: тема, стиль панели, вход в «Цвета и обои», размер панели, два тумблера, часы и дата
                            ThemeCard(d, cell(0).fillMaxWidth())
                            RailStyleCard(d, cell(1).fillMaxWidth())
                            Placed(2) { ColorsLinkCard(d, Modifier) } // кнопка - через обёртку, рамка шайбы (h737)
                            RailSizeCard(d, cell(3).fillMaxWidth())
                            Row(cell(4).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                                AnimBatteryCards(d, Modifier.weight(1f).fillMaxHeight())
                            }
                            Row(cell(5).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                                ClockDateCards(d, Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            // шесть рядов одной высоты на всю область (общая сетка из пяти рядов у «Вида» не годится)
                            val rows6 = 6
                            val rowH6 = (h - (rows6 - 1) * gap) / rows6
                            val d6 = SettingsDims(s, rowH6)
                            // подпись под названием, пока влезает по высоте ряда
                            val subFits = rowH6 >= ((s.f2 + d6.sub) * 1.25).roundToInt() + 4 + 2 * d6.padV
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                val row = Modifier.fillMaxWidth().weight(1f)
                                ThemeCard(d6, row, subFits)
                                ColorsLinkCard(d6, row, subFits)
                                RailSizeCard(d6, row)
                                RailStyleCard(d6, row)
                                Row(row, horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                    AnimBatteryCards(d6, Modifier.weight(1f).fillMaxHeight())
                                }
                                Row(row, horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                    ClockDateCards(d6, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                        SettingsPage.COLORS -> if (s.portrait) {
                            // портрет: режим, три слайдера одной карточкой, обои (миниатюры в пропорции экрана), размытие
                            // с затемнением, «Пресеты»; доли высоты 1 : 3 : 4 : 2 : 1
                            val unitH = (h - 4 * gap) / 11
                            val dc = SettingsDims(s, unitH)
                            val set = if (model.colorsEditDay) model.dayColors else model.nightColors
                            val light = model.colorsEditDay
                            val glass = model.railStyle == RailStyle.GLASS
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                val row = Modifier.fillMaxWidth()
                                ColorsModeCard(dc, row.weight(1f))
                                SlidersCard(dc, row.weight(3f)) {
                                    ColorSliderRow(stringResource(R.string.l_s_colors_accent), set.accentHue, ColorSet.HUE_NEUTRAL, dc, it,
                                        hueTrack(light, accent = true)) { v -> model.editColors(set.copy(accentHue = v)) }
                                    ColorSliderRow(stringResource(R.string.l_s_colors_bg_hue), set.bgHue, ColorSet.HUE_NEUTRAL, dc, it,
                                        hueTrack(light, accent = false)) { v -> model.editColors(set.copy(bgHue = v)) }
                                    ColorSliderRow(stringResource(R.string.l_s_colors_bg_sat), set.bgSat, 100, dc, it,
                                        satTrack(light, set.bgHue)) { v -> model.editColors(set.copy(bgSat = v)) }
                                }
                                WallpaperCard(dc, glass, row.weight(4f), portrait = true)
                                SlidersCard(dc, row.weight(2f).alpha(if (glass) 1f else WALLPAPER_OFF_ALPHA)) {
                                    ColorSliderRow(stringResource(R.string.l_s_wallpaper_blur), model.wallpaperBlur, 100, dc, it, WALLPAPER_BLUR_TRACK,
                                        enabled = glass, onRelease = { model.saveWallpaper() }) { v -> model.editWallpaper(blur = v) }
                                    ColorSliderRow(stringResource(R.string.l_s_wallpaper_dim), model.wallpaperDim, 100, dc, it, WALLPAPER_DIM_TRACK,
                                        enabled = glass, onRelease = { model.saveWallpaper() }) { v -> model.editWallpaper(dim = v) }
                                }
                                PresetsCard(dc, row.weight(1f), withLabel = false)
                            }
                        } else {
                            // шесть долей высоты: режим, три цвета рядом, обои (две доли), размытие с затемнением, «Пресеты»
                            val unitH = (h - 4 * gap) / 6
                            val dc = SettingsDims(s, unitH)
                            val set = if (model.colorsEditDay) model.dayColors else model.nightColors
                            val light = model.colorsEditDay
                            val glass = model.railStyle == RailStyle.GLASS
                            val wpAlpha = if (glass) 1f else WALLPAPER_OFF_ALPHA
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                val row = Modifier.fillMaxWidth()
                                ColorsModeCard(dc, row.weight(1f))
                                Row(row.weight(1f), horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                    val cardM = Modifier.weight(1f).fillMaxHeight()
                                    CompactSliderCard(stringResource(R.string.l_s_colors_accent), set.accentHue, ColorSet.HUE_NEUTRAL, dc, cardM,
                                        hueTrack(light, accent = true)) { model.editColors(set.copy(accentHue = it)) }
                                    CompactSliderCard(stringResource(R.string.l_s_colors_bg_hue), set.bgHue, ColorSet.HUE_NEUTRAL, dc, cardM,
                                        hueTrack(light, accent = false)) { model.editColors(set.copy(bgHue = it)) }
                                    CompactSliderCard(stringResource(R.string.l_s_colors_bg_sat), set.bgSat, 100, dc, cardM,
                                        satTrack(light, set.bgHue)) { model.editColors(set.copy(bgSat = it)) }
                                }
                                // подпись «Только в стиле «Стекло»» не приглушаем: приглушает миниатюры сама карточка
                                WallpaperCard(dc, glass, row.weight(2f))
                                Row(row.weight(1f).alpha(wpAlpha), horizontalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                    val cardM = Modifier.weight(1f).fillMaxHeight()
                                    CompactSliderCard(stringResource(R.string.l_s_wallpaper_blur), model.wallpaperBlur, 100, dc, cardM, WALLPAPER_BLUR_TRACK,
                                        enabled = glass, onRelease = { model.saveWallpaper() }) { model.editWallpaper(blur = it) }
                                    CompactSliderCard(stringResource(R.string.l_s_wallpaper_dim), model.wallpaperDim, 100, dc, cardM, WALLPAPER_DIM_TRACK,
                                        enabled = glass, onRelease = { model.saveWallpaper() }) { model.editWallpaper(dim = it) }
                                }
                                PresetsCard(dc, row.weight(1f), withLabel = true)
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
                                // h1029: свой хост Car App без геолокации «в любом режиме» - у навигатора нет GPS. Рядов пять и все
                                // заняты (подсказки, масштаб, перезапуск), поэтому, пока разрешения нет, подсказка стоит на месте масштаба
                                val carHost = EmbedHost.nav.carHost
                                val noBgLocation = carHost && hints && !google && !noHints && !hasBackgroundLocation(navPkg)
                                // масштаб - dpi своего хоста Car App или окна навигатора (ADR 0055); через теневую AA карту
                                // рисует Android Auto, там он не нужен
                                if (noBgLocation) Placed(row++) { LocationHintCard(navPkg, d, Modifier) }
                                else if (carHost || !(hints && google)) MapScaleCard(navPkg, d, cell(row++).fillMaxWidth())
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
                        SettingsPage.HOME -> if (s.portrait) {
                            // «Раскладка» четыре ряда, «Слои» три; без прокрутки (семь рядов)
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(gap))) {
                                PortraitLayoutCard(d, Modifier.fillMaxWidth().height(m.dp(cellH(0, 4))))
                                LayersCard(cellH(4, 3), Modifier.fillMaxWidth().height(m.dp(cellH(4, 3))))
                            }
                        } else {
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

/**
 * Каркас настроек. Горизонталь: колонка разделов слева, страница справа. Портрет (h921): ряд вкладок сверху на всю ширину,
 * страница ниже. Страница - слой карточек с offset по сетке рядов, рисуется в Box на остаток.
 */
@Composable
private fun SettingsFrame(
    portrait: Boolean, tabs: @Composable () -> Unit, categories: @Composable () -> Unit, page: @Composable () -> Unit,
) {
    val m = LocalMetrics.current
    val s = m.spec
    if (portrait) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
            tabs()
            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            categories()
            Box(Modifier.weight(1f).fillMaxHeight()) { page() }
        }
    }
}

/** Вкладка раздела в портрете: только название, пилюля высотой T; выбранная - как [CategoryButton]. */
@Composable
private fun TabButton(page: SettingsPage, selected: Boolean, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val label = stringResource(when (page) {
        SettingsPage.LOOK, SettingsPage.COLORS -> R.string.l_sec_look
        SettingsPage.NAVIGATOR -> R.string.l_sec_nav
        SettingsPage.MUSIC -> R.string.l_sec_music
        SettingsPage.HOME -> R.string.l_sec_home
        SettingsPage.SYSTEM, SettingsPage.ABOUT -> R.string.l_sec_system
    })
    OptionBox(selected, modifier.fillMaxHeight(), RoundedCornerShape(m.dp(s.t / 2)), description = label, unselected = Pal.SURFACE,
        onTap = { LauncherModel.settingsPage = page; LauncherModel.aboutLicense = null }) {
        Box(Modifier.fillMaxSize().padding(horizontal = m.dp(s.g4)), contentAlignment = Alignment.Center) {
            Txt(label, (s.f2 * 0.93f).roundToInt(), color = Pal.TEXT, weight = FontWeight.SemiBold, ellipsis = false)
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

/** Строка «Вида»: карточка на ряд, название (и серая подпись) слева, управление справа. */
@Composable
private fun LookRow(label: String, sub: String?, d: SettingsDims, modifier: Modifier, control: @Composable RowScope.() -> Unit) {
    val m = LocalMetrics.current
    val s = m.spec
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
                Txt(label, s.f2, ellipsis = false)
                if (sub != null) Txt(sub, d.sub, color = Pal.SEC)
            }
            control()
        }
    }
}

/**
 * Сегментированный переключатель: капсула высотой с контрол ряда, внутри варианты; выбранный - золото .32 с обводкой 2px
 * ([OptionBox]). Один на все строки «Вида»; [fs] - кегль подписей (в узких местах мельче обычного).
 */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, d: SettingsDims, fs: Int, onSelect: (T) -> Unit) {
    val m = LocalMetrics.current
    val shape = RoundedCornerShape(m.dp(d.ctl / 2))
    Row(Modifier.height(m.dp(d.ctl)).clip(shape).background(LocalPalette.current.btnSet)) {
        for ((value, label) in options) {
            OptionBox(selected == value, Modifier.fillMaxHeight(), shape, label, unselected = Color.Transparent,
                onTap = { onSelect(value) }) {
                Box(Modifier.fillMaxHeight().padding(horizontal = m.dp(d.pillPad * 3 / 4)), contentAlignment = Alignment.Center) {
                    Txt(label, fs, weight = FontWeight.SemiBold, ellipsis = false)
                }
            }
        }
    }
}

/** Пилюля-вариант высотой с контрол ряда («Ночные» / «Дневные» в «Цветах»). */
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

/** «Тема»: название с подписью слева, сегмент «Тёмная / Светлая / Авто» справа. */
@Composable
private fun ThemeCard(d: SettingsDims, modifier: Modifier, withSub: Boolean = true) {
    val model = LauncherModel
    LookRow(stringResource(R.string.l_s_theme), stringResource(R.string.l_s_theme_sub).takeIf { withSub }, d, modifier) {
        Segmented(listOf(ThemeChoice.DARK to stringResource(R.string.l_s_theme_dark), ThemeChoice.LIGHT to stringResource(R.string.l_s_theme_light),
            ThemeChoice.AUTO to stringResource(R.string.l_s_theme_auto)), model.theme, d, LocalMetrics.current.spec.f2) { model.chooseTheme(it) }
    }
}

/** «Анимации кнопок» и «Заряд телефона»: два тумблера в одном ряду пополам (вызывать внутри Row). */
@Composable
private fun RowScope.AnimBatteryCards(d: SettingsDims, modifier: Modifier) {
    val model = LauncherModel
    SwitchCard(stringResource(R.string.l_s_anim), model.animations, d, modifier) { model.chooseAnimations(it) }
    SwitchCard(stringResource(R.string.l_s_battery), model.showBattery, d, modifier) { model.chooseShowBattery(it) }
}

/** «12-часовой формат» (тумблер) и «Дата» (сегмент ДД.ММ / ММ.ДД): пополам в одном ряду (вызывать внутри Row). */
@Composable
private fun RowScope.ClockDateCards(d: SettingsDims, modifier: Modifier) {
    val model = LauncherModel
    SwitchCard(stringResource(R.string.l_s_clock12), model.clock12h, d, modifier) { model.chooseClock12h(it) }
    Card(modifier) {
        val m = LocalMetrics.current
        val s = m.spec
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
            Txt(stringResource(R.string.l_s_date), s.f2, Modifier.weight(1f), ellipsis = false)
            Segmented(listOf(false to stringResource(R.string.l_s_date_dmy), true to stringResource(R.string.l_s_date_mdy)),
                model.dateMdy, d, (s.f2 * 0.85f).roundToInt()) { model.chooseDateMdy(it) }
        }
    }
}

/** «Цвета» в «Виде»: подпись, миниатюры ночного и дневного набора, шеврон; тап открывает [SettingsPage.COLORS]. */
@Composable
private fun ColorsLinkCard(d: SettingsDims, modifier: Modifier, withSub: Boolean = true) {
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
                if (withSub) Txt(stringResource(R.string.l_s_colors_sub), d.sub, color = Pal.SEC)
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

/**
 * пресеты цветов: три равные пилюли с миниатюрой и подписью (ADR 0063). Тап применяет набор к правимому
 * (ночному или дневному), выбранный - тот, чьи значения совпали с текущими. [withLabel] - слева подпись ряда (в портрете места нет).
 */
@Composable
private fun PresetsCard(d: SettingsDims, modifier: Modifier, withLabel: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val light = model.colorsEditDay
    val cur = if (light) model.dayColors else model.nightColors
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
            if (withLabel) Txt(stringResource(R.string.l_s_colors_presets), d.sub, color = Pal.SEC, ellipsis = false)
            for (p in ColorPreset.ALL) {
                val label = stringResource(when (p.id) {
                    "tree" -> R.string.l_s_preset_tree
                    "new_year" -> R.string.l_s_preset_new_year
                    else -> R.string.l_s_preset_default
                })
                val shape = RoundedCornerShape(m.dp(d.ctl / 2))
                OptionBox(p.set(light) == cur, Modifier.weight(1f).height(m.dp(d.ctl)), shape, label,
                    unselected = LocalPalette.current.btnSet, onTap = { model.applyPreset(p) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = m.dp(d.pillPad / 2)), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8), Alignment.CenterHorizontally)) {
                        PresetSwatch(p, light, d)
                        Txt(label, (s.f2 * 0.7f).roundToInt(), Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold,
                            maxLines = 2, ellipsis = false)
                    }
                }
            }
        }
    }
}

/** Миниатюра набора 16:9 (56x32 при T 96): фон палитры, уголок акцента слева снизу; у праздничных - огоньки гирлянды по верху. */
@Composable
private fun PresetSwatch(p: ColorPreset, light: Boolean, d: SettingsDims) {
    val m = LocalMetrics.current
    val set = p.set(light)
    val pal = LauncherColors.palette(light, set)
    val h = max(8, d.ctl / 3)
    val w = h * 16 / 9
    val corner = RoundedCornerShape(m.dp(max(2, h / 6)))
    Box(Modifier.width(m.dp(w)).height(m.dp(h)).clip(corner).background(Brush.linearGradient(pal.bgStops.map { it.second }))
        .border(m.dp(1), pal.sep, corner)) {
        Box(Modifier.align(Alignment.BottomStart).width(m.dp(w / 4)).height(m.dp(h * 4 / 10)).background(pal.accent))
        if (p.garland) {
            val lights = remember(set.accentHue) { GarlandLogic.colors(set.accentHue) }
            Canvas(Modifier.fillMaxSize()) {
                val r = max(1f, size.height / 11f)
                for (i in 0 until 5) drawCircle(lights[i % lights.size], r, androidx.compose.ui.geometry.Offset(size.width * (i + 0.5f) / 5f, size.height * 0.16f))
            }
        }
    }
}

/** Строка «Цветов»: какой набор настраиваем (ночной / дневной) и «Сбросить» его к золоту. */
@Composable
private fun ColorsModeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    Card(modifier) {
        ColorsModeRow(d, Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)))
    }
}

/** Пилюли «Ночные» / «Дневные» и «Сбросить»: общий ряд страницы «Цвета» и портретного блока «Вид». */
@Composable
private fun ColorsModeRow(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
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

/**
 * Карточка со слайдерами столбиком (портретные «Цвета и обои»): каждый ряд [content] получает модификатор доли высоты.
 */
@Composable
private fun SlidersCard(d: SettingsDims, modifier: Modifier, content: @Composable ColumnScope.(Modifier) -> Unit) {
    val m = LocalMetrics.current
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd), top = m.dp(d.padV), bottom = m.dp(d.padV))) {
            content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Приглушение обоев и их слайдеров вне стиля «Стекло». */
private const val WALLPAPER_OFF_ALPHA = 0.38f

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

/** Дорожки слайдеров обоев: размытие - от чёткого к мутному, затемнение - от светлого к почти чёрному. */
private val WALLPAPER_BLUR_TRACK = Brush.horizontalGradient(listOf(Color(0xFFF2F2F2), Color(0xFF5E636B)))
private val WALLPAPER_DIM_TRACK = Brush.horizontalGradient(listOf(Color(0xFFDADADA), Color(0xFF0C0C0C)))

/**
 * Карточка-слайдер «Цветов» и обоев: название сверху одной строкой, трек с градиентом и белая ручка под ним. Тап ставит
 * значение сразу, перетаскивание двигает. [enabled] = false - не двигается (обои вне стиля «Стекло»); [onRelease] - запись
 * по отпусканию, для цветов [LauncherModel.saveColors].
 */
@Composable
private fun CompactSliderCard(
    label: String, value: Int, top: Int, d: SettingsDims, modifier: Modifier, track: Brush,
    enabled: Boolean = true, onRelease: () -> Unit = { LauncherModel.saveColors() }, onChange: (Int) -> Unit,
) {
    val m = LocalMetrics.current
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = m.dp(d.padEnd), vertical = m.dp(d.padV / 2)), verticalArrangement = Arrangement.Center) {
            Txt(label, d.sub)
            ColorSlider(value, top, d, track, Modifier.fillMaxWidth().weight(1f), onChange, enabled, onRelease)
        }
    }
}

/**
 * Выбор обоев стола: название и пояснение слева, справа миниатюры 16:9 с подписью («Без обоев», две встроенные, своё фото
 * при наличии) и «Галерея» - выбор фото на телефоне ([LauncherActions.openWallpaperPicker]). Вне стиля «Стекло» ([glass] = false)
 * миниатюры приглушены и не нажимаются, а подпись акцентом объясняет почему; [LauncherModel.wallpaper] при этом не меняется.
 * [portrait]: название сверху, миниатюры под ним в пропорции экрана - видно, что останется от картинки после обрезки (ADR 0054).
 */
@Composable
private fun WallpaperCard(d: SettingsDims, glass: Boolean, modifier: Modifier, portrait: Boolean = false) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val ctx = LocalContext.current
    val hasCustom = remember(model.wallpaperVer) { WallpaperImport.file(ctx).exists() }
    val title = @Composable { w: Modifier ->
        Column(w, verticalArrangement = Arrangement.spacedBy(m.dp(4))) {
            Txt(stringResource(R.string.l_s_wallpaper), s.f2, ellipsis = false)
            Txt(stringResource(if (glass) R.string.l_s_wallpaper_sub_glass else R.string.l_s_wallpaper_sub_solid), d.sub,
                color = if (glass) Pal.SEC else LocalAccent.current, maxLines = 3, ellipsis = false)
        }
    }
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd), top = m.dp(d.padV), bottom = m.dp(d.padV))) {
            val g = s.g
            val items = if (hasCustom) 7 else 6
            // подпись в две строки: «Зимняя дорога», «Christmas tree» в узкой плитке переносятся
            val labelH = 2 * (d.sub * 1.3).roundToInt() + s.g4
            val off = Modifier.alpha(if (glass) 1f else WALLPAPER_OFF_ALPHA)
            if (portrait) {
                val titleH = ((s.f2 + d.sub) * 1.3).roundToInt() + 4
                val availH = constraints.maxHeight - titleH - g - labelH
                val thumbW = min(availH * s.w / s.h, (constraints.maxWidth - (items - 1) * g) / items)
                val thumbH = thumbW * s.h / s.w
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(m.dp(g))) {
                    title(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().then(off), horizontalArrangement = Arrangement.SpaceBetween) {
                        WallpaperThumbs(glass, hasCustom, thumbW, thumbH, d)
                    }
                }
            } else {
                val textW = constraints.maxWidth * 22 / 100
                val availW = constraints.maxWidth - textW - g
                val thumbW = min((constraints.maxHeight - labelH) * 16 / 9, (availW - (items - 1) * g) / items)
                val thumbH = thumbW * 9 / 16
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(g))) {
                    title(Modifier.width(m.dp(textW)))
                    Row(Modifier.weight(1f).then(off), horizontalArrangement = Arrangement.spacedBy(m.dp(g), Alignment.End)) {
                        WallpaperThumbs(glass, hasCustom, thumbW, thumbH, d)
                    }
                }
            }
        }
    }
}

/** Ряд миниатюр [WallpaperCard]: «Без обоев», встроенные, своё фото (если есть) и «Галерея». */
@Composable
private fun WallpaperThumbs(glass: Boolean, hasCustom: Boolean, thumbW: Int, thumbH: Int, d: SettingsDims) {
    val m = LocalMetrics.current
    val model = LauncherModel
    val pal = LocalPalette.current
    fun pick(id: WallpaperId) { model.chooseWallpaper(id) }
    WallpaperThumb(stringResource(if (model.garland) R.string.l_s_wallpaper_lights else R.string.l_s_wallpaper_none), model.wallpaper == WallpaperId.NONE, glass, thumbW, thumbH, d, { pick(WallpaperId.NONE) }) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(pal.bgStops.map { it.second }))) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.25f).fillMaxHeight(0.4f).background(pal.accent))
            if (model.garland) {
                val set = if (model.colorsEditDay) model.dayColors else model.nightColors
                val lights = remember(set.accentHue) { GarlandLogic.colors(set.accentHue) }
                val bokeh = remember { LightsLogic.layout(7) }
                Canvas(Modifier.fillMaxSize()) {
                    for (b in bokeh) {
                        val c = lights[b.colorIdx % lights.size]
                        val r = b.r * size.height * 0.45f
                        val p = androidx.compose.ui.geometry.Offset(size.width * b.x, size.height * b.y)
                        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = b.alpha * 2f), c.copy(alpha = 0f)), center = p, radius = r), r, p)
                    }
                }
            }
        }
    }
    WallpaperThumb(stringResource(R.string.l_s_wallpaper_headswap), model.wallpaper == WallpaperId.HEADSWAP, glass, thumbW, thumbH, d, { pick(WallpaperId.HEADSWAP) }) {
        WallpaperPreview(WallpaperId.HEADSWAP, model.wallpaperVer, thumbW, thumbH)
    }
    WallpaperThumb(stringResource(R.string.l_s_wallpaper_mountains), model.wallpaper == WallpaperId.MOUNTAINS, glass, thumbW, thumbH, d, { pick(WallpaperId.MOUNTAINS) }) {
        WallpaperPreview(WallpaperId.MOUNTAINS, model.wallpaperVer, thumbW, thumbH)
    }
    WallpaperThumb(stringResource(R.string.l_s_wallpaper_tree), model.wallpaper == WallpaperId.TREE, glass, thumbW, thumbH, d, { pick(WallpaperId.TREE) }) {
        WallpaperPreview(WallpaperId.TREE, model.wallpaperVer, thumbW, thumbH)
    }
    WallpaperThumb(stringResource(R.string.l_s_wallpaper_winter_road), model.wallpaper == WallpaperId.WINTER_ROAD, glass, thumbW, thumbH, d, { pick(WallpaperId.WINTER_ROAD) }) {
        WallpaperPreview(WallpaperId.WINTER_ROAD, model.wallpaperVer, thumbW, thumbH)
    }
    if (hasCustom) {
        WallpaperThumb(stringResource(R.string.l_s_wallpaper_custom), model.wallpaper == WallpaperId.CUSTOM, glass, thumbW, thumbH, d, { pick(WallpaperId.CUSTOM) }) {
            WallpaperPreview(WallpaperId.CUSTOM, model.wallpaperVer, thumbW, thumbH)
        }
    }
    WallpaperThumb(stringResource(R.string.l_s_wallpaper_gallery), false, glass, thumbW, thumbH, d, { LauncherActions.openWallpaperPicker() }) {
        Box(Modifier.fillMaxSize().border(m.dp(2), Pal.SEC, RoundedCornerShape(m.dp(max(2, min(thumbW, thumbH) / 10)))), contentAlignment = Alignment.Center) {
            SvgIcon(Ico.IMAGE, min(thumbW, thumbH) * 4 / 10, Pal.SEC)
        }
    }
}

/** Миниатюра обоев: та же картинка, что на столе, но читается в малом размере [w]x[h]; пока читается - пустая плашка. */
@Composable
private fun WallpaperPreview(id: WallpaperId, ver: Int, w: Int, h: Int) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, id, ver, w, h) {
        value = withContext(Dispatchers.IO) { WallpaperLoader.load(ctx, id, ver, max(1, w), max(1, h)) }
    }
    Box(Modifier.fillMaxSize().background(Pal.SURFACE_HI)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Миниатюра (16:9 в альбоме, по экрану в портрете) со скруглением и подписью под ней; выбранная - в кольце акцента через зазор ([SettingsDims.ring]). */
@Composable
private fun WallpaperThumb(
    label: String, selected: Boolean, enabled: Boolean, w: Int, h: Int, d: SettingsDims, onTap: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = LocalMetrics.current
    val accent = LocalAccent.current
    // скругление от короткой стороны: в портрете миниатюры высокие
    val shape = RoundedCornerShape(m.dp(max(3, min(w, h) / 8)))
    val inner = RoundedCornerShape(m.dp(max(2, min(w, h) / 10)))
    Column(Modifier.width(m.dp(w)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(m.spec.g4))) {
        Pressable(Modifier.size(m.dp(w), m.dp(h)).clip(shape), enabled = enabled, description = label, onTap = onTap) { pressed ->
            Box(Modifier.matchParentSize().then(if (selected) Modifier.border(m.dp(d.ring), accent, shape) else Modifier)
                .padding(m.dp(d.ring * 2)).clip(inner)) {
                content()
                if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
            }
        }
        Txt(label, d.sub, Modifier.fillMaxWidth(), color = if (selected) Pal.TEXT else Pal.SEC, align = TextAlign.Center, maxLines = 2, ellipsis = false)
    }
}

/** Название слева, трек справа: строка слайдера без подложки (портретные «Цвета и обои», [SlidersCard]). */
@Composable
private fun ColorSliderRow(
    label: String, value: Int, top: Int, d: SettingsDims, modifier: Modifier, track: Brush,
    enabled: Boolean = true, onRelease: () -> Unit = { LauncherModel.saveColors() }, onChange: (Int) -> Unit,
) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Txt(label, s.f2, Modifier.weight(0.34f), maxLines = 2, ellipsis = false)
        ColorSlider(value, top, d, track, Modifier.weight(0.66f).fillMaxHeight(), onChange, enabled, onRelease)
    }
}

@Composable
private fun ColorSlider(
    value: Int, top: Int, d: SettingsDims, track: Brush, modifier: Modifier, onChange: (Int) -> Unit,
    enabled: Boolean = true, onRelease: () -> Unit = { LauncherModel.saveColors() },
) {
    val m = LocalMetrics.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val knobDp = m.dp(d.knob)
    val knobPx = with(density) { knobDp.toPx() }
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val span = (w - knobPx).coerceAtLeast(1f)
        fun at(x: Float) = (((x - knobPx / 2) / span).coerceIn(0f, 1f) * top).roundToInt()
        Box(Modifier.fillMaxSize()
            .then(if (!enabled) Modifier else Modifier
                .pointerInput(top, span) {
                    detectTapGestures(onTap = { change(at(it.x)); release() })
                }
                .pointerInput(top, span) {
                    detectHorizontalDragGestures(
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                    ) { c, _ -> c.consume(); change(at(c.position.x)) }
                }),
        ) {
            Box(Modifier.align(Alignment.CenterStart).padding(horizontal = knobDp / 2).fillMaxWidth().height(m.dp(d.trackH * 4 / 10))
                .clip(RoundedCornerShape(m.dp(d.trackH / 5))).background(track))
            val x = with(density) { (span * value / top.toFloat()).toDp() }
            Box(Modifier.align(Alignment.CenterStart).offset(x = x).size(knobDp).clip(CircleShape).background(Pal.WHITE)
                .border(m.dp(max(2, d.knob / 12)), Color(0x8C000000), CircleShape))
        }
    }
}

/**
 * «Размер панели»: сегмент вариантов рейки. «Обрезанная» - только на экранах высотой 480. В портрете панель нижняя
 * (заголовок свой, «Обрезанной» нет).
 */
@Composable
private fun RailSizeCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val model = LauncherModel
    val options = listOfNotNull(
        if (s.h480) RailSize.CUT to stringResource(R.string.l_s_rail_cut) else null,
        RailSize.SMALL to stringResource(R.string.l_s_rail_small),
        RailSize.MEDIUM to stringResource(R.string.l_s_rail_medium),
        RailSize.NORMAL to stringResource(R.string.l_s_rail_normal),
    )
    // сохранённая «Обрезанная» в портрете работает как «Маленькая» ([LauncherSpec.rs])
    val current = if (s.portrait) s.rs else model.railSize
    LookRow(stringResource(if (s.portrait) R.string.l_s_rail_bottom else R.string.l_s_rail_size), null, d, modifier) {
        Segmented(options, current, d, if (options.size > 3) (s.f2 * 0.8f).roundToInt() else (s.f2 * 0.9f).roundToInt()) { model.chooseRailSize(it) }
    }
}

/** «Стиль»: «Сплошная» или «Стекло» - рейка (в портрете нижняя), плитки главного и обои. */
@Composable
private fun RailStyleCard(d: SettingsDims, modifier: Modifier) {
    val model = LauncherModel
    LookRow(stringResource(R.string.l_s_rail_style), null, d, modifier) {
        Segmented(listOf(RailStyle.SOLID to stringResource(R.string.l_s_style_solid), RailStyle.GLASS to stringResource(R.string.l_s_style_glass)),
            model.railStyle, d, LocalMetrics.current.spec.f2) { model.chooseRailStyle(it) }
    }
}

/**
 * «Масштаб карты» навигатора, у каждого своя ступень. Свой хост Car App: dpi = «Авто» × ступень; окно: плотность стола ×
 * 0,6 / 0,75 / 1 / 1,2 (ADR 0055).
 */
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

/** Есть ли у навигатора ACCESS_BACKGROUND_LOCATION («Разрешить в любом режиме»); перепроверка раз в 2 с, пока открыта страница. */
@Composable
private fun hasBackgroundLocation(pkg: String): Boolean {
    val ctx = LauncherModel.context
    val granted by produceState(true, pkg) {
        while (true) {
            value = withContext(Dispatchers.IO) { CarAppLocation.of(ctx, pkg).background }
            delay(LOCATION_RECHECK_MS)
        }
    }
    return granted
}

/**
 * Подсказка с нажатием (свой хост Car App без «Разрешить в любом режиме»): тап открывает настройки приложения навигатора
 * на телефоне. Pressable, как у кнопок, - через обёртку Placed.
 */
@Composable
private fun LocationHintCard(pkg: String, d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val text = stringResource(R.string.l_nav_location_hint)
    ActionCard(text, modifier, onTap = { LauncherActions.openAppSettings(pkg) }) {
        Box(Modifier.fillMaxSize().padding(start = m.dp(d.padStart), end = m.dp(d.padEnd)), contentAlignment = Alignment.CenterStart) {
            Txt(text, d.sub, color = Pal.SEC, maxLines = 2, ellipsis = false)
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
        for (row in NavigatorPick.tiles(model.arabic, model.chinese).chunked(2)) {
            Row(Modifier.height(m.dp(rowH)).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(d.gap))) {
                for (pkg in row) {
                    val has = pkg in installed
                    val label = stringResource(NAV_LABELS.getValue(pkg))
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

/**
 * «Раскладка» в портрете (h921): две плашки - «Плитка сверху» (RIGHT, QUAD без «карты слева») и «Музыка сверху» (LEFT).
 * 2:1 и 1:2 в портрете ведут себя как «Плитка сверху», поэтому выбранной считается она же.
 */
@Composable
private fun PortraitLayoutCard(d: SettingsDims, modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    val k = s.t / 96.0
    fun r(v: Int) = max(1, (v * k).roundToInt())
    val model = LauncherModel
    val musicTop = model.homeLayout == HomeLayout.QUAD && model.mapLeft
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(start = m.dp(r(24)), end = m.dp(r(24)), top = m.dp(r(14)), bottom = m.dp(r(20))),
            verticalArrangement = Arrangement.spacedBy(m.dp(r(14)))) {
            Txt(stringResource(R.string.l_s_layout), s.f2)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.dp(r(20)))) {
                for (top in listOf(false, true)) {
                    val label = stringResource(if (top) R.string.l_s_layout_music_top else R.string.l_s_layout_tile_top)
                    OptionBox(musicTop == top, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(m.dp(s.radius)), label,
                        unselected = LocalPalette.current.btnSet, onTap = { model.chooseHomeLayout(HomeLayout.QUAD, top) }) {
                        Column(Modifier.fillMaxSize().padding(m.dp(r(10))), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(m.dp(r(8)))) {
                            PortraitMiniature(top, r(6), Modifier.weight(1f).fillMaxWidth(0.4f))
                            Txt(label, s.f2, weight = FontWeight.SemiBold, ellipsis = false)
                        }
                    }
                }
            }
        }
    }
}

/** Миниатюра портретного главного: плитка (акцент), музыка и нижний ряд полосами 5 : 2 : 1; при [musicTop] музыка и плитка меняются. */
@Composable
private fun PortraitMiniature(musicTop: Boolean, gapPx: Int, modifier: Modifier) {
    val m = LocalMetrics.current
    val k = m.spec.t / 96.0
    val pal = LocalPalette.current
    val gap = m.dp(gapPx)
    val small = RoundedCornerShape(gap)
    Column(modifier.clip(RoundedCornerShape(m.dp(max(1, (10 * k).roundToInt())))).background(pal.mini).padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap)) {
        val tile = @Composable { Box(Modifier.weight(5f).fillMaxWidth().clip(small).background(LocalAccent.current)) }
        val music = @Composable {
            Box(Modifier.weight(2f).fillMaxWidth().clip(small).background(Color(0xFF6B5520)), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.MUSIC, (22 * k).roundToInt(), Color(0xFFF2E2B0), stroke = 2.4f)
            }
        }
        if (musicTop) { music(); tile() } else { tile(); music() }
        Box(Modifier.weight(1f).fillMaxWidth().clip(small).background(pal.btn2))
    }
}

/** Как часто перепроверять геолокацию навигатора, пока открыты его настройки. */
private const val LOCATION_RECHECK_MS = 2_000L

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
    val desktopFps = DexPrefs(LauncherModel.context).desktopFps
    val fps = desktopFps.fixed?.toString() ?: stringResource(R.string.fps_auto_title)
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
            val tileH = fitH.coerceIn(minH, maxOf(minH, rowH)) // на 1280x480 rowH < minH
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
