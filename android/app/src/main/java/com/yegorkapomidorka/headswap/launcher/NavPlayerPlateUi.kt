package com.yegorkapomidorka.headswap.launcher

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Плашка плеера поверх навигатора на весь экран (ADR 0061): обложка, название с исполнителем и prev / play / next снизу
 * справа от левой панели навигатора. Обычный слой Compose над окном навигатора: касания по плашке берёт она, мимо - карта.
 * Стиль как у шторки уведомлений: «Стекло» - [homeGlassPanel], сплошной - непрозрачный bar палитры. Пауза дольше
 * [NavPlayerPlate.COLLAPSE_MS] - сворачивается в кнопку «♪», тап разворачивает. Нет сессии, звонок, Алиса, навигатор
 * на своём хосте Car App - плашки нет.
 */
@Composable
fun NavPlayerPlateOverlay() {
    val model = LauncherModel
    val slot = EmbedHost.nav
    val navArea = model.area == Area.Navigator && slot.status == EmbedStatus.READY && !slot.carHost
    if (!navArea) return
    val r = model.areaRect ?: return
    MusicPolling()
    val st = MusicController.state
    // начало паузы: свой отсчёт, у MusicState его нет; тап по «♪» начинает его заново
    var pausedSince by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(st.playing, st.hasSession) {
        pausedSince = if (st.playing || !st.hasSession) null else pausedSince ?: SystemClock.uptimeMillis()
    }
    LaunchedEffect(pausedSince) {
        val since = pausedSince ?: return@LaunchedEffect
        delay((since + NavPlayerPlate.COLLAPSE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0) + 50)
        tick++
    }
    // tick перечитывает время, когда пауза дотянула до сворачивания
    val now = if (tick >= 0) SystemClock.uptimeMillis() else 0L
    val look = NavPlayerPlate.look(
        navArea = true, navReady = true, carHost = false, access = st.access, hasSession = st.hasSession,
        playing = st.playing, pausedSinceMs = pausedSince, nowMs = now,
        ringing = PhoneController.callState == CallState.RINGING, alice = AliceVoice.phase != AliceVoice.Phase.HIDDEN,
    )
    if (look == NavPlayerPlate.Look.HIDDEN) return
    val m = LocalMetrics.current
    // как у Алисы и шаблонов навигатора: px макета 1280x720 по высоте, в портрете по T
    val k = if (m.spec.portrait) m.spec.t / 96f else m.spec.h / 720f
    fun px(v: Int) = (v * k).roundToInt()
    // содержимое плашки крупнее макета в [NavPlayerPlate.SCALE] раз (Егор 24.11: мелко), W и H уже с ним
    fun ps(v: Int) = (v * k * NavPlayerPlate.SCALE).roundToInt()
    val glass = homeGlassStyle
    val fg = if (glass) Pal.WHITE else Pal.TEXT
    val plateH = px(NavPlayerPlate.H)
    val fullW = px(NavPlayerPlate.W)
    val plateW = if (look == NavPlayerPlate.Look.COLLAPSED) plateH else fullW
    val areaW = r.width.roundToInt()
    val areaH = r.height.roundToInt()
    // место по навигатору: долгое нажатие - и плашку можно тащить, отпустили - запомнилось (ADR 0061)
    val pkg = slot.pkg.orEmpty()
    var saved by remember(pkg) { mutableStateOf(model.navPlayerPos(pkg)) }
    var drag by remember { mutableStateOf<Offset?>(null) }
    // после перетаскивания UP доходит и до кнопок под пальцем: их тап этого касания глушим
    val dragged = remember { booleanArrayOf(false) }
    fun unlessDragged(action: () -> Unit): () -> Unit = { if (!dragged[0]) action() }
    val (ox, oy) = NavPlayerPlate.origin(saved, areaW, areaH, fullW, plateH, k)
    val origin by rememberUpdatedState(Offset(ox.toFloat(), oy.toFloat()))
    val at = drag ?: origin
    val shape: Shape = if (look == NavPlayerPlate.Look.COLLAPSED) CircleShape else RoundedCornerShape(m.dp(ps(18)))
    // непрозрачная, как сплошная шторка уведомлений: Pal.SURFACE - белый 7% поверх фона лаунчера, на светлой карте
    // текст и prev / next пропадали (стенд 24.11, Google Карты). bar сам светлый днём и тёмный ночью
    val surface = LocalPalette.current.bar
    Box(
        Modifier
            .absoluteOffset { IntOffset((r.left + at.x).roundToInt(), (r.top + at.y).roundToInt()) }
            .size(m.dp(plateW), m.dp(plateH))
            .pointerInput(pkg, areaW, areaH, plateH) {
                awaitEachGesture {
                    // Initial: раньше кнопок плашки, они DOWN уже забирают
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragged[0] = false
                    val start = origin
                    val early: Boolean? = withTimeoutOrNull(PressLogic.LONG_MS) {
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (early != null) return@awaitEachGesture
                    dragged[0] = true
                    var cur = start
                    drag = cur
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val delta = c.positionChange()
                        c.consume()
                        if (!c.pressed) break
                        cur += delta
                        drag = cur
                    }
                    val p = NavPlayerPlate.pos(cur.x, cur.y, areaW, areaH, fullW, plateH)
                    saved = p
                    model.saveNavPlayerPos(pkg, p)
                    drag = null
                }
            }
            .then(if (glass) Modifier else Modifier.shadow(m.dp(ps(12)), shape, clip = false))
            .clip(shape)
            .then(if (glass) Modifier.homeGlassPanel(shape, m.spec.t / 96f) else Modifier.background(surface, shape)),
    ) {
        if (look == NavPlayerPlate.Look.COLLAPSED) {
            RoundButton(plateH, stringResource(R.string.l_music_players), onTap = unlessDragged { pausedSince = SystemClock.uptimeMillis() }) {
                SvgIcon(Ico.MUSIC, ps(28), fg)
            }
            return@Box
        }
        // обложка и название открывают приложение плеера; prev / play / next всегда слева направо
        Ltr {
            Row(
                Modifier.fillMaxSize().padding(start = m.dp(ps(8)), end = m.dp(ps(10))),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(ps(12))),
            ) {
                Pressable(Modifier.weight(1f), description = stringResource(R.string.l_music_players),
                    onTap = unlessDragged { LauncherActions.openMusicApp() }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(ps(12)))) {
                        val cover = ps(56)
                        Box(Modifier.size(m.dp(cover)).clip(RoundedCornerShape(m.dp(ps(12)))).background(Color(0x33FFFFFF)),
                            contentAlignment = Alignment.Center) {
                            val art = st.art
                            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else SvgIcon(Ico.MUSIC, ps(28), fg.copy(alpha = 0.6f))
                        }
                        LocaleDir {
                            Column(verticalArrangement = Arrangement.spacedBy(m.dp(ps(2)))) {
                                Txt(st.title, ps(16), color = fg, weight = FontWeight.SemiBold)
                                if (st.artist.isNotEmpty()) Txt(st.artist, ps(13), color = fg.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
                val player = st.pkg ?: model.selectedPlayer
                RoundButton(ps(44), stringResource(R.string.l_prev), onTap = unlessDragged { MusicController.previous() }) {
                    SvgIcon(Ico.PREV, ps(22), fg, fill = true)
                }
                RoundButton(ps(48), stringResource(if (st.playing) R.string.l_pause else R.string.l_play),
                    onTap = unlessDragged { MusicController.playPause(model.context, player) },
                    background = if (glass) Color(0x29FFFFFF) else LocalAccent.current) {
                    SvgIcon(if (st.playing) Ico.PAUSE else Ico.PLAY, ps(22), Pal.WHITE, fill = true)
                }
                RoundButton(ps(44), stringResource(R.string.l_next), onTap = unlessDragged { MusicController.next() }) {
                    SvgIcon(Ico.NEXT, ps(22), fg, fill = true)
                }
            }
        }
    }
}
