package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.AppLog

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

    /** Выбор в Picker: приложение уходит в нижний ряд главного, туда же и возвращаемся. */
    fun addApp(ref: AppRef) {
        model.addApp(ref)
        model.go(Area.Home)
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
        if (ref.pkg == model.navigatorPkg) { openNavigatorWindow(); return }
        // тап по уже открытому приложению: экран не меняется, поэтому проверку задачи запускаем руками
        if (model.area == Area.App(ref)) { EmbedHost.refresh(EmbedHost.app); return }
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
        if (model.navigatorPkg == null) openNavigatorChoice() else openNavigatorWindow()
    }

    /** Навигатор на всю область. Повторный тап, когда он уже развёрнут, заново проверяет его задачу (после Back она могла уйти). */
    private fun openNavigatorWindow() {
        if (model.area == Area.Navigator) EmbedHost.refresh(EmbedHost.nav) else model.go(Area.Navigator)
    }

    fun openNavigatorChoice() {
        model.settingsPage = SettingsPage.NAVIGATOR
        model.go(Area.Settings)
    }

    /** Кнопка «Назад» рейки: Back в открытое окно; если приложение вышло из корня - на главный. */
    fun embeddedBack() {
        AppLog.i("LAUNCHER Назад на рейке: экран=${model.area}", false)
        when (model.area) {
            is Area.App -> EmbedHost.back(EmbedHost.app) { backClosed(EmbedHost.app) }
            Area.Navigator -> EmbedHost.back(EmbedHost.nav) { backClosed(EmbedHost.nav) }
            else -> model.home()
        }
    }

    /** Back вывел приложение из корня (задачи на дисплее нет): если оно ещё на экране - на главный. */
    private fun backClosed(slot: EmbedSlot) {
        val a = model.area
        val shown = (slot === EmbedHost.app && a is Area.App) || (slot === EmbedHost.nav && a == Area.Navigator)
        AppLog.i("LAUNCHER Back закрыл ${slot.name}, экран=$a${if (shown) ", возврат на главный" else ", экран уже другой"}", false)
        if (shown) { model.launchLock.finish(); model.starting = null; model.home() }
    }

    /** Слот сообщил, что задачи приложения больше нет (процесс убит, Back из корня, окно потеряно): слой убираем. */
    internal fun embedGone(slot: EmbedSlot) = backClosed(slot)

    /** Аппаратный Back ГУ: из встроенного приложения - назад в него, из экранов лаунчера - на главный, на главном ничего. */
    fun hardwareBack() {
        AppLog.i("LAUNCHER аппаратный Back: экран=${model.area}", false)
        when (model.area) {
            Area.Home -> Unit
            is Area.App, Area.Navigator -> embeddedBack()
            else -> model.home()
        }
    }
}
