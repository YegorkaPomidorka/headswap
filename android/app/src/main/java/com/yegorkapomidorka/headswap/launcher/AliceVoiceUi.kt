package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.dex.DesktopAudioState
import kotlin.math.roundToInt
import kotlin.math.sin

private val PILL_BG = Color(0xBD0D1117)
private val PILL_EDGE = Color(0x1FFFFFFF)
private val ROUTE_EDGE = Color(0x73F2B33D)
private val VIOLET = Color(0xFFA98BFF)
private val VIOLET_LIGHT = Color(0xFFCDBDFF)
private val GOLD = Color(0xFFF2B33D)
private val SUB = Color(0xFFB4BCCB)
/** Gemini: синий акцент и градиент звезды (синий - фиолетовый - розовый), свой рисунок, без файла Google. */
private val GEM_BLUE = Color(0xFF6FA3F7)
private val GEM_BLUE_LIGHT = Color(0xFFB8D0FB)
private val GEM_BG = Color(0xFF1E1F22)
private val GEM_GRADIENT = listOf(Color(0xFF4796E3), Color(0xFF9177C7), Color(0xFFCA6673))

/**
 * Плашка скрытой Алисы снизу по центру области (макет «Алиса на рабочем столе», 07.11): иконка Алисы и состояние
 * ([AliceVoice.Phase]). В навигаторе с левой панелью - по центру карты, чтобы не закрыть «Поехали». Тап закрывает Алису.
 */
@Composable
fun AliceVoicePill() {
    val model = LauncherModel
    val phase = AliceVoice.phase
    if (phase == AliceVoice.Phase.HIDDEN) return
    val r = model.areaRect ?: return
    val m = LocalMetrics.current
    val k = m.spec.h / 720f
    fun px(v: Int) = (v * k).roundToInt()
    val left = if (model.area == Area.Navigator) AliceVoice.navLeftPx.coerceIn(0, (r.width / 2).roundToInt()) else 0
    val centerX = r.left + left + (r.width - left) / 2f
    var pillW by remember { mutableIntStateOf(0) }
    val h = px(88)
    val route = phase == AliceVoice.Phase.ROUTE
    val gemini = AliceVoice.gemini
    val accent = if (gemini) GEM_BLUE else VIOLET
    val accentLight = if (gemini) GEM_BLUE_LIGHT else VIOLET_LIGHT
    Box(
        Modifier
            .absoluteOffset { IntOffset((centerX - pillW / 2f).roundToInt(), (r.bottom - px(28) - h).roundToInt()) }
            .onSizeChanged { pillW = it.width }
            .height(m.dp(h))
            .clip(RoundedCornerShape(50))
            .background(PILL_BG)
            .border(m.dp(1), if (route) ROUTE_EDGE else PILL_EDGE, RoundedCornerShape(50)),
    ) {
        Pressable(
            Modifier.height(m.dp(h)).padding(start = m.dp(px(16)), end = m.dp(px(28))),
            description = stringResource(if (gemini) R.string.l_gemini_close else R.string.l_alice_close),
            onTap = { LauncherActions.closeAssistant("тап по плашке помощника"); AliceVoice.stop() },
        ) {
            Row(Modifier.height(m.dp(h)), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.dp(px(16)))) {
                if (route) {
                    Box(Modifier.size(m.dp(px(56))).clip(CircleShape).background(GOLD), contentAlignment = Alignment.Center) {
                        NavArrow(m.dp(px(26)))
                    }
                } else if (gemini) {
                    Box(Modifier.size(m.dp(px(56))).clip(CircleShape).background(GEM_BG)
                        .alpha(if (phase == AliceVoice.Phase.THINKING) 0.85f else 1f), contentAlignment = Alignment.Center) {
                        GeminiSpark(m.dp(px(34)))
                    }
                } else {
                    Image(painterResource(R.drawable.alice_icon), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(m.dp(px(56))).clip(CircleShape)
                            .alpha(if (phase == AliceVoice.Phase.THINKING) 0.85f else 1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(m.dp(px(2)))) {
                    val title = when (phase) {
                        AliceVoice.Phase.LISTENING -> stringResource(R.string.l_alice_listening)
                        AliceVoice.Phase.THINKING -> stringResource(R.string.l_alice_thinking)
                        AliceVoice.Phase.SPEAKING -> stringResource(R.string.l_alice_speaking)
                        else -> stringResource(R.string.l_alice_route)
                    }
                    Txt(title, px(24), color = Color.White, weight = FontWeight.SemiBold)
                    if (route) Txt(stringResource(R.string.l_alice_route_hint), px(15), color = SUB)
                }
                when (phase) {
                    AliceVoice.Phase.LISTENING -> ListenBars(::px, accent)
                    AliceVoice.Phase.THINKING -> ThinkDots(::px, accent)
                    AliceVoice.Phase.SPEAKING -> SpeakBars(::px, accentLight)
                    else -> Unit
                }
            }
        }
    }
}

/** «Я вас слушаю»: пять полосок ровно пульсируют по очереди - громкость чужого микрофона нам не видна. */
@Composable
private fun ListenBars(px: (Int) -> Int, color: Color) {
    val m = LocalMetrics.current
    val t = rememberInfiniteTransition(label = "listen")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "p")
    Row(Modifier.height(m.dp(px(40))), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(px(5)))) {
        for (i in 0 until 5) {
            val phase = (p - i * 0.14f) * 2f * Math.PI.toFloat()
            val s = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase))
            Box(Modifier.width(m.dp(px(5))).height(m.dp((px(36) * s).roundToInt().coerceAtLeast(1)))
                .clip(RoundedCornerShape(50)).background(color))
        }
    }
}

