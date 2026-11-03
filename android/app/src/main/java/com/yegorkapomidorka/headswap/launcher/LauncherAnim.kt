package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Кривые анимаций нажатия из макета Teyes.dc.html. */
object PressEasing {
    /** Лого и шестерёнка: cubic-bezier(.45,0,.2,1). */
    val SPIN = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
    /** «Вид карточки»: cubic-bezier(.3,0,.2,1). */
    val DROP = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
    /** «Назад»: CSS ease-in-out. */
    val IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** Стрелка навигации: cubic-bezier(.15,.85,.25,1). */
    val NAV = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f)
}

/**
 * Прогресс анимации нажатия 0..1, линейный: кривую и ключевые кадры применяет иконка.
 * В покое 1 (конец анимации = исходный вид); каждый DOWN перезапускает с нуля.
 */
class PressAnim(private val scope: CoroutineScope, private val ms: Int) {
    private val anim = Animatable(1f)
    val progress: Float get() = anim.value
    fun play() {
        if (!LauncherModel.animations) return
        scope.launch {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(ms, easing = LinearEasing))
        }
    }
}

@Composable
fun rememberPressAnim(ms: Int): PressAnim {
    val scope = rememberCoroutineScope()
    return remember(ms) { PressAnim(scope, ms) }
}

/** Стрелка навигации: rot += 720 + 45 * (1..7), 1,6 с, cubic-bezier(.15,.85,.25,1); всегда не та сторона, где стояла. */
class NavSpin(private val scope: CoroutineScope) {
    private val anim = Animatable(0f)
    private var target = 0f
    val degrees: Float get() = anim.value
    fun play() {
        if (!LauncherModel.animations) return
        target = NavSpinLogic.next(target, (1..7).random())
        scope.launch { anim.animateTo(target, tween(1600, easing = PressEasing.NAV)) }
    }
}

@Composable
fun rememberNavSpin(): NavSpin {
    val scope = rememberCoroutineScope()
    return remember { NavSpin(scope) }
}

internal object NavSpinLogic {
    /** Следующий угол: два оборота плюс 1..7 шагов по 45 градусов. */
    fun next(rot: Float, steps: Int): Float = rot + 720f + 45f * steps.coerceIn(1, 7)
}

/** Значение ключевых кадров «Назад»: шеврон улетает влево и гаснет к 40%, на 41% появляется справа и возвращается. */
internal object BackKeyframes {
    /** Сдвиг по X в единицах viewBox 24 и прозрачность. */
    fun at(p: Float): Pair<Float, Float> = when {
        p >= 1f -> 0f to 1f
        p <= 0.4f -> PressEasing.IN_OUT.transform(p / 0.4f).let { -14f * it to 1f - it }
        p < 0.41f -> 14f to 0f
        else -> PressEasing.IN_OUT.transform((p - 0.41f) / 0.59f).let { 14f * (1f - it) to it }
    }
}

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private fun DrawScope.strokePath(p: Path, color: Color, width: Float) =
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** Шеврон «Назад» рейки с анимацией по [progress]. */
@Composable
fun BackIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val path = remember { parse(Ico.CHEV_BACK[0]) }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / 24f
        val (dx, alpha) = BackKeyframes.at(progress())
        scale(sc, sc, pivot = Offset.Zero) {
            translate(left = dx) { strokePath(path, color.copy(alpha = color.alpha * alpha), 2f) }
        }
    }
}

/** Логотип Headswap: стрелки делают 2 оборота вокруг центра, экранчик в центре неподвижен. */
@Composable
fun LogoIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val paths = remember { VIco.LOGO.map { parse(it.d) } }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / VIco.LOGO_VIEWPORT
        val deg = 720f * PressEasing.SPIN.transform(progress().coerceIn(0f, 1f))
        scale(sc, sc, pivot = Offset.Zero) {
            for ((i, vp) in VIco.LOGO.withIndex()) {
                // последний путь - экранчик, первые четыре - стрелки
                if (i < VIco.LOGO.size - 1) rotate(deg, pivot = Offset(24f, 24f)) { drawVPath(vp, paths[i], color) }
                else drawVPath(vp, paths[i], color)
            }
        }
    }
}

private fun DrawScope.drawVPath(vp: VPath, p: Path, color: Color) {
    if (vp.fill) drawPath(p, color, style = Fill)
    vp.strokeW?.let { strokePath(p, color, it) }
}

/** Шестерёнка: один оборот за время анимации. */
@Composable
fun GearIcon(sizePx: Int, color: Color, progress: () -> Float) {
    SvgIcon(Ico.GEAR, sizePx, color, stroke = 2f,
        modifier = Modifier.graphicsLayer { rotationZ = 360f * PressEasing.SPIN.transform(progress().coerceIn(0f, 1f)) })
}

/**
 * «Вид карточки»: сверху падает новая плитка (-7 единиц, проявляется), прежняя верхняя опускается на место нижней (-5 -> 0),
 * прежняя нижняя уезжает вниз (+6) и гаснет.
 */
@Composable
fun LayersIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val top = remember { parse(Ico.LAYERS[0]) }
    val low = remember { parse(Ico.LAYERS[1]) }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / 24f
        val p = progress().coerceIn(0f, 1f)
        val e = PressEasing.DROP.transform(p)
        scale(sc, sc, pivot = Offset.Zero) {
            translate(top = -7f * (1f - e)) { strokePath(top, color.copy(alpha = color.alpha * e), 2f) }
            translate(top = -5f * (1f - e)) { strokePath(low, color, 2f) }
            if (p < 1f) translate(top = 6f * e) { strokePath(low, color.copy(alpha = color.alpha * (1f - e)), 2f) }
        }
    }
}
