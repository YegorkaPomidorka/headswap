package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/** Слой «Огоньки» под всем содержимым: один Canvas, фаза читается только при отрисовке; «Анимации кнопок» выключены - статично. */
@Composable
fun LightsLayer(t: Int, accentHue: Int, animated: Boolean) {
    val bokeh = remember { LightsLogic.layout() }
    val colors = remember(accentHue) { GarlandLogic.colors(accentHue) }
    val phase = if (animated) {
        rememberInfiniteTransition(label = "lights")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(LightsLogic.CYCLE_MS, easing = LinearEasing)), label = "breathe")
    } else null
    Canvas(Modifier.fillMaxSize()) {
        val c = phase?.value ?: 0f
        for (b in bokeh) {
            val a = b.alpha * (if (phase == null) 1f else LightsLogic.breathe(b, c))
            val col = colors[b.colorIdx % colors.size]
            val r = b.r * t
            val p = Offset(size.width * b.x, size.height * b.y)
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.5f), col.copy(alpha = 0f)), center = p, radius = r), r, p)
        }
    }
}
