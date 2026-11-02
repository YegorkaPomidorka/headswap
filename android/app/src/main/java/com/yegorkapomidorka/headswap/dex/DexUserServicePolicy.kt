// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.aa.DesktopGeometryPolicy
import com.yegorkapomidorka.headswap.aa.DesktopVideoGeometry

/** Values verified against the target One UI 9 framework, with distinct VD/Display flag namespaces. */
internal object DexUserServicePolicy {
    const val WIDTH = DesktopVideoGeometry.WIDTH
    const val HEIGHT = DesktopVideoGeometry.HEIGHT
    const val DENSITY = 160
    // PUBLIC | PRESENTATION | SUPPORTS_TOUCH | TRUSTED | DESTROY_CONTENT | ALLOWS_CONTENT_MODE_SWITCH.
    // OWN_CONTENT_ONLY, AUTO_MIRROR and SHOW_SYSTEM_DECORATIONS make One UI discard the last flag.
    const val VIRTUAL_DISPLAY_FLAGS = 132419
    const val DISPLAY_ALLOWS_CONTENT_MODE_SWITCH = 32768
    const val DISPLAY_TRUSTED = 128

    fun requireDimensions(width: Int, height: Int) = DesktopGeometryPolicy.requireDimensions(width, height)

    fun requireDensity(density: Int) {
        DesktopGeometryPolicy.requireDensity(density)
    }

    fun validateGeometry(id: Int, logicalW: Int, logicalH: Int, physicalW: Int, physicalH: Int, density: Int,
        flags: Int, expectedDensity: Int = DENSITY, desktopMode: Int = DesktopModePolicy.SAMSUNG_DEX,
        expectedWidth: Int = WIDTH, expectedHeight: Int = HEIGHT) {
        DesktopModePolicy.requireMode(desktopMode)
        requireDensity(expectedDensity)
        requireDimensions(expectedWidth, expectedHeight)
        check(id > 0 && logicalW == expectedWidth && logicalH == expectedHeight && physicalW == expectedWidth && physicalH == expectedHeight && density == expectedDensity) {
            "Android не предоставил отдельный экран ${expectedWidth}×${expectedHeight}/$expectedDensity без масштабирования"
        }
        check(flags and DISPLAY_TRUSTED != 0) { "Android не предоставил доверенный виртуальный экран" }
        check(desktopMode != DesktopModePolicy.SAMSUNG_DEX || flags and DISPLAY_ALLOWS_CONTENT_MODE_SWITCH != 0) {
            "One UI не разрешил системный оконный режим для виртуального экрана"
        }
    }
}

/** Single mouse state. CarLife touch becomes a real primary mouse gesture, including button events. */
internal class DexMouseState(private val width: Int = DexUserServicePolicy.WIDTH, private val height: Int = DexUserServicePolicy.HEIGHT) {
    init { DexUserServicePolicy.requireDimensions(width, height) }
    data class Event(val action: Int, val x: Int, val y: Int, val buttons: Int, val actionButton: Int = 0)
    var pressedButtons: Int = 0
        private set

    fun events(action: Int, x: Int, y: Int, buttons: Int): List<Event> {
        require(action in setOf(0, 1, 2, 3, 7)) { "Недопустимое действие указателя" }
        require(x in 0 until width && y in 0 until height) { "Координаты за пределами экрана DeX" }
        require(buttons and 7 == buttons) { "Недопустимая кнопка мыши" }
        val result = ArrayList<Event>(6)
        fun emit(a: Int, b: Int, ab: Int = 0) { result += Event(a, x, y, b, ab) }
        when (action) {
            0 -> {
                if (pressedButtons != 0) emit(3, 0)
                emit(7, 0)
                pressedButtons = buttons.takeIf { it != 0 } ?: 1
                emit(0, pressedButtons)
                for (button in listOf(1, 2, 4)) if (pressedButtons and button != 0) emit(11, pressedButtons, button)
            }
            1 -> {
                if (pressedButtons != 0) {
                    for (button in listOf(1, 2, 4)) if (pressedButtons and button != 0) {
                        pressedButtons = pressedButtons and button.inv()
                        emit(12, pressedButtons, button)
                    }
                    emit(1, 0)
                    emit(7, 0)
                }
            }
            2, 7 -> emit(if (pressedButtons == 0) 7 else 2, pressedButtons)
            3 -> {
                if (pressedButtons != 0) emit(3, 0)
                pressedButtons = 0
            }
        }
        return result
    }

    fun reset() { pressedButtons = 0 }
}
