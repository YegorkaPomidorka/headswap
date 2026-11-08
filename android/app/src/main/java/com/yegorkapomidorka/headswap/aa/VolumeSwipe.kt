package com.yegorkapomidorka.headswap.aa

/**
 * Громкость двумя пальцами у правого края (рабочий стол, Vezel без крутилки). Два пальца легли в правую полосу
 * ([ZONE] ширины кадра ГУ) - жест наш: уже переданное касание отменяется ([onCancel]), дальше до отпускания всех
 * пальцев события в рабочий стол не идут. Каждые [STEP] высоты кадра хода вверх - шаг громче, вниз - тише ([onStep]).
 * Позиция - среднее по двум пальцам, после POINTER_UP шаги не считаются. Синхронный, без таймеров.
 */
class VolumeSwipe(
    private val onCancel: () -> Unit,
    private val onStep: (Int) -> Unit,
) {
    private enum class State { IDLE, ACTIVE, DRAIN }

    private val lock = Any()
    private var state = State.IDLE
    private var anchorY = 0
    private var stepPx = 1

    /** true - событие съедено жестом и дальше не идёт. w, h - кадр ГУ. */
    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int, w: Int, h: Int, enabled: Boolean): Boolean = synchronized(lock) {
        val act = action and 0xFF
        val second = px != 0 || py != 0
        when (state) {
            State.IDLE -> {
                if (!enabled || act != AapMessages.TOUCH_POINTER_DOWN || w <= 0 || h <= 0) return false
                val edge = w - w * ZONE
                if (x < edge || (second && px < edge)) return false
                state = State.ACTIVE
                stepPx = (h * STEP).toInt().coerceAtLeast(1)
                anchorY = if (second) (y + py) / 2 else y
                onCancel()
                true
            }
            State.ACTIVE, State.DRAIN -> {
                when (act) {
                    AapMessages.TOUCH_DOWN -> {                 // UP потерян: новое касание уже не жест
                        state = State.IDLE
                        return false
                    }
                    AapMessages.TOUCH_UP, 3 -> state = State.IDLE
                    AapMessages.TOUCH_POINTER_UP -> state = State.DRAIN
                    AapMessages.TOUCH_MOVE -> if (state == State.ACTIVE && second) {
                        val cur = (y + py) / 2
                        var steps = 0
                        while (anchorY - cur >= stepPx) { anchorY -= stepPx; steps++ }
                        while (cur - anchorY >= stepPx) { anchorY += stepPx; steps-- }
                        if (steps != 0) onStep(steps)
                    }
                }
                true
            }
        }
    }

    /** Сессия закрыта: жест сбрасываем. */
    fun reset() = synchronized(lock) { state = State.IDLE }

    companion object {
        /** Правая полоса кадра, где должны лечь оба пальца. */
        const val ZONE = 0.3f
        /** Ход на один шаг громкости, доля высоты кадра. */
        const val STEP = 1f / 12
    }
}
