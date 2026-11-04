package com.yegorkapomidorka.headswap.launcher

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DesktopKeys

/**
 * Шайба и кнопки ГУ в своём лаунчере (ADR 0024): фокус Compose по кнопкам [Pressable], рамка как в AA.
 * Поворот - следующий/предыдущий элемент, наклоны - сосед по направлению, OK - нажатие. Первое движение после
 * касания только показывает рамку; любое касание экрана её прячет. NAVI/MUSIC/HOME - действия лаунчера.
 */
object LauncherKnob : DesktopKeys.Sink {
    /** Кнопка, которая может получить фокус шайбы; [tap] - её действие по OK. */
    class Target { var tap: () -> Unit = {} }

    /** Рамка видна: после последнего касания крутили шайбу или жали её кнопки. */
    var visible by mutableStateOf(false)

    /** Активность лаунчера на экране ГУ; без неё мост шлёт клавиши в окно как раньше. */
    @Volatile var active = false

    private val main = Handler(Looper.getMainLooper())
    private var focus: FocusManager? = null // только главный поток
    private var target: Target? = null // только главный поток

    fun attach(fm: FocusManager) { focus = fm }
    fun detach(fm: FocusManager) { if (focus === fm) focus = null }

    /** Pressable сообщает о фокусе; при уходе с экрана - тоже, с on = false. */
    fun focused(t: Target, on: Boolean) {
        if (on) target = t else if (target === t) target = null
    }

    override fun rotate(delta: Int): Boolean = take(KnobLogic.forRotary(delta))
    override fun key(keycode: Int): Boolean = take(KnobLogic.forKey(keycode))

    private fun take(k: Knob?): Boolean {
        // чужой лаунчер: его окно само разбирает стрелки, HOME идёт через showDesktop
        if (k == null || !active || LauncherModel.foreign != null) return false
        main.post { run(k) }
        return true
    }

    private fun run(k: Knob) {
        when (k) {
            Knob.OK -> if (visible && target != null) target?.tap?.invoke() else reveal()
            Knob.HOME -> { visible = false; LauncherActions.home() }
            Knob.NAVI -> { visible = false; LauncherActions.openNavigator() }
            Knob.MUSIC -> { visible = false; LauncherActions.openMusic() }
            else -> if (!visible || target == null) reveal() else focus?.moveFocus(direction(k))
        }
    }

    /** Рамка появляется там, где фокус был до касания; фокуса нет (сменился экран) - на первой кнопке. */
    private fun reveal() {
        val fm = focus ?: return
        visible = true
        if (target == null) fm.moveFocus(FocusDirection.Next)
        AppLog.i("LAUNCHER шайба: рамка фокуса, экран=${LauncherModel.area}", false)
    }

    private fun direction(k: Knob): FocusDirection = when (k) {
        Knob.NEXT -> FocusDirection.Next
        Knob.PREVIOUS -> FocusDirection.Previous
        Knob.UP -> FocusDirection.Up
        Knob.DOWN -> FocusDirection.Down
        Knob.LEFT -> FocusDirection.Left
        else -> FocusDirection.Right
    }

    /** Цвет зазора между рамкой и кнопкой - фон темы, чтобы рамка читалась и на обложке, и на золоте. */
    fun gapColor(light: Boolean) = if (light) Color(0xFFFBF7EC) else Color(0xFF0A0F14)
}

/** Рамка фокуса внутри границ кнопки: снаружи её срезал бы clip карточки. Кольцо [color] и под ним зазор [gap]. */
fun Modifier.knobRing(show: Boolean, shape: Shape, color: Color, gap: Color, widthPx: Float): Modifier =
    if (!show) this else drawWithContent {
        drawContent()
        ring(shape, 0f, widthPx, color)
        ring(shape, widthPx, widthPx * 0.6f, gap)
    }

private fun DrawScope.ring(shape: Shape, insetPx: Float, width: Float, color: Color) {
    val d = insetPx + width / 2
    val sz = Size(size.width - 2 * d, size.height - 2 * d)
    if (sz.width <= 0f || sz.height <= 0f) return
    val outline = shape.createOutline(sz, layoutDirection, this)
    drawContext.transform.translate(d, d)
    drawOutline(outline, color, style = Stroke(width))
    drawContext.transform.translate(-d, -d)
}

/** Любое касание экрана прячет рамку шайбы. Смотрит на DOWN раньше кнопок и ничего не забирает. */
fun Modifier.knobTouchReset(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press && LauncherKnob.visible) LauncherKnob.visible = false
        }
    }
}
