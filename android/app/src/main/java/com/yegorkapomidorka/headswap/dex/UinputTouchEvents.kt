package com.yegorkapomidorka.headswap.dex

import android.view.MotionEvent

/**
 * h1007: жест MotionEvent -> события evdev для тачскрина [UinputHostMain] (multitouch, протокол B: слот = id пальца).
 * Чистая логика без Android-вызовов, кроме констант действий; одна копия на дисплей.
 */
internal class UinputTouchEvents {
    private var nextTracking = 1
    private val active = LinkedHashSet<Int>()

    val touching: Boolean get() = active.isNotEmpty()

    fun convert(actionMasked: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, width: Int, height: Int): IntArray {
        val out = ArrayList<Int>(48)
        fun ev(type: Int, code: Int, value: Int) { out += type; out += code; out += value }
        fun position(i: Int) {
            ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_SLOT, ids[i])
            ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_POSITION_X, xs[i].toInt().coerceIn(0, width - 1))
            ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_POSITION_Y, ys[i].toInt().coerceIn(0, height - 1))
        }
        fun release(slot: Int) {
            ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_SLOT, slot)
            ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_TRACKING_ID, -1)
        }
        require(ids.all { it in 0 until MAX_SLOTS }) { "id пальца вне слотов" }
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (actionMasked == MotionEvent.ACTION_DOWN && active.isNotEmpty()) {
                    // Прошлый жест не закрыт (потерян UP): отпустить, иначе Android склеит жесты.
                    active.forEach(::release); active.clear()
                    ev(UinputHostMain.EV_KEY, UinputHostMain.BTN_TOUCH, 0)
                    ev(UinputHostMain.EV_SYN, SYN_REPORT, 0)
                }
                val slot = ids[actionIndex]
                ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_SLOT, slot)
                ev(UinputHostMain.EV_ABS, UinputHostMain.ABS_MT_TRACKING_ID, nextTracking)
                nextTracking = nextTracking % 65535 + 1
                for (i in ids.indices) position(i)
                if (active.isEmpty()) ev(UinputHostMain.EV_KEY, UinputHostMain.BTN_TOUCH, 1)
                active += slot
            }
            MotionEvent.ACTION_MOVE -> for (i in ids.indices) if (ids[i] in active) position(i)
            MotionEvent.ACTION_POINTER_UP -> {
                for (i in ids.indices) if (i != actionIndex && ids[i] in active) position(i)
                release(ids[actionIndex]); active -= ids[actionIndex]
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active.forEach(::release); active.clear()
                ev(UinputHostMain.EV_KEY, UinputHostMain.BTN_TOUCH, 0)
            }
            else -> return IntArray(0)
        }
        ev(UinputHostMain.EV_SYN, SYN_REPORT, 0)
        return out.toIntArray()
    }

    companion object {
        const val MAX_SLOTS = 10
        private const val SYN_REPORT = 0
    }
}
