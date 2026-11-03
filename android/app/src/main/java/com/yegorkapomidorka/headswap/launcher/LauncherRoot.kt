package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Корень лаунчера: фон по теме, рейка слева (всегда) и область содержимого. */
@Composable
fun LauncherRoot(connected: Boolean) {
    val density = LocalDensity.current
    val model = LauncherModel
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val spec = remember(w, h) { LauncherSpecs.forSize(w, h) }
        val metrics = remember(spec, density) { LauncherMetrics(spec, density) }
        val pal = if (model.lightTheme) Palettes.LIGHT else Palettes.DARK
        CompositionLocalProvider(LocalMetrics provides metrics, LocalAccent provides Pal.GOLD, LocalPalette provides pal) {
            Box(Modifier.fillMaxSize().launcherBackground(pal)) {
                if (!connected) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.l_connecting), spec.f1, color = Pal.SEC)
                    }
                } else if (model.foreign != null) {
                    // чужой лаунчер: только его окно и наша панель навигации, без рейки и оверлеев звонка
                    ForeignRoot()
                } else {
                    // рейка всегда слева; «Карта слева» переставляет только ряды главного (HomeScreen)
                    Row(Modifier.fillMaxSize()) {
                        Rail()
                        Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { model.areaRect = it.boundsInRoot() }) {
                            AreaContent()
                        }
                    }
                    run {
                        EmbedLayers()
                        // входящий звонок поверх чужого приложения или экрана без карточки «Телефон»
                        if (PhoneController.callState == CallState.RINGING && (model.area != Area.Home || spec.strip)) {
                            // как нижняя полоса главного: прибита к низу, скругления только сверху
                            val r = metrics.dp(spec.radius)
                            Box(Modifier.fillMaxSize().padding(end = metrics.dp(spec.m)), contentAlignment = Alignment.BottomEnd) {
                                PhoneCard(Modifier.width(metrics.dp(spec.phone)).height(metrics.dp(spec.bottomH)),
                                    RoundedCornerShape(topStart = r, topEnd = r))
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
    val canBack = BackNav.enabled(model.area)
    Column(Modifier.width(m.dp(s.rail)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(bottomEnd = m.dp(s.radius))).background(Pal.SURFACE)
                .padding(top = m.dp(s.g), bottom = m.dp(s.g8)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(status.time, s.f1, color = Pal.RAIL, weight = FontWeight.SemiBold, mono = true)
            // «Назад» виден всегда: белый везде, кроме главного, где идти некуда (серый, без анимации)
            val back = rememberPressAnim(600)
            RoundButton(s.rb, stringResource(R.string.l_back), onTap = { LauncherActions.embeddedBack() }, enabled = canBack,
                onPress = back::play) {
                BackIcon(s.backIc, if (canBack) Pal.RAIL else LocalPalette.current.backOff) { back.progress }
            }
            // лого: на главном - «Все приложения», на любом другом экране - домой
            val logo = rememberPressAnim(1000)
            val home = model.area == Area.Home
            RoundButton(s.rb, stringResource(if (home) R.string.l_all_apps else R.string.l_home), onTap = { LauncherActions.logo() },
                onPress = logo::play) {
                Box(Modifier.matchParentSize().drawBehind {
                    // как CSS circle farthest-corner: радиус 0.707 стороны, стопы 0 / 40% / 70%
                    drawCircle(Brush.radialGradient(
                        0f to accent.copy(alpha = 0.6f), 0.4f to accent.copy(alpha = 0.25f), 0.7f to accent.copy(alpha = 0f),
                        center = center, radius = size.minDimension * 0.7071f))
                }, contentAlignment = Alignment.Center) {
                    LogoIcon(s.logoIn, Pal.RAIL) { logo.progress }
                }
            }
            // на экране настроек шестерёнка подсвечена: текущий экран
            val gear = rememberPressAnim(900)
            val inSettings = model.area == Area.Settings
            RoundButton(s.rb, stringResource(R.string.l_settings), onTap = { LauncherActions.openSettings() }, onPress = gear::play,
                background = if (inSettings) accent.copy(alpha = 0.32f) else Color.Transparent) {
                GearIcon(s.ric, if (inSettings) Pal.RAIL else Pal.SEC2) { gear.progress }
            }
            // в ленте «Вид карточки» - стеклянная кнопка на карте
            if (!s.strip) CardViewButton(s.rb, s.ric, Color.Transparent, Pal.SEC2)
        }
        // стрелка навигации: 2 оборота и стоп в случайной другой стороне света
        val spin = rememberNavSpin()
        Pressable(
            Modifier.width(m.dp(s.rail)).height(m.dp(s.navH))
                .clip(RoundedCornerShape(topEnd = m.dp(s.radius))).background(accent),
            description = stringResource(R.string.l_navigation), onTap = { LauncherActions.openNavigator() },
            onPress = spin::play,
        ) { pressed ->
            if (pressed) Box(Modifier.matchParentSize().background(Color(0x33000000)))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.NAV, s.icBig, Pal.onAccent(accent), fill = true,
                    modifier = Modifier.graphicsLayer { rotationZ = spin.degrees })
            }
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

/** Строка статуса справа сверху: дата, Bluetooth, Wi-Fi, сеть, громкость медиа. Ничего не нажимается, погоды нет. */
@Composable
fun StatusBar(title: String? = null, showNet: Boolean = true) {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    Row(
        Modifier.fillMaxWidth().height(m.dp(s.statusH)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g), Alignment.End),
    ) {
        // заголовок экрана слева (настройки), статус справа
        if (title != null) Txt(title, s.fs, Modifier.weight(1f), color = Pal.TEXT, weight = FontWeight.SemiBold)
        Ltr {
            Txt(st.date, s.fs, color = Pal.SEC2)
            SvgIcon(Ico.BT, s.fs, if (st.bluetooth) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            SvgIcon(Ico.WIFI, s.fs, if (st.wifi) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            // сеть с уровнем сигнала - здесь, рядом с Wi-Fi, а не в рейке
            if (showNet && st.signal >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                VectorIcon(VIco.signal(st.signal), 24f, s.fs, Pal.SEC2)
                Txt(st.net, s.fs, color = Pal.SEC2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                SvgIcon(Ico.VOLUME, s.fs, Pal.SEC2)
                Txt(st.volume.toString(), s.fs, color = Pal.SEC2)
            }
        }
    }
}
