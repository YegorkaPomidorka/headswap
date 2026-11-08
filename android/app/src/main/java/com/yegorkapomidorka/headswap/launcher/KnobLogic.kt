package com.yegorkapomidorka.headswap.launcher

/** Что делает лаунчер по шайбе и кнопкам ГУ. Чистая логика, без Android. */
enum class Knob { NEXT, PREVIOUS, UP, DOWN, LEFT, RIGHT, OK, HOME, NAVI, MUSIC }

object KnobLogic {
    /** Android keycode -> действие; null - лаунчер клавишу не берёт (BACK уходит инжектом в окно, громкость - ГУ). */
    fun forKey(keycode: Int): Knob? = when (keycode) {
        19 -> Knob.UP
        20 -> Knob.DOWN
        21 -> Knob.LEFT
        22 -> Knob.RIGHT
        23, 66, 160 -> Knob.OK // DPAD_CENTER, ENTER, NUMPAD_ENTER
        3 -> Knob.HOME
        65538 -> Knob.NAVI // AA NAVIGATION из AaBridge.HARD_KEY_TO_ANDROID
        209 -> Knob.MUSIC
        else -> null
    }

    fun forRotary(delta: Int): Knob? = when {
        delta > 0 -> Knob.NEXT
        delta < 0 -> Knob.PREVIOUS
        else -> null
    }

    /**
     * Клавиша для чужого приложения в окне на всю область: поворот - стрелки вниз/вверх (телефонные приложения
     * шайбу не знают), наклоны - стрелки, OK - DPAD_CENTER. null - действие остаётся лаунчеру (HOME/NAVI/MUSIC).
     */
    fun windowKey(k: Knob): Int? = when (k) {
        Knob.NEXT, Knob.DOWN -> 20
        Knob.PREVIOUS, Knob.UP -> 19
        Knob.LEFT -> 21
        Knob.RIGHT -> 22
        Knob.OK -> 23
        else -> null
    }

    /** Движение фокуса: первое после касания только показывает рамку, не сдвигая её (как в AA). */
    fun moves(k: Knob) = k in setOf(Knob.NEXT, Knob.PREVIOUS, Knob.UP, Knob.DOWN, Knob.LEFT, Knob.RIGHT)
}
