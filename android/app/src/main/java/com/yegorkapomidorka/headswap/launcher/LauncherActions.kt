package com.yegorkapomidorka.headswap.launcher

import android.app.ActivityOptions
import android.content.Intent
import android.view.Display
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.dex.DexState

/** Действия кнопок лаунчера в одном месте: переходы между экранами, запуск приложений, назад. */
object LauncherActions {
    private val model get() = LauncherModel

    /** Логотип на рейке: на главном - «Все приложения», на любом другом экране (и в открытом приложении) - домой. */
    fun logo() { if (model.area == Area.Home) model.go(Area.AllApps) else home() }

    /** Домой, единственная точка возврата на главный экран. */
    fun home() { if (model.foreign != null) ForeignActions.home() else model.home() }
    fun openPicker() { model.go(Area.Picker) }
    /** Шестерёнка на рейке: настройки; повторное нажатие - назад. */
    fun openSettings() {
        if (model.area == Area.Settings) { model.back(); return }
        model.settingsPage = SettingsPage.LOOK
        model.aboutLicense = null
        model.go(Area.Settings)
    }
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

    /** Карточка «Телефон» без доступа: окно Headswap на экране телефона с запросом разрешений карточки. */
    fun openPhoneRequest() {
        if (!model.launchDebounce.allow(model.now())) return
        val ctx = model.context
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_CMD, "phone_card_perm")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        runCatching { ctx.startActivity(intent, opts.toBundle()) }
            .onSuccess { AppLog.i("LAUNCHER запрос разрешений карточки «Телефон» открыт на телефоне", false) }
            .onFailure { AppLog.i("LAUNCHER запрос разрешений на телефоне: ${it.javaClass.simpleName}: ${it.message}", false) }
    }

    /** Выбор в Picker: приложение уходит в нижний ряд главного, туда же и возвращаемся. */
    fun addApp(ref: AppRef) {
        model.addApp(ref)
        home()
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

    /** «Яндекс — с Алисой», потом «Яндекс Старт» (старое имя пакета): тот же порядок, что в [com.yegorkapomidorka.headswap.dex.PhoneAssistant]. */
    private val ALICE_PACKAGES = listOf("com.yandex.searchapp", "ru.yandex.searchplugin")
    private const val ALICE_CLASS = "ru.yandex.searchplugin.AssistantActivityAlias"

    /** Ключ «пакет/класс» Алисы для дочернего окна: установленный пакет из списка, не нашли - первый (окно покажет ошибку). */
    internal fun aliceKey(): String {
        val pm = model.context.packageManager
        val pkg = ALICE_PACKAGES.firstOrNull { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess } ?: ALICE_PACKAGES[0]
        return "$pkg/$ALICE_CLASS"
    }

    /** Кнопка голоса: панель Алисы слева за рейкой; повторное нажатие закрывает. Главный поток. */
    fun toggleAssistant() { if (model.assistantOpen) closeAssistant("повторная кнопка голоса") else openAssistant() }

    fun openAssistant() {
        if (model.assistantOpen) return
        AppLog.i("ASSIST панель открыта, экран=${model.area}", false)
        model.assistantOpen = true
    }

    /** Закрыть панель и отпустить дочерний дисплей; [why] - причина для лога. */
    fun closeAssistant(why: String) {
        if (!model.assistantOpen) return
        AppLog.i("ASSIST панель закрыта: $why", false)
        model.assistantOpen = false
        com.yegorkapomidorka.headswap.dex.PhoneAssistant.stopWatch()
        EmbedHost.want(EmbedHost.alice, null, 0, 0)
    }

    /**
     * Алиса открыла в своей панели другое приложение. Маршрут (Навигатор по yandexnavi://build_route_on_map) - как в AA:
     * задачу закрываем, панель тоже, навигатор на всю область и ACTION_NAVIGATE + geo: в его машинную часть. Навигатор
     * обычным окном - переносим задачу в его окно. Не маршрут - оставляем в панели. Главный поток.
     */
    internal fun assistantOpened(t: android.app.ActivityManager.RunningTaskInfo) {
        if (!model.assistantOpen) return
        val pkg = t.topActivity?.packageName ?: return
        val data = t.baseIntent.dataString
        val geo = com.yegorkapomidorka.headswap.carapp.RouteIntent.geoFor(data)
        if (geo == null) { AppLog.i("ASSIST $pkg не маршрут ($data), остаётся в панели", false); return }
        val navigate = android.content.Intent(com.yegorkapomidorka.headswap.carapp.RouteIntent.ACTION_NAVIGATE, android.net.Uri.parse(geo))
        val car = com.yegorkapomidorka.headswap.carapp.CarAppHost.pkg
        AppLog.i("ASSIST маршрут от Алисы $geo -> ${car?.let { "машинная часть $it" } ?: "окно навигатора ${model.navigatorPkg}"}", false)
        val taskId = t.taskId
        Thread({
            val assist = com.yegorkapomidorka.headswap.dex.PhoneAssistant
            if (car != null) assist.removeTask(taskId)
            else EmbedHost.navDisplayId().takeIf { it > 0 }?.let { d -> runCatching { assist.moveTask(taskId, d) } } ?: assist.removeTask(taskId)
        }, "assist-route").start()
        closeAssistant("маршрут -> навигатор")
        if (model.navigatorPkg != null && model.area != Area.Navigator) model.go(Area.Navigator)
        if (car != null) com.yegorkapomidorka.headswap.carapp.CarAppHost.newIntent(navigate)
    }

    /** Кнопка MEDIA ГУ: плеер на весь экран; плеер не выбран или не установлен - выбор плеера. */
    fun openMusic() {
        val pkg = MusicController.state.pkg ?: model.musicPlayer
        if (pkg == null || model.catalog.none { it.packageName == pkg }) openPlayers() else openMusicApp()
    }

    /** Старт сессии с «Включать музыку автоматически»: играет активная сессия, иначе выбранный или свой плеер. Главный поток. */
    fun autoplay() {
        val display = DexState.displayId
        if (!model.autoplay || display < 0 || display == model.autoplayedDisplay) return
        model.autoplayedDisplay = display
        val st = MusicController.state
        val pkg = model.musicPlayer ?: model.localPlayer
        AppLog.i("LAUNCHER автозапуск музыки: ${if (st.playing) "уже играет" else st.pkg ?: pkg ?: "плеера нет"}", false)
        if (st.playing) return
        if (st.hasSession) MusicController.playPause(model.context, null) else pkg?.let { MusicController.startPlayer(model.context, it) }
    }

    /** «Плеер телефона» на чипе: без выбранного приложения, играет любая активная сессия. */
    fun choosePhonePlayer() { model.chooseMusicPlayer(null) }

    /** Выбранный плеер запоминается и становится текущим: играет сразу, если у него есть сессия или MediaBrowserService. */
    fun choosePlayer(pkg: String) {
        model.chooseMusicPlayer(pkg)
        // свой плеер из списка всех плееров становится первым слотом чипа
        if (pkg !in PlayerChip.ONLINE) model.chooseLocalPlayer(pkg)
        MusicController.startPlayer(model.context, pkg) // главный поток: MediaBrowser создаёт Handler, connect асинхронный
        home()
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
        model.aboutLicense = null
        model.go(Area.Settings)
    }

    /**
     * Кнопка «Назад» рейки: Back в открытое окно (если приложение вышло из корня - на главный); с экрана лаунчера - на
     * предыдущий экран или главный (в «О приложении» сначала шаг внутри настроек); на главном ничего.
     */
    private var lastAppBackAt = 0L

    fun embeddedBack() {
        AppLog.i("LAUNCHER Назад на рейке: экран=${model.area}", false)
        // панель Алисы закрывается сама, Back в Яндекс не шлём
        if (model.assistantOpen) { closeAssistant("Назад"); return }
        when (model.area) {
            is Area.App -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (DoubleBack.isDouble(lastAppBackAt, now)) {
                    lastAppBackAt = 0L
                    AppLog.i("LAUNCHER двойное Назад: на главный, приложение в фоне", false)
                    backClosed(EmbedHost.app)
                } else {
                    lastAppBackAt = now
                    EmbedHost.back(EmbedHost.app) { backClosed(EmbedHost.app) }
                }
            }
            Area.Navigator -> EmbedHost.back(EmbedHost.nav) { backClosed(EmbedHost.nav) }
            Area.Home -> Unit
            Area.Settings -> if (!model.settingsBack()) model.back()
            else -> model.back()
        }
    }

    /** Back вывел приложение из корня (задачи на дисплее нет): если оно ещё на экране - на главный. */
    private fun backClosed(slot: EmbedSlot) {
        val a = model.area
        val shown = (slot === EmbedHost.app && a is Area.App) || (slot === EmbedHost.nav && a == Area.Navigator)
        AppLog.i("LAUNCHER Back закрыл ${slot.name}, экран=$a${if (shown) ", возврат на главный" else ", экран уже другой"}", false)
        if (shown) { model.launchLock.finish(); model.starting = null; home() }
    }

    /** Слот сообщил, что задачи приложения больше нет (процесс убит, Back из корня, окно потеряно): слой убираем. */
    internal fun embedGone(slot: EmbedSlot) {
        if (slot === EmbedHost.alice) { closeAssistant("задача Алисы исчезла"); return }
        if (model.foreign != null) { if (slot === EmbedHost.app) ForeignActions.gone(); return }
        backClosed(slot)
    }

    /** Аппаратный Back ГУ - то же, что «Назад» на рейке (в режиме чужого лаунчера - ◀ панели). */
    fun hardwareBack() {
        AppLog.i("LAUNCHER аппаратный Back: экран=${model.area}", false)
        if (model.foreign != null) { ForeignActions.press(ForeignDesktop.NavKey.BACK); return }
        embeddedBack()
    }
}
