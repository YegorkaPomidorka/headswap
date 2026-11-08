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
    class Target { var tap: () -> Unit = {}; var description: String? = null }

    /** Рамка видна: после последнего касания крутили шайбу или жали её кнопки. */
    var visible by mutableStateOf(false)

    /**
     * Экземпляр активности лаунчера на экране ГУ; без него мост шлёт клавиши в окно как раньше. Свой у каждого
     * экземпляра: старая активность (досрочный finish, переподключение) не гасит приём у живой.
     */
    @Volatile private var owner: Any? = null
    @Volatile private var lastDecline: String? = null

    fun resumed(o: Any) { owner = o }
    fun paused(o: Any) { if (owner === o) owner = null }

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

    /** Кнопка голоса: панель Алисы; чужой лаунчер или активность не на экране - не беру, Алиса пойдёт на весь дисплей. */
    override fun voice(): Boolean {
        val decline = when {
            owner == null -> "активность лаунчера не на экране"
            LauncherModel.foreign != null -> "чужой лаунчер"
            else -> null
        }
        if (decline != null) {
            AppLog.i("ASSIST кнопка голоса: лаунчер не берёт ($decline)", false)
            return false
        }
        main.post { LauncherActions.toggleAssistant() }
        return true
    }

    private fun take(k: Knob?): Boolean {
        if (k == null) return false
        // чужой лаунчер: его окно само разбирает стрелки, HOME идёт через showDesktop
        val decline = when {
            owner == null -> "активность лаунчера не на экране"
            LauncherModel.foreign != null -> "чужой лаунчер"
            else -> null
        }
        if (decline != lastDecline) {
            lastDecline = decline
            AppLog.i("LAUNCHER шайба: ${decline?.let { "не беру ($it), клавиши идут в окно" } ?: "беру сам"}", false)
        }
        if (decline != null) return false
        main.post { if (!toWindow(k)) run(k) }
        return true
    }

    @Volatile private var lastWindow: String? = null

    /**
     * Чужое приложение развёрнуто на всю область (навигатор окном, приложение из списка): шайба и стрелки идут в его
     * дисплей, HOME/NAVI/MUSIC остаются лаунчеру, BACK лаунчер и так не берёт. Свой хост Car App (2ГИС) - кнопки
     * шаблона наши, их обходит фокус Compose. Главный поток.
     */
    private fun toWindow(k: Knob): Boolean {
        val code = KnobLogic.windowKey(k) ?: return false
        val slot = when (LauncherModel.area) {
            Area.Navigator -> EmbedHost.nav.takeUnless { it.carHost }
            is Area.App -> EmbedHost.app
            else -> null
        }
        val sent = slot != null && EmbedHost.key(slot, code)
        val where = if (sent) "окно ${slot?.name} (${slot?.pkg})" else null
        if (where != lastWindow) {
            lastWindow = where
            AppLog.i("LAUNCHER шайба: ${where?.let { "клавиши идут в $it" } ?: "фокус лаунчера"}", false)
        }
        if (sent) visible = false
        return sent
    }

    private fun run(k: Knob) {
        when (k) {
            Knob.OK -> {
                val t = target
                if (visible && t != null) {
                    AppLog.i("LAUNCHER шайба OK: «${t.description ?: "без подписи"}», экран=${LauncherModel.area}", false)
                    t.tap()
                } else reveal()
            }
            Knob.HOME -> act(k) { LauncherActions.home() }
            Knob.NAVI -> act(k) { LauncherActions.openNavigator() }
            Knob.MUSIC -> act(k) { LauncherActions.openMusic() }
            else -> if (!visible || target == null) reveal() else {
                val fm = focus
                if (fm == null || !fm.moveFocus(direction(k))) AppLog.i("LAUNCHER шайба $k: фокус не сдвинулся" +
                    (if (fm == null) " (нет FocusManager)" else ""), false)
            }
        }
    }

    private fun act(k: Knob, action: () -> Unit) {
        visible = false
        AppLog.i("LAUNCHER кнопка ГУ $k, экран=${LauncherModel.area}", false)
        action()
    }

    /** Рамка появляется там, где фокус был до касания; фокуса нет (сменился экран) - на первой кнопке. */
    private fun reveal() {
        val fm = focus
        if (fm == null) { AppLog.i("LAUNCHER шайба: нет FocusManager, рамку не показать", false); return }
        visible = true
        val moved = target != null || fm.moveFocus(FocusDirection.Next)
        AppLog.i("LAUNCHER шайба: рамка фокуса на «${target?.description ?: if (moved) "?" else "нет кнопок"}», " +
            "экран=${LauncherModel.area}", false)
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
    fun gapColor(pal: LauncherPalette): Color = pal.bgStops.first().second
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
