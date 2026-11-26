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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * Гирлянда по нижнему краю строки статуса (ADR 0063): включена праздничным набором цветов ([LauncherModel.garland]).
 * Один Canvas и одна бесконечная анимация; фаза читается только при отрисовке, дерево не перекомпонуется. Касания не
 * берёт. Над полоской уведомлений провода и лампочек нет. В портрете и при скрытой строке статуса не рисуется.
 */
@Composable
fun Garland(home: LauncherSpec) {
    val model = LauncherModel
    val area = model.areaRect
    val y = model.statusBottom
    if (!model.garland || home.portrait || model.hideStatusBar || area == null || y == null) return
    val t = home.t.toFloat()
    // полоска уведомлений живёт в своём масштабе ([NotificationsOverlay]) и там же отодвигается от текста строки
    val nt = min(home.t, Math.round(home.h * 2f / 15f)).toFloat()
    val gap = if (model.notifications && NotificationsRepo.items.isNotEmpty()) {
        val cx = NotifGeom.lineAvoiding(
            NotifGeom.fixedLineX(area.left, area.right, home.g.toFloat(), home.music.toFloat(), localeRtl()),
            nt / 2f, home.g.toFloat(), model.statusBusyX, area.left, area.right,
        )
        val half = nt / 2f + 0.4f * nt
        (cx - half)..(cx + half)
    } else null
    val shape = remember(area.left, area.right, y, t, gap) { GarlandLogic.build(area.left, area.right, y, t, gap) }
    val hue = (if (model.shownLight) model.dayColors else model.nightColors).accentHue
    val colors = remember(hue) { GarlandLogic.colors(hue) }
    val wire = LocalPalette.current.fg3.copy(alpha = 0.55f)
    val wirePath = remember(shape) {
        Path().apply { for (run in shape.wire) { moveTo(run[0].x, run[0].y); for (p in run.drop(1)) lineTo(p.x, p.y) } }
    }
    val phase = if (model.animations) {
        rememberInfiniteTransition(label = "garland")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(GarlandLogic.PERIOD_MS, easing = LinearEasing)), label = "blink")
    } else null
    val bulbW = 0.10f * t
    val bulbH = 0.16f * t
    val stroke = max(1.5f, t / 48f)
    Canvas(Modifier.fillMaxSize()) {
        drawPath(wirePath, wire, style = Stroke(stroke, cap = StrokeCap.Round))
        for (h in shape.hooks) drawCircle(wire, stroke * 1.2f, Offset(h.x, h.y))
        val ph = phase?.value ?: 0f
        for (b in shape.bulbs) {
            val o = if (phase == null) 1f else GarlandLogic.opacity(ph, b.delay)
            val c = colors[b.colorIdx % colors.size]
            // лампочка висит на проводе: колпачок на нём, капля вниз; свечение - два полупрозрачных круга
            val cy = b.y + bulbH * 0.55f
            drawCircle(c.copy(alpha = 0.10f * o), bulbH * 1.5f, Offset(b.x, cy))
            drawCircle(c.copy(alpha = 0.22f * o), bulbH * 0.95f, Offset(b.x, cy))
            drawPath(dropPath(b.x, b.y, bulbW, bulbH), c.copy(alpha = o))
        }
    }
}

/** Капля: узкий верх у провода, круглое брюшко внизу. */
private fun dropPath(x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
    moveTo(x, y)
    cubicTo(x + w * 0.35f, y + h * 0.25f, x + w * 0.62f, y + h * 0.55f, x + w * 0.5f, y + h * 0.78f)
    cubicTo(x + w * 0.4f, y + h * 0.98f, x - w * 0.4f, y + h * 0.98f, x - w * 0.5f, y + h * 0.78f)
    cubicTo(x - w * 0.62f, y + h * 0.55f, x - w * 0.35f, y + h * 0.25f, x, y)
    close()
}
