package com.yegorkapomidorka.headswap.launcher

/** Действия кнопок лаунчера в одном месте: переходы между экранами, запуск приложений, назад. */
object LauncherActions {
    private val model get() = LauncherModel

    fun goHome() { model.home() }
    fun openAllApps() { model.go(Area.AllApps) }
    fun openPicker() { model.go(Area.Picker) }
    fun openSettings() { model.settingsPage = SettingsPage.MAIN; model.go(Area.Settings) }
    /** Тап по карточке «Телефон»: экран «Вызовы»; пропущенные считаются просмотренными. */
    fun openCalls() {
        model.markMissedSeen()
        model.go(Area.Calls)
    }

    /** «Перезвонить» / «Позвонить»: звонок на номер, счётчик пропущенных сбрасывается. */
    fun callBack(number: String) {
        if (!model.launchDebounce.allow(model.now())) return
        model.markMissedSeen()
        PhoneController.dial(model.context, number)
    }
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
        // навигатор уже живёт в своём окне: приложение из списка с тем же пакетом разворачивает его
        if (ref.pkg == model.navigatorPkg) { model.go(Area.Navigator); return }
        model.launchLock.begin(ref.key, now)
        model.starting = model.label(ref) ?: ref.pkg
        model.appKey = ref.key
        model.go(Area.App(ref))
    }

    /** Тап по телу карточки музыки: приложение плеера на весь экран справа от рейки. */
    fun openMusicApp() {
        val pkg = MusicController.state.pkg ?: model.musicPlayer ?: return
        val entry = model.catalog.firstOrNull { it.packageName == pkg } ?: return
        openApp(AppRef(entry.packageName, entry.className))
    }

    fun openPlayers() { model.go(Area.Players) }

    /** Выбранный плеер запоминается и становится текущим: играет сразу, если у него есть сессия или MediaBrowserService. */
    fun choosePlayer(pkg: String) {
        model.chooseMusicPlayer(pkg)
        MusicController.startPlayer(model.context, pkg) // главный поток: MediaBrowser создаёт Handler, connect асинхронный
        model.home()
    }

    /** Блок навигации рейки и тап по карточке: навигатор на всю область справа от рейки. */
    fun openNavigator() {
        if (!model.launchDebounce.allow(model.now())) return
        if (model.navigatorPkg == null) openNavigatorChoice() else model.go(Area.Navigator)
    }

    fun openNavigatorChoice() {
        model.settingsPage = SettingsPage.NAVIGATOR
        model.go(Area.Settings)
    }

    /** Кнопка «Назад» рейки: Back в открытое окно; если приложение вышло из корня - на главный. */
    fun embeddedBack() {
        when (model.area) {
            is Area.App -> EmbedHost.back(EmbedHost.app) { if (model.area is Area.App) model.home() }
            Area.Navigator -> EmbedHost.back(EmbedHost.nav) { if (model.area == Area.Navigator) model.home() }
            else -> model.home()
        }
    }

    /** Аппаратный Back ГУ: из встроенного приложения - назад в него, из экранов лаунчера - на главный, на главном ничего. */
    fun hardwareBack() {
        when (model.area) {
            Area.Home -> Unit
            is Area.App, Area.Navigator -> embeddedBack()
            else -> model.home()
        }
    }
}
