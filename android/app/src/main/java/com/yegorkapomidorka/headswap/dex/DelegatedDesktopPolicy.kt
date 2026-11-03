// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Permission adoption changes permission checks, never Linux identity or display ownership. */
internal object DelegatedDesktopPolicy {
    const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
    const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    const val REAL_GET_TASKS = "android.permission.REAL_GET_TASKS"

    fun requireOwner(processUid: Int, appUid: Int, callerUid: Int) {
        if (appUid % 100000 < 10000 || processUid != appUid || callerUid != appUid) {
            throw SecurityException("Служба рабочего стола доступна только собственному приложению")
        }
    }

    fun requirePermissions(trustedDisplay: Boolean, input: Boolean) {
        if (!trustedDisplay || !input) throw SecurityException("Доступ рабочего стола завершён; повторно запустите службу доступа")
    }

    fun requireMode(mode: Int) {
        DesktopModePolicy.requireMode(mode)
        require(mode != DesktopModePolicy.SAMSUNG_DEX) { "Эта служба поддерживает только Android Desktop; Samsung DeX не поддерживается" }
    }

    data class Component(val packageName: String, val className: String, val ownActivity: Boolean)

    fun desktopComponent(mode: Int, displayId: Int, appPackage: String): Component {
        requireMode(mode)
        require(displayId > 0) { "Запуск на основном экране запрещён" }
        EmbeddedDisplayPolicy.requirePackage(appPackage)
        return Component(appPackage, DesktopModePolicy.ANDROID_HOME_CLASS, true)
    }

    /**
     * [visible]: задача на экране (TaskInfo.isVisible, API 32+); после moveTaskToBack она остаётся первой в списке, но не видна.
     * [displayId]/[userId] = [UNKNOWN], если Android не отдал их приложению (скрытые поля TaskInfo).
     */
    data class Task(val displayId: Int, val userId: Int, val basePackage: String?, val topPackage: String?, val visible: Boolean = true)

    const val UNKNOWN = -1

    /**
     * null means unavailable/filtered, not an empty display. Never examine another display or user.
     * Задача с неизвестным дисплеем учитывается только по пакету: на практике у приложения одна задача (повторный запуск
     * переиспользует её), так что оценка верна, пока то же приложение не открыто и на экране телефона.
     */
    fun taskState(id: Int, userId: Int, packageName: String, permitted: Boolean, tasks: List<Task>?): Int {
        require(id > 0) { "Проверка основного экрана запрещена" }
        EmbeddedDisplayPolicy.requirePackage(packageName)
        if (!permitted || tasks == null) return -1
        val owned = tasks.filter {
            if (it.displayId == UNKNOWN || it.userId == UNKNOWN) it.basePackage == packageName || it.topPackage == packageName
            else it.displayId == id && it.userId == userId
        }
        val first = owned.firstOrNull()
        return when {
            first?.topPackage == packageName -> if (first.visible) 2 else 3
            owned.any { it.basePackage == packageName || it.topPackage == packageName } -> 1
            else -> 0
        }
    }

    /**
     * Пакеты задач экрана [id] для «Недавних», свежие первыми, без повторов. null, если права или список недоступны либо
     * хоть у одной задачи не опознан экран или пользователь: иначе в список попали бы пакеты экрана телефона.
     */
    fun childPackages(id: Int, userId: Int, permitted: Boolean, tasks: List<Task>?): List<String>? {
        require(id > 0) { "Проверка основного экрана запрещена" }
        if (!permitted || tasks == null) return null
        if (tasks.any { it.displayId == UNKNOWN || it.userId == UNKNOWN }) return null
        return tasks.filter { it.displayId == id && it.userId == userId }
            .mapNotNull { it.basePackage ?: it.topPackage }.distinct()
    }
}

/** Every successful caller receives producer-release ACK, including concurrent/repeated destroy. */
internal class DelegatedShutdownGate {
    @Volatile var started = false
        private set
    private var failure: Throwable? = null

    fun requireOpen() {
        check(!started) { "Служба рабочего стола уже завершена" }
    }

    @Synchronized fun close(release: () -> Unit) {
        if (started) {
            failure?.let { throw it }
            return
        }
        started = true
        try { release() }
        catch (error: Throwable) {
            failure = error
            throw error
        }
    }
}
