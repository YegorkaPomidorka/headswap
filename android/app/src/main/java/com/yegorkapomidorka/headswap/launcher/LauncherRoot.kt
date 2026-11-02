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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Корень лаунчера: фон, рейка слева (справа при правом руле) и область содержимого. */
@Composable
fun LauncherRoot(connected: Boolean) {
    val density = LocalDensity.current
    val model = LauncherModel
    LaunchedEffect(Unit) {
        while (true) {
            model.status = withContext(Dispatchers.Default) { StatusReader.read(model.context) }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val spec = remember(w, h) { LauncherSpecs.forSize(w, h) }
        val metrics = remember(spec, density) { LauncherMetrics(spec, density) }
        val theme = model.theme
        CompositionLocalProvider(LocalMetrics provides metrics, LocalAccent provides theme.accent) {
            Box(Modifier.fillMaxSize().launcherBackground(theme.glow)) {
                if (!connected) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.l_connecting), spec.f1, color = Pal.SEC)
                    }
                } else {
                    val dir = if (model.rightHand) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides dir) {
                        Row(Modifier.fillMaxSize()) {
                            Rail()
                            Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { model.areaRect = it.boundsInRoot() }) {
                                AreaContent()
                            }
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        EmbedLayers()
                        // входящий звонок поверх чужого приложения или экрана без карточки «Телефон»
                        if (PhoneController.callState == CallState.RINGING && (model.area != Area.Home || spec.strip)) {
                            Box(Modifier.fillMaxSize().padding(end = metrics.dp(spec.m), bottom = metrics.dp(spec.m)),
                                contentAlignment = Alignment.BottomEnd) {
                                PhoneCard(Modifier.width(metrics.dp(spec.phone)).height(metrics.dp(spec.t * 9 / 4)))
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

/** Серый «Назад» на рейке, когда идти некуда (макет v2). */
private val BackIdle = Color(0xFF5F6A80)

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
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(s.g8))) {
                Txt(status.time, s.f1, color = Pal.TEXT, weight = FontWeight.SemiBold, mono = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                    if (status.signal >= 0) VectorIcon(VIco.signal(status.signal), 24f, s.fs, Pal.SEC)
                    Txt(status.net, s.fs, color = Pal.SEC)
                }
            }
            // «Назад» виден всегда: белый везде, кроме главного, где идти некуда (серый)
            RoundButton(s.rb, stringResource(R.string.l_back), onTap = { LauncherActions.embeddedBack() }, enabled = canBack) {
                SvgIcon(Ico.CHEV_BACK, s.backIc, if (canBack) Pal.WHITE else BackIdle, stroke = 2f)
            }
            RoundButton(s.rb, stringResource(R.string.l_all_apps), onTap = { LauncherActions.openAllApps() }) {
                Box(Modifier.matchParentSize().drawBehind {
                    // как CSS circle farthest-corner: радиус 0.707 стороны, стопы 0 / 40% / 70%
                    drawCircle(Brush.radialGradient(
                        0f to accent.copy(alpha = 0.6f), 0.4f to accent.copy(alpha = 0.25f), 0.7f to accent.copy(alpha = 0f),
                        center = center, radius = size.minDimension * 0.7071f))
                }, contentAlignment = Alignment.Center) {
                    VectorIcon(VIco.LOGO, VIco.LOGO_VIEWPORT, s.logoIn, Pal.WHITE)
                }
            }
            RoundButton(s.rb, stringResource(R.string.l_settings), onTap = { LauncherActions.openSettings() }) {
                SvgIcon(Ico.GEAR, s.ric, Pal.SEC2, stroke = 2f)
            }
            RoundButton(s.rb, stringResource(R.string.l_card_view), onTap = { LauncherActions.cycleCardView() }) {
                SvgIcon(Ico.LAYERS, s.ric, Pal.SEC2, stroke = 2f)
            }
        }
        Pressable(
            Modifier.width(m.dp(s.rail)).height(m.dp(s.navH))
                .clip(RoundedCornerShape(topEnd = m.dp(s.radius))).background(accent),
            description = stringResource(R.string.l_navigation), onTap = { LauncherActions.openNavigator() },
        ) { pressed ->
            if (pressed) Box(Modifier.matchParentSize().background(Color(0x33000000)))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.NAV, s.icBig, Pal.onAccent(accent), fill = true)
            }
        }
    }
}

/** Строка статуса справа сверху: дата, Bluetooth, Wi-Fi, громкость медиа. Ничего не нажимается, погоды нет. */
@Composable
fun StatusBar() {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    Row(
        Modifier.fillMaxWidth().height(m.dp(s.statusH)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g), Alignment.End),
    ) {
        Ltr {
            Txt(st.date, s.fs, color = Pal.SEC2)
            SvgIcon(Ico.BT, s.fs, if (st.bluetooth) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            SvgIcon(Ico.WIFI, s.fs, if (st.wifi) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                SvgIcon(Ico.VOLUME, s.fs, Pal.SEC2)
                Txt(st.volume.toString(), s.fs, color = Pal.SEC2)
            }
        }
    }
}
