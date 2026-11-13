package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Корень лаунчера: фон по теме, рейка у начала строки (слева; при арабском языке экран зеркален и рейка справа; кроме
 * полноэкранного YouTube) и область содержимого.
 */
@Composable
fun LauncherRoot(connected: Boolean) {
    val density = LocalDensity.current
    val model = LauncherModel
    // направление языка: до любого Ltr; по нему «Карта слева», окно Алисы и возврат из Ltr (LocaleDir)
    val localeDir = LocalLayoutDirection.current
    LaunchedEffect(Unit) {
        while (true) {
            model.status = withContext(Dispatchers.Default) { StatusReader.read(model.context) }
            // режим фар от ГУ для темы «Авто»
            model.huTheme = CarLifeState.huTheme
            delay(1000)
        }
    }
    // состояние звонка и журнал: на главном потоке (PhoneStateListener берёт Looper потока создания)
    LaunchedEffect(Unit) {
        while (true) {
            PhoneController.tick(model.context)
            delay(1000)
        }
    }
    // «Включать музыку автоматически»: один раз на рабочий стол (новый дисплей = новая сессия), не на каждую рекомпозицию
    LaunchedEffect(connected) {
        if (connected) {
            delay(2000) // первый опрос сессий MusicController
            LauncherActions.autoplay()
        }
    }
    // шайба ГУ двигает фокус Compose этого окна
    val focusManager = LocalFocusManager.current
    DisposableEffect(focusManager) {
        LauncherKnob.attach(focusManager)
        onDispose { LauncherKnob.detach(focusManager) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().knobTouchReset()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val railSize = model.railSize
        val spec = remember(w, h, railSize) { LauncherSpecs.forSize(w, h, railSize) }
        val metrics = remember(spec, density) { LauncherMetrics(spec, density) }
        // раскладка плашек в портрете своя ([LauncherModel.layout]): флаг ведёт модель, спека у каждого экрана своя
        SideEffect { model.portrait = spec.portrait }
        val pal = model.palette
        CompositionLocalProvider(LocalMetrics provides metrics, LocalAccent provides pal.accent, LocalPalette provides pal,
            LocalLocaleDirection provides localeDir) {
            Box(Modifier.fillMaxSize().launcherBackground(pal)) {
                if (!connected) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.l_connecting), spec.f1, color = Pal.SEC)
                    }
                } else if (model.foreign != null) {
                    // чужой лаунчер: только его окно и наша панель навигации, без рейки и оверлеев звонка
                    ForeignRoot()
                } else {
                    // рейка у начала строки: слева, в RTL справа. «Карта слева» - физическая сторона (HomeScreen).
                    // Полноэкранный YouTube забирает и её место: выход - кнопкой самого YouTube (FullscreenApp)
                    val fullscreen = model.area is Area.App && EmbedHost.app.fullscreen
                    if (spec.portrait) {
                        // вертикальный экран: область на всю ширину, рейка снизу (h921)
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { model.areaRect = it.boundsInRoot() }) {
                                AreaContent()
                            }
                            if (!fullscreen) BottomRail()
                        }
                    } else Row(Modifier.fillMaxSize()) {
                        if (!fullscreen) Rail()
                        Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { model.areaRect = it.boundsInRoot() }) {
                            AreaContent()
                        }
                    }
                    run {
                        // слои с пиксельными rect-ами (boundsInRoot + absoluteOffset) - физические: Box создаётся внутри Ltr,
                        // иначе в RTL он ставит детей от правого края и сдвиг уезжает на ширину слоя
                        Ltr {
                            Box(Modifier.fillMaxSize()) {
                                EmbedLayers()
                                HomeTileButtons() // ⇄ и «Слои» ленты - над живыми окнами плашек
                                NavHostOverlay() // шаблоны навигатора на своём хосте Car App - над его картой, в границах области
                                NotificationsOverlay() // язычок и шторка уведомлений телефона из-под строки статуса; касания берёт только сам
                                LyricsLayer(Modifier.fillMaxSize()) // текст песни: над всеми экранами, ниже только звонка
                                AliceVoicePill() // скрытая Алиса: слушаю / думаю / говорю / маршрут - снизу по центру
                                // входящий звонок поверх чужого приложения или экрана без карточки «Телефон»
                                if (PhoneController.callState == CallState.RINGING && (model.area != Area.Home || spec.strip || model.layout.two)) {
                                    val r = metrics.dp(spec.radius)
                                    if (spec.portrait) {
                                        // над нижней рейкой, по ширине области, полный вид карточки; на главном звонок идёт
                                        // полосой вместо нижнего ряда (PortraitCallBar), сюда попадают остальные экраны
                                        Box(Modifier.fillMaxSize().padding(start = metrics.dp(spec.g), end = metrics.dp(spec.g),
                                            bottom = metrics.dp(spec.bottomRailH + spec.padBottom)),
                                            contentAlignment = Alignment.BottomCenter) {
                                            LocaleDir {
                                                PhoneCard(Modifier.fillMaxWidth().height(metrics.dp(spec.bottomRowH)), RoundedCornerShape(r),
                                                    compact = false)
                                            }
                                        }
                                    } else {
                                        // как нижняя полоса главного: прибита к низу справа, скругления только сверху
                                        Box(Modifier.fillMaxSize().padding(end = metrics.dp(spec.m)), contentAlignment = Alignment.BottomEnd) {
                                            LocaleDir {
                                                PhoneCard(Modifier.width(metrics.dp(spec.phone)).height(metrics.dp(spec.bottomH)),
                                                    RoundedCornerShape(topStart = r, topEnd = r))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        RemoveConfirm()
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaContent() {
    when (LauncherModel.area) {
        Area.Home -> HomeScreen()
        Area.AllApps -> AllAppsScreen()
        Area.Picker -> PickerScreen()
        Area.Settings -> LauncherSettingsScreen()
        Area.Calls -> CallsScreen()
        Area.Players -> PlayersScreen()
        Area.Navigator, is Area.App -> EmbedAreaBackdrop()
    }
}


/** Рейка на всю высоту (S-образно распределена) и под ней отдельный золотой блок навигации. Макет v2. */
@Composable
private fun Rail() {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val model = LauncherModel
    val status = model.status
    Column(Modifier.width(m.dp(s.rail)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(bottomEnd = m.dp(s.radius))).background(Pal.SURFACE)
                .padding(top = m.dp(s.g), bottom = m.dp(s.g8)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (s.clockInRail) Txt(status.time, s.f1, color = Pal.RAIL, weight = FontWeight.SemiBold, mono = true)
            // «Средняя»: часы, золотая чёрточка и минуты столбиком; в «Маленькой» и «Обрезанной» часов нет
            if (s.clockStacked) {
                val parts = status.time.split(":", limit = 2)
                Column(Modifier.height(m.dp(s.clockH)), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(m.dp(s.clockGap))) {
                    // строка Txt выше шрифта (lineHeight 1.15): центруем цифры в рамке clockHr, иначе зазоры у чёрточки разные
                    Box(Modifier.height(m.dp(s.clockHr)), contentAlignment = Alignment.Center) {
                        Txt(parts[0], s.clockHr, Modifier.wrapContentHeight(unbounded = true), color = Pal.RAIL,
                            weight = FontWeight.SemiBold, mono = true)
                    }
                    Box(Modifier.width(m.dp(s.sepW)).height(m.dp(s.sepH)).clip(RoundedCornerShape(m.dp(s.sepH))).background(accent))
                    Box(Modifier.height(m.dp(s.clockHr)), contentAlignment = Alignment.Center) {
                        Txt(parts.getOrElse(1) { "" }, s.clockHr, Modifier.wrapContentHeight(unbounded = true), color = Pal.SEC2,
                            weight = FontWeight.SemiBold, mono = true)
                    }
                }
            }
            RailBackButton()
            RailLogoButton()
            RailGearButton()
            // «Вид карточки» в рейке; в ленте с обычной рейкой - стеклянная кнопка в углу большой плашки (HomeTileButtons)
            if (s.cardViewInRail) CardViewButton(s.rb, s.ric, Color.Transparent, Pal.SEC2)
        }
        NavBlock(Modifier.width(m.dp(s.rail)).height(m.dp(s.navH)), RoundedCornerShape(topEnd = m.dp(s.radius)))
    }
}

/** «Назад» виден всегда: белый везде, кроме главного, где идти некуда (серый, без анимации). */
@Composable
private fun RailBackButton() {
    val s = LocalMetrics.current.spec
    val canBack = BackNav.enabled(LauncherModel.area)
    val back = rememberPressAnim(600)
    RoundButton(s.rb, stringResource(R.string.l_back), onTap = { LauncherActions.embeddedBack() }, enabled = canBack,
        onPress = back::play) {
        BackIcon(s.backIc, if (canBack) Pal.RAIL else LocalPalette.current.backOff) { back.progress }
    }
}

/** Лого: на главном - «Все приложения», на любом другом экране - домой; долгое нажатие - в меню ГУ (h1061). */
@Composable
private fun RailLogoButton() {
    val s = LocalMetrics.current.spec
    val accent = LocalAccent.current
    val logo = rememberPressAnim(1000)
    val home = LauncherModel.area == Area.Home
    val ctx = LocalContext.current
    RoundButton(s.rb, stringResource(if (home) R.string.l_all_apps else R.string.l_home), onTap = { LauncherActions.logo() },
        onLong = { BridgeService.requestHuDesktop(ctx) }, longMs = PressLogic.LONG_HU_EXIT_MS, onPress = logo::play) {
        Box(Modifier.matchParentSize().drawBehind {
            // как CSS circle farthest-corner: радиус 0.707 стороны, стопы 0 / 40% / 70%
            drawCircle(Brush.radialGradient(
                0f to accent.copy(alpha = 0.6f), 0.4f to accent.copy(alpha = 0.25f), 0.7f to accent.copy(alpha = 0f),
                center = center, radius = size.minDimension * 0.7071f))
        }, contentAlignment = Alignment.Center) {
            LogoIcon(s.logoIn, Pal.RAIL) { logo.progress }
        }
    }
}

/** На экране настроек шестерёнка подсвечена: текущий экран. */
@Composable
private fun RailGearButton() {
    val s = LocalMetrics.current.spec
    val accent = LocalAccent.current
    val gear = rememberPressAnim(900)
    val inSettings = LauncherModel.area == Area.Settings
    RoundButton(s.rb, stringResource(R.string.l_settings), onTap = { LauncherActions.openSettings() }, onPress = gear::play,
        background = if (inSettings) accent.copy(alpha = 0.32f) else Color.Transparent) {
        GearIcon(s.ric, if (inSettings) Pal.RAIL else Pal.SEC2) { gear.progress }
    }
}

/** Блок навигации акцентом: под рейкой слева, в портрете - справа в нижней рейке. [shape] - скругление угла у края. */
@Composable
private fun NavBlock(modifier: Modifier, shape: Shape) {
    val s = LocalMetrics.current.spec
    val accent = LocalAccent.current
    Pressable(
        modifier.clip(shape).background(accent),
        description = stringResource(R.string.l_navigation), onTap = { LauncherActions.openNavigator() },
        // на золоте золотая рамка не видна
        focusShape = shape, ringColor = Color.White,
    ) { pressed ->
        if (pressed) Box(Modifier.matchParentSize().background(Color(0x33000000)))
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            SvgIcon(Ico.NAV, s.icBig, Pal.onAccent(accent), fill = true)
        }
    }
}

/**
 * Нижняя рейка портрета (h921): слева панель цвета карточек с «Назад», лого, настройками, «Видом карточки» и громкостью
 * «−»/«+» (капсула), справа блок навигации шириной рейки. Скругление сверху, прибита к низу экрана.
 */
@Composable
private fun BottomRail() {
    val m = LocalMetrics.current
    val s = m.spec
    val r = m.dp(s.radius)
    val top = RoundedCornerShape(topStart = r, topEnd = r)
    Row(Modifier.fillMaxWidth().height(m.dp(s.bottomRailH)).padding(horizontal = m.dp(s.g)),
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Row(Modifier.weight(1f).fillMaxHeight().clip(top).background(Pal.SURFACE),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
            RailBackButton()
            RailLogoButton()
            RailGearButton()
            CardViewButton(s.rb, s.ric, Color.Transparent, Pal.SEC2)
            VolumeGroup()
        }
        NavBlock(Modifier.width(m.dp(s.rail)).fillMaxHeight(), top)
    }
}

/** «−» и «+» громкости медиа в одной капсуле: тот же ±1 шаг, что у жеста двумя пальцами ([LauncherActions.adjustVolume]). */
@Composable
private fun VolumeGroup() {
    val m = LocalMetrics.current
    val s = m.spec
    Row(Modifier.clip(RoundedCornerShape(50)).background(Pal.SURFACE_HI).padding(horizontal = m.dp(s.b4)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.b4))) {
        RoundButton(s.volBtn, stringResource(R.string.l_volume_down), onTap = { LauncherActions.adjustVolume(-1) }) {
            SvgIcon(Ico.VOLUME_DOWN, (s.volBtn * 0.45f).toInt(), Pal.RAIL)
        }
        RoundButton(s.volBtn, stringResource(R.string.l_volume_up), onTap = { LauncherActions.adjustVolume(1) }) {
            SvgIcon(Ico.VOLUME_UP, (s.volBtn * 0.45f).toInt(), Pal.RAIL)
        }
    }
}

/** «Вид карточки навигации»: на рейке прозрачная, в ленте - стеклянная на карте. По DOWN падает новая плитка. */
@Composable
fun CardViewButton(sizePx: Int, iconPx: Int, background: Color, color: Color, modifier: Modifier = Modifier) {
    val anim = rememberPressAnim(700)
    RoundButton(sizePx, stringResource(R.string.l_card_view), onTap = { LauncherActions.cycleCardView() }, modifier = modifier,
        background = background, onPress = anim::play) {
        LayersIcon(iconPx, color) { anim.progress }
    }
}

/**
 * Строка статуса справа сверху: дата, Bluetooth, Wi-Fi, сеть, громкость медиа. Ничего не нажимается, погоды нет.
 * Стоит на сплошной полосе цвета панелей темы от рейки до края экрана и от верха: фон рисуется за отступами родителя
 * (все три экрана со строкой - start g, top g8, end m). Нижний край полосы уходит в модель: к нему крепится язычок
 * уведомлений ([NotificationsOverlay]); золотая полоска живёт там же, по центру области.
 */
@Composable
fun StatusBar(title: String? = null, showNet: Boolean = true) {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    val model = LauncherModel
    val bar = LocalPalette.current.bar
    if (model.hideStatusBar) {
        // строка скрыта (Для разработчиков): полосы и значков нет, остаётся только заголовок экрана; чёлка уведомлений - от верха экрана
        DisposableEffect(Unit) { model.statusBottom = null; model.statusSlot = true; onDispose { model.statusSlot = false } }
        if (title != null) Row(Modifier.fillMaxWidth().height(m.dp(s.statusH)), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, s.statusFs, Modifier.weight(1f), color = Pal.TEXT, weight = FontWeight.SemiBold)
        }
        return
    }
    DisposableEffect(Unit) { onDispose { model.statusBottom = null; model.statusBusyX = emptyList() } }
    // где на строке текст: полоска уведомлений встаёт над стыком плиток и наезжала на дату (Vezel, 08.11)
    var timeX by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var iconsX by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var titleX by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    LaunchedEffect(timeX, iconsX, titleX) { model.statusBusyX = listOfNotNull(timeX, titleX, iconsX) }
    fun Modifier.busy(set: (ClosedFloatingPointRange<Float>) -> Unit) =
        onGloballyPositioned { val b = it.boundsInRoot(); set(b.left..b.right) }
    Row(
        Modifier.fillMaxWidth().height(m.dp(s.statusH))
            .onGloballyPositioned { model.statusBottom = it.boundsInRoot().top - s.g8 + s.statusBandH }
            .drawBehind {
                // отступ start (g) у рейки, end (m) у края экрана; в RTL рейка справа - полоса выходит влево на m
                // в портрете рейки сбоку нет: поля с обеих сторон экранные (g и padEnd)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val left = if (rtl) s.padEnd else s.g
                val right = if (rtl) s.g else s.padEnd
                drawRect(bar, Offset(-left.toFloat(), -s.g8.toFloat()), Size(size.width + left + right, s.statusBandH.toFloat()))
            }
            // строка по центру полосы: родитель опускает её на g8, полоса ниже строки на 3/4 g8
            .offset(y = -m.dp(s.g8 / 4)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g), Alignment.End),
    ) {
        // в узкой рейке часов нет: время слева, прижато к краю (перед заголовком, если он есть)
        if (s.clockInStatus) {
            Txt(st.time, s.statusFs, Modifier.busy { timeX = it }, color = Pal.TEXT, weight = FontWeight.SemiBold, mono = true)
            if (title == null) Spacer(Modifier.weight(1f))
        }
        // заголовок экрана у начала (слева; в RTL справа), статус у конца; сам ряд значков всегда слева направо (создан внутри Ltr)
        // ширину самого текста заголовка - в занятое: на экранах без стыка (настройки) полоска уведомлений встаёт к левому краю
        if (title != null) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Txt(title, s.statusFs, Modifier.busy { titleX = it }, color = Pal.TEXT, weight = FontWeight.SemiBold)
        }
        Ltr { Box(Modifier.busy { iconsX = it }) { StatusIcons(st, showNet, model.showBattery) } }
    }
}

/** Дата, Bluetooth, Wi-Fi, сеть, громкость и заряд телефона: порядок слева направо при любом языке ([Ltr] снаружи, чтобы ряд создался в нём). */
@Composable
private fun StatusIcons(st: StatusInfo, showNet: Boolean, showBattery: Boolean) {
    val m = LocalMetrics.current
    val s = m.spec
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Txt(st.date, s.statusFs, color = Pal.SEC2)
        SvgIcon(Ico.BT, s.statusFs, if (st.bluetooth) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
        SvgIcon(Ico.WIFI, s.statusFs, if (st.wifi) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
        // сеть с уровнем сигнала - здесь, рядом с Wi-Fi, а не в рейке
        if (showNet && st.signal >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
            VectorIcon(VIco.signal(st.signal), 24f, s.statusFs, Pal.SEC2)
            Txt(st.net, s.statusFs, color = Pal.SEC2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
            SvgIcon(Ico.VOLUME, s.statusFs, Pal.SEC2)
            Txt(st.volume.toString(), s.statusFs, color = Pal.SEC2)
        }
        // заряд телефона в конце ряда: цифра внутри батарейки, молния справа - на зарядке.
        // Нет данных или выключено в «Вид» - ничего не рисуем
        if (showBattery && st.battery >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
            BatteryBadge(st.battery, s.statusFs, Pal.SEC2)
            if (st.charging) VectorIcon(VIco.BOLT, 24f, s.statusFs, Pal.SEC2)
        }
    }
}

/** Батарейка с цифрой заряда внутри (без «%»): контур со скруглением и «носик» справа. Высота - [fs], ширина - 2·fs. */
@Composable
private fun BatteryBadge(percent: Int, fs: Int, color: Color) {
    val m = LocalMetrics.current
    val nub = maxOf(2, fs / 9)
    Box(
        Modifier.width(m.dp(fs * 2)).height(m.dp(fs)).drawBehind {
            val sw = maxOf(1.5f, size.height / 11f)
            val nubW = nub * size.width / (fs * 2)
            val bodyW = size.width - nubW - sw
            drawRoundRect(color, Offset(sw / 2, sw / 2), Size(bodyW - sw / 2, size.height - sw),
                androidx.compose.ui.geometry.CornerRadius(size.height * 0.25f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            val nubH = size.height * 0.36f
            drawRoundRect(color, Offset(bodyW + sw * 0.5f, (size.height - nubH) / 2), Size(nubW, nubH),
                androidx.compose.ui.geometry.CornerRadius(nubW / 2))
        }.padding(end = m.dp(nub)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(percent.toString(), (fs * 0.68f).toInt(), color = color, weight = FontWeight.SemiBold)
    }
}
