package com.yegorkapomidorka.headswap.launcher

/** Действия кнопок лаунчера в одном месте: переходы между экранами, запуск приложений, назад. */
object LauncherActions {
    private val model get() = LauncherModel

    fun goHome() { model.home() }
    fun openAllApps() { model.go(Area.AllApps) }
    fun openPicker() { model.go(Area.Picker) }
    fun openSettings() { model.go(Area.Settings) }
    fun openCalls() { model.go(Area.Calls) }
    fun cycleCardView() { model.nextCardView() }

    fun addApp(ref: AppRef) {
        model.addApp(ref)
        model.allAppsPage = Int.MAX_VALUE / 2 // «+» всегда последний: показываем последнюю страницу
        model.go(Area.AllApps)
    }

    fun confirmRemove() {
        model.confirmRemove?.let { model.removeApp(it) }
        model.confirmRemove = null
    }

    /** Антидребезг 400 мс и блокировка повторного тапа, пока приложение стартует (до 8 с). */
    fun openApp(ref: AppRef) {
        val now = model.now()
        if (!model.launchDebounce.allow(now) || model.launchLock.isLocked(now)) return
        model.launchLock.begin(ref.key, now)
        model.starting = model.label(ref) ?: ref.pkg
        model.go(Area.App(ref))
    }

    fun openNavigator() { model.go(Area.Navigator) }

    /** Кнопка «Назад» рейки: в открытом чужом приложении. */
    fun embeddedBack() { model.home() }

    /** Аппаратный Back ГУ: из встроенного приложения - назад в него, из экранов лаунчера - на главный, на главном ничего. */
    fun hardwareBack() {
        when (model.area) {
            Area.Home -> Unit
            is Area.App, Area.Navigator -> embeddedBack()
            else -> model.home()
        }
    }
}
