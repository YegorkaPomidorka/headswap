package com.yegorkapomidorka.headswap.aa

import kotlin.math.abs

/**
 * Левая рейка рабочего стола прячется и возвращается одним пальцем: горизонтальный свайп в коридоре от [X_MIN] до
 * [X_MAX] ширины кадра ГУ, [Y_MIN]..[Y_MAX] высоты (ADR 0062). Рейка видна - ждём жест влево, скрыта - вправо.
 * Касание не задерживаем: пока жест не опознан, события идут дальше как есть. Сработало на MOVE (UP с ГУ приходит с
 * задержкой 200-500 мс) - уже переданное касание отменяется ([onCancel]), [onToggle] получает hide, и до отпускания
 * события съедаются. Второй палец, уход по вертикали и откат назад - отказ: до UP жест не смотрим. Без таймеров,
 * синхронный; в портрете (h > w) не срабатывает.
 */
class RailSwipe(
    private val onCancel: () -> Unit,
    private val onToggle: (hide: Boolean) -> Unit,
) {
    private enum class State { IDLE, TRACK, SKIP, EAT }

    private val lock = Any()
    private var state = State.IDLE
    private var startX = 0
    private var startY = 0

    /** true - событие съедено жестом и дальше не идёт. w, h - кадр ГУ; railHidden - рейка сейчас скрыта. */
    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int, w: Int, h: Int, enabled: Boolean, railHidden: Boolean): Boolean =
        synchronized(lock) {
            val act = action and 0xFF
            if (act == AapMessages.TOUCH_DOWN) state = State.IDLE      // UP мог потеряться: новое касание начинает с нуля
            when (state) {
                State.IDLE -> {
                    if (act != AapMessages.TOUCH_DOWN) return false
                    val inCorridor = enabled && w > h && h > 0 &&
                        x >= w * (if (railHidden) SHOW_X_MIN else X_MIN) && x <= w * X_MAX && y >= h * Y_MIN && y <= h * Y_MAX
                    if (inCorridor && !(px != 0 || py != 0)) {
                        startX = x
                        startY = y
                        state = State.TRACK
                    } else state = State.SKIP
                    false
                }
                State.SKIP -> {
                    if (act == AapMessages.TOUCH_UP || act == CANCEL) state = State.IDLE
                    false
                }
                State.TRACK -> {
                    when (act) {
                        AapMessages.TOUCH_UP, CANCEL -> state = State.IDLE
                        AapMessages.TOUCH_POINTER_DOWN, AapMessages.TOUCH_POINTER_UP -> state = State.SKIP
                        AapMessages.TOUCH_MOVE -> {
                            if (px != 0 || py != 0) { state = State.SKIP; return false }
                            val sign = if (railHidden) 1 else -1
                            val dx = (x - startX) * sign           // вдоль нужного направления
                            val dy = abs(y - startY)
                            val adx = abs(x - startX)
                            when {
                                dy > h * DY_MAX && dy > adx * SLOPE -> state = State.SKIP
                                dx < -w * BACK_MAX -> state = State.SKIP
                                dx >= w * DX_MIN && dy <= adx * SLOPE -> {
                                    state = State.EAT
                                    onCancel()
                                    onToggle(!railHidden)
                                    return true
                                }
                            }
                        }
                    }
                    false
                }
                State.EAT -> {
                    if (act == AapMessages.TOUCH_UP || act == CANCEL) state = State.IDLE
                    true
                }
            }
        }

    /** Сессия закрыта: жест сбрасываем. */
    fun reset() = synchronized(lock) { state = State.IDLE }

    companion object {
        /** Коридор старта, доли кадра: в навигаторах левую часть в середине по вертикали так явно не тянут. */
        const val X_MIN = 0.10f
        /** Рейка скрыта: возвращают её привычно от самого края (стенд 23.11: старты с x 0-10 px). */
        const val SHOW_X_MIN = 0f
        const val X_MAX = 0.35f
        const val Y_MIN = 0.30f
        const val Y_MAX = 0.70f
        /** Ход по горизонтали до срабатывания, доля ширины. */
        const val DX_MIN = 0.12f
        /** Откат против направления жеста, доля ширины, после которого жест не наш. */
        const val BACK_MAX = 0.05f
        /** Уход по вертикали, доля высоты, после которого (и при |dy| > SLOPE·|dx|) жест не наш. */
        const val DY_MAX = 0.05f
        const val SLOPE = 0.5f
        private const val CANCEL = 3
    }
}