/** «Думаю...»: три точки прыгают по очереди. */
@Composable
private fun ThinkDots(px: (Int) -> Int, color: Color) {
    val m = LocalMetrics.current
    val t = rememberInfiniteTransition(label = "think")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "p")
    Row(Modifier.height(m.dp(px(40))), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(px(8)))) {
        for (i in 0 until 3) {
            val x = ((p - i * 0.17f) % 1f + 1f) % 1f
            val up = if (x < 0.4f) sin(x / 0.4f * Math.PI.toFloat()) else 0f
            Box(Modifier.absoluteOffset { IntOffset(0, -(px(6) * up).roundToInt()) }.size(m.dp(px(9)))
                .alpha(0.25f + 0.75f * up).clip(CircleShape).background(color))
        }
    }
}

/** «Говорю»: высота полосок от громкости голоса в захвате звука рабочего стола; без захвата - мягкая анимация. */
@Composable
private fun SpeakBars(px: (Int) -> Int, light: Color) {
    val m = LocalMetrics.current
    var level by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { ns ->
            time = ns / 1e9f
            val target = if (DesktopAudioState.running) (DesktopAudioState.level * 2.2f).coerceIn(0.15f, 1f)
            else 0.55f + 0.35f * sin(time * 7f)
            level += (target - level) * 0.35f
        }
    }
    val mult = floatArrayOf(0.6f, 0.95f, 0.75f, 1f, 0.7f, 0.9f, 0.5f)
    Row(Modifier.height(m.dp(px(44))), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(px(4)))) {
        for (i in mult.indices) {
            val wobble = 0.8f + 0.2f * sin(time * (9f + i * 1.7f) + i)
            val s = (level * mult[i] * wobble).coerceIn(0.12f, 1f)
            Box(Modifier.width(m.dp(px(4))).height(m.dp((px(44) * s).roundToInt().coerceAtLeast(1)))
                .clip(RoundedCornerShape(50)).background(if (i % 2 == 0) Color.White else light))
        }
    }
}

/** Четырёхлучевая звезда Gemini с вогнутыми гранями и градиентом по диагонали. */
@Composable
private fun GeminiSpark(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val c = w / 2f
        val path = Path().apply {
            moveTo(c, 0f)
            quadraticTo(c * 1.08f, c * 0.92f, w, c)
            quadraticTo(c * 1.08f, c * 1.08f, c, w)
            quadraticTo(c * 0.92f, c * 1.08f, 0f, c)
            quadraticTo(c * 0.92f, c * 0.92f, c, 0f)
            close()
        }
        drawPath(path, androidx.compose.ui.graphics.Brush.linearGradient(GEM_GRADIENT,
            start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(w, w)))
    }
}

/** Стрелка навигации на жёлтом круге плашки маршрута. */
@Composable
private fun NavArrow(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 3 / 24, w * 11 / 24); lineTo(w * 21 / 24, w * 3 / 24); lineTo(w * 13 / 24, w * 21 / 24)
            lineTo(w * 11 / 24, w * 13 / 24); close()
        }
        drawPath(path, Color(0xFF141413))
    }
}
