// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Limits for the navigator, app and assistant tiles. Independent of phone display settings and parent geometry. */
internal object EmbeddedDisplayPolicy {
    const val MAX_CHILDREN = 3
    fun displayFlags(sdkInt: Int, alwaysUnlocked: Boolean = false): Int =
        DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, sdkInt, alwaysUnlocked)
    const val MAX_WIDTH = 3840
    const val MAX_HEIGHT = 2160
    const val MAX_DENSITY = 1280
    // One 2x navigator plus one ordinary tile, even at the full parent dimensions.
    const val MAX_TOTAL_PIXELS = 10_368_000L
    private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val classPattern = Regex("\\.?[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*")

    /** The parent is the desktop display owned by our own package, never a third-party launcher. */
    fun requireParent(mode: Int, displayId: Int) {
        check(mode == DesktopModePolicy.ANDROID_DESKTOP && displayId > 0) { "Виджет доступен только на активном рабочем столе HeadSwap" }
    }

    fun requireSize(width: Int, height: Int, density: Int) {
        requireDimensions(width, height)
        require(density in 96..MAX_DENSITY) { "Плотность виджета должна быть от 96 до $MAX_DENSITY DPI" }
    }

    fun requireDimensions(width: Int, height: Int) {
        require(width in 128..MAX_WIDTH && height in 128..MAX_HEIGHT) {
            "Размер виджета должен быть 128–$MAX_WIDTH × 128–$MAX_HEIGHT"
        }
    }

    /** Excludes the tile being resized; admission must precede producer or input changes. */
    fun requirePixelBudget(otherPixels: Long, width: Int, height: Int) {
        requireDimensions(width, height)
        require(otherPixels in 0..MAX_TOTAL_PIXELS && width.toLong() * height <= MAX_TOTAL_PIXELS - otherPixels) {
            "Превышен общий размер встроенных экранов"
        }
    }

    fun requireCapacity(existing: Int) {
        check(existing in 0 until MAX_CHILDREN) { "Одновременно доступны не более трёх виджетов" }
    }

    /** Алиса Яндекса открывается по ASSIST, а не из списка запуска: только этот компонент идёт без CATEGORY_LAUNCHER. */
    fun assistLaunch(packageName: String, className: String): Boolean =
        (packageName == "com.yandex.searchapp" || packageName == "ru.yandex.searchplugin") &&
            className == "ru.yandex.searchplugin.AssistantActivityAlias"

    /** WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW. */
    const val WINDOWING_MULTI_WINDOW = 6
    /** ActivityOptions.KEY_LAUNCH_WINDOWING_MODE (скрытая константа; Bundle читает system_server, не hidden API). */
    const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
    private val multiWindowPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta")

    /**
     * Telegram в полноэкранном режиме берёт высоту строки состояния телефона (152 px при 520 dpi), если уже открывался
     * на телефоне, и игнорирует нулевой инсет плитки: сверху пустая полоса. В многооконном режиме он верит инсету (ADR 0031).
     * Портретные по манифесту (Звук и т.п.) в полноэкранном режиме система рисует узким столбиком с полями
     * (letterbox); в многооконном она игнорирует запрос ориентации и растягивает окно на плитку.
     * Остальным не включаем: YouTube в многооконном режиме не уходит на весь экран.
     */
    fun multiWindowLaunch(packageName: String, screenOrientation: Int = -1): Boolean =
        packageName in multiWindowPackages || screenOrientation in portraitOrientations

    /** ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, SENSOR_PORTRAIT, REVERSE_PORTRAIT, USER_PORTRAIT. */
    private val portraitOrientations = setOf(1, 7, 9, 12)

    fun requireChildId(id: Int, parentId: Int) {
        check(parentId > 0 && id > 0 && id != parentId) { "Виджет не получил собственный дополнительный экран" }
    }

    fun requirePackage(packageName: String) {
        require(packagePattern.matches(packageName)) { "Неверный пакет приложения виджета" }
    }

    fun launchCommand(id: Int, parentId: Int, userId: Int, packageName: String, className: String): Array<String> {
        requireChildId(id, parentId)
        require(userId >= 0)
        requirePackage(packageName)
        require(classPattern.matches(className)) { "Неверный компонент приложения виджета" }
        return arrayOf("/system/bin/am", "start", "--user", userId.toString(), "--display", id.toString(),
            "--windowingMode", "1", "-f", "0x34000000", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-n", "$packageName/$className")
    }
}

/** One gesture per tile; malformed/orphaned events cannot become input on another display. */
internal class EmbeddedTouchState(private val width: Int, private val height: Int) {
    data class Event(val action: Int, val x: Float, val y: Float)
    private var pressed = false
    private var lastX = 0f
    private var lastY = 0f

    fun events(action: Int, x: Float, y: Float): List<Event> {
        require(action in 0..3) { "Виджет принимает только одиночное касание" }
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height) {
            "Координаты за пределами виджета"
        }
        val out = ArrayList<Event>(2)
        if (action == 0) {
            cancel()?.let(out::add)
            pressed = true
        } else if (!pressed) return emptyList()
        out.add(Event(action, x, y))
        lastX = x
        lastY = y
        if (action == 1 || action == 3) pressed = false
        return out
    }

    fun cancel(): Event? {
        if (!pressed) return null
        pressed = false
        return Event(3, lastX, lastY)
    }
}
