package com.yegorkapomidorka.headswap.dex

import android.view.Display

/**
 * Настоящая группа дисплея (DisplayInfo.displayGroupId, скрытый API). На Android 17 флаг OWN_DISPLAY_GROUP у дисплея
 * стоит, а группа всё равно 0 (группа телефона), поэтому решения принимаем по группе, а не по флагу. null - не прочитали.
 */
internal object DisplayGroups {
    fun groupId(display: Display?): Int? {
        display ?: return null
        return try {
            val infoClass = Class.forName("android.view.DisplayInfo")
            val info = infoClass.getConstructor().newInstance()
            val ok = Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info) as? Boolean ?: false
            if (ok) infoClass.getField("displayGroupId").getInt(info) else null
        } catch (_: ReflectiveOperationException) { null }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
    }

    fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN($state)"
    }

    /** Строка лога для разбора засыпания: группа и состояние питания дисплея. */
    fun describe(display: Display?): String =
        if (display == null) "нет дисплея" else "group=${groupId(display) ?: "?"} state=${stateName(display.state)}"
}
