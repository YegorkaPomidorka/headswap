package com.yegorkapomidorka.headswap.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yegorkapomidorka.headswap.carapp.CarAppHost
import com.yegorkapomidorka.headswap.carapp.CarConfigMath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Что показано в области справа от рейки. Рейка остаётся всегда: это путь домой. */
sealed interface Area {
    data object Home : Area
    data object AllApps : Area
    data object Picker : Area
    data object Settings : Area
    data object Calls : Area
    /** Список медиаприложений (переключатель плееров на карточке музыки). */
    data object Players : Area
    /** Навигатор, развёрнутый на всю область. */
    data object Navigator : Area
    /** Чужое приложение в дочернем окне. */
    data class App(val ref: AppRef) : Area
}

/**
 * Состояние лаунчера живёт в процессе, а не в Activity: дочерние окна и список приложений переживают
 * пересоздание Activity (поворот, смена конфигурации ГУ).
 */
internal object LauncherModel {
    private lateinit var appCtx: Context
    private lateinit var prefs: LauncherPrefs
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "headswap-launcher-io").apply { isDaemon = true } }

    val context: Context get() = appCtx

    var area by mutableStateOf<Area>(Area.Home)
    var theme by mutableStateOf(ThemeChoice.DARK)
    var railSize by mutableStateOf(RailSize.NORMAL)
    var mapLeft by mutableStateOf(false)
    var animations by mutableStateOf(true)
    var autoplay by mutableStateOf(true)
    /** «Уведомления» телефона на экране ГУ (слушатель - [NotificationsRepo]). */
    var notifications by mutableStateOf(true)
    /** «Алиса только голосом» ([LauncherPrefs.aliceVoiceOnly]). */
    var aliceVoiceOnly by mutableStateOf(true)
    var localPlayer by mutableStateOf<String?>(null)
    var onlinePlayer by mutableStateOf<String?>(null)
    /** Режим темы ГУ (CHANGE_MD_THEME, 0 день, 1 ночь, 2 авто); null - ГУ ещё не присылала. Обновляет [LauncherRoot]. */
    var huTheme by mutableStateOf<Int?>(null)
    /** Дисплей, на котором музыку уже включили автоматически: автозапуск один раз на рабочий стол. */
    var autoplayedDisplay = -1
    /** Светлая ли палитра сейчас: выбор «Тема» и, для «Авто», режим фар машины. */
    val lightTheme: Boolean get() = ThemeChoice.light(theme, huTheme)
    /** Свои цвета «Вид › Цвета» ([ColorSet]): ночные - для тёмной палитры, дневные - для светлой. */
    var nightColors by mutableStateOf(ColorSet.NIGHT)
    var dayColors by mutableStateOf(ColorSet.DAY)
    /** На экране «Цвета» настраиваются дневные (иначе ночные); пока он открыт, весь стол показывает этот набор. */
    var colorsEditDay by mutableStateOf(false)
    private val colorsOpen: Boolean get() = area == Area.Settings && settingsPage == SettingsPage.COLORS
    /** Светлая ли палитра на экране: на «Цветах» - редактируемый набор, иначе [lightTheme]. */
    val shownLight: Boolean get() = if (colorsOpen) colorsEditDay else lightTheme
    val palette: LauncherPalette
        get() = shownLight.let { LauncherColors.palette(it, if (it) dayColors else nightColors) }
    /** Большая плашка главного; малая - [smallView]; раскладка - [homeLayout]. Действуют через [tiles]. */
    var cardView by mutableStateOf(CardView.NAVIGATOR)
    var smallView by mutableStateOf(CardView.MUSIC)
    var homeLayout by mutableStateOf(HomeLayout.QUAD)
    /** Что листают «Слои» и в каком порядке ([LayerList]). */
    var layers by mutableStateOf(LayerList.DEFAULT)
    /** Пакет приложения плашки-приложения ([CardView.APP]); по умолчанию YouTube. */
    var tileApp by mutableStateOf(FullscreenApp.YOUTUBE)
    /** Ключ запуска приложения плашки из каталога; null - не установлено (или каталог ещё грузится). */
    val tileAppKey: String? get() = catalog.firstOrNull { it.packageName == tileApp }?.key
    /** Установлено ли приложение; пока каталог не загружен - да, чтобы не сбросить выбор. Навигатор - нет: у него своё окно. */
    fun installed(pkg: String): Boolean = pkg != navigatorPkg && (!catalogLoaded || catalog.any { it.packageName == pkg })
    /** Плашки с учётом правил. */
    val tiles: HomeTiles
        get() = HomeTilesLogic.normalize(HomeTiles(cardView, smallView, tileApp), homeLayout, installed(tileApp))
    /** Где на экране большая и малая плашки (корень): по ним стоят ⇄ на стыке и «Слои» ленты поверх живых окон. */
    var bigTileRect by mutableStateOf<Rect?>(null)
    var smallTileRect by mutableStateOf<Rect?>(null)
    /** Плашка-приложение на главном: туда слой app ставит окно приложения. */
    var appCardRect by mutableStateOf<Rect?>(null)
    /** Выбор приложения (Picker) открыт из «Слоёв»: выбранное уходит в слои, а не в нижний ряд. */
    var pickerForLayers by mutableStateOf(false)
    /** Прокрутка раздела «Экран»: после выбора слоя возвращаемся туда же, где «Слои». */
    var settingsHomeScroll = 0
    var apps by mutableStateOf<List<AppRef>>(emptyList())
    var savedNavigator by mutableStateOf<String?>(null)
    var musicPlayer by mutableStateOf<String?>(null)
    var lastOnlinePlayer by mutableStateOf<String?>(null)
    var lastLocalPlayer by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<List<DesktopAppCatalog.Entry>>(emptyList())
    var catalogLoaded by mutableStateOf(false)
    var status by mutableStateOf(StatusInfo())
    /** Подтверждение удаления приложения из списка («Убрать?»). */
    var confirmRemove by mutableStateOf<AppRef?>(null)
    /** Область справа от рейки и карточка навигации - в координатах корня Compose. */
    var areaRect by mutableStateOf<Rect?>(null)
    var navCardRect by mutableStateOf<Rect?>(null)
    /** Нижний край сплошной строки статуса в координатах корня; null - на этом экране строки нет (оверлей уведомлений вешается на верх области). */
    var statusBottom by mutableStateOf<Float?>(null)
    /** Что занято текстом на строке статуса (дата и значки, часы в узкой рейке) - [left, right] в координатах корня; полоска уведомлений их объезжает. */
    var statusBusyX by mutableStateOf<List<ClosedFloatingPointRange<Float>>>(emptyList())
    /** «Скрыть строку статуса» (Для разработчиков): полосы нет, уведомления висят чёлкой от верхнего края ([NotificationsOverlay]). */
    var hideStatusBar by mutableStateOf(false)
    /** На экране стоит скрытая строка статуса: там, где была бы полоска, висит чёлка уведомлений. */
    var statusSlot by mutableStateOf(false)

    /** Чужой лаунчер из DexPrefs.desktopComponent в окне над панелью навигации; null - наш рабочий стол. Ведёт [ForeignActions]. */
    var foreign by mutableStateOf<AppRef?>(null)
    /** Панель Алисы (кнопка голоса) поверх рабочего стола: слева за рейкой, под ней затемнение. */
    var assistantOpen by mutableStateOf(false)
    /** Панель Алисы скрыта (только голос): дочерний дисплей живёт, слой за экраном, затемнения нет. */
    var assistantHidden by mutableStateOf(false)
    /** Оверлей «Недавние» на экране. */
    var recentsOpen by mutableStateOf(false)
    /** Приложения оверлея; null - список ещё запрашивается. */
    var recents by mutableStateOf<List<AppRef>?>(null)
    /** Пакеты, виденные на экране ГУ в этом сеансе, свежие первыми ([ForeignDesktop.mergeHistory]). */
    var recentsHistory: List<String> = emptyList()
    /** Последний выбор из DexPrefs, который уже разобрали (пустая строка - ещё не разбирали, null - наш стол). */
    var foreignSeen: String? = ""
    /** Метка отката [ForeignDesktop.brokenKey]: этот лаунчер на этом экране ГУ не стартовал. В prefs не пишется. */
    var foreignBroken: String? = null
    /** Экран ГУ, для которого собрана история. */
    var foreignDisplay = -1

    /** Ключ «пакет/класс» приложения в окне приложения; null - ещё ни разу не открывали. */
    var appKey by mutableStateOf<String?>(null)
    /** Страница настроек, с которой открыть экран (выбор навигатора по тапу на пустую карточку). */
    var settingsPage by mutableStateOf(SettingsPage.LOOK)
    /** Лицензия (индекс в About.licenses), чей полный текст открыт в «О приложении»; null - список. */
    var aboutLicense by mutableStateOf<Int?>(null)

    /** «Назад» внутри настроек (см. [BackNav.settingsStep]); false - шагов нет, выходить с экрана. */
    fun settingsBack(): Boolean {
        val (page, licenseOpen) = BackNav.settingsStep(settingsPage, aboutLicense != null) ?: return false
        settingsPage = page
        if (!licenseOpen) aboutLicense = null
        return true
    }
    /** Плотность дисплея ГУ: дочерние окна живут с ней же. */
    var densityDpi = 160
    /** Страница «Все приложения» запоминается между заходами. */
    var allAppsPage = 0

    val launchDebounce = Debounce()
    val launchLock = LaunchLock()
    /** Приложение, которое сейчас стартует: под «Запускаем <название>…». */
    var starting by mutableStateOf<String?>(null)

    fun init(ctx: Context) {
        if (ready) return
        appCtx = ctx.applicationContext
        prefs = LauncherPrefs(appCtx)
        theme = prefs.theme
        nightColors = prefs.nightColors
        dayColors = prefs.dayColors
        railSize = prefs.railSize
        mapLeft = prefs.mapLeft
        animations = prefs.animations
        autoplay = prefs.autoplay
        notifications = prefs.notifications
        NotificationsRepo.setEnabled(notifications)
        aliceVoiceOnly = prefs.aliceVoiceOnly
        hideStatusBar = prefs.hideStatusBar
        localPlayer = prefs.localPlayer
        onlinePlayer = prefs.onlinePlayer
        cardView = prefs.cardView
        smallView = prefs.smallView
        homeLayout = prefs.homeLayout
        layers = prefs.layers
        tileApp = prefs.tileApp
        apps = prefs.apps
        savedNavigator = prefs.navigator
        musicPlayer = prefs.musicPlayer
        lastOnlinePlayer = prefs.lastOnlinePlayer
        lastLocalPlayer = prefs.lastLocalPlayer
        missedSeenMs = prefs.missedSeenMs
        EmbedHost.onGone = { LauncherActions.embedGone(it) }
        EmbedHost.onAssistantOpened = { LauncherActions.assistantOpened(it) }
        CarAppHost.scaleFor = { pkg -> CarConfigMath.step(prefs.mapScale(pkg)) }
        CarAppHost.allowFor = { pkg -> prefs.navHints(pkg) }
        LauncherActions.startRouteCatcher(context.applicationContext)
        ready = true
        reloadCatalog()
    }

    fun reloadCatalog() {
        io.execute {
            val pm = appCtx.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = runCatching {
                pm.queryIntentActivities(query, 0).mapNotNull { info ->
                    val a = info.activityInfo ?: return@mapNotNull null
                    DesktopAppCatalog.Entry(a.packageName, a.name,
                        runCatching { info.loadLabel(pm).toString() }.getOrDefault(a.packageName),
                        enabled = a.enabled && a.applicationInfo.enabled, exported = a.exported)
                }
            }.getOrElse { AppLog.i("Launcher catalog: ${it.javaClass.simpleName}", false); emptyList() }
            val selected = DesktopAppCatalog.select(found, appCtx.packageName)
            main.post { catalog = selected; catalogLoaded = true; applyShadowNav() } // выбранный навигатор известен только с каталогом
        }
    }

    fun packageLabel(pkg: String): String =
        runCatching { appCtx.packageManager.getApplicationLabel(appCtx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    fun loadPackageIcon(pkg: String): ImageBitmap? {
        icons["pkg:$pkg"]?.let { return it }
        val drawable = runCatching { appCtx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        return drawableToImage(drawable).also { icons["pkg:$pkg"] = it }
    }

    private fun drawableToImage(drawable: android.graphics.drawable.Drawable): ImageBitmap {
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return bmp.asImageBitmap()
    }

    fun label(ref: AppRef): String? = catalog.firstOrNull { it.packageName == ref.pkg && it.className == ref.cls }?.label

    /** Список пользователя без удалённых из системы приложений. */
    val visibleApps: List<AppRef>
        get() = if (!catalogLoaded) apps else apps.filter { label(it) != null }

    fun addApp(ref: AppRef) { apps = AppList.add(apps, ref).also { prefs.apps = it } }
    fun removeApp(ref: AppRef) { apps = AppList.remove(apps, ref).also { prefs.apps = it } }

    fun chooseTheme(value: ThemeChoice) { theme = value; prefs.theme = value }
    /** Слайдер «Цвета» двигается: только состояние, без записи; запись - [saveColors] по отпусканию. */
    fun editColors(set: ColorSet) { if (colorsEditDay) dayColors = set else nightColors = set }
    fun saveColors() { prefs.nightColors = nightColors; prefs.dayColors = dayColors }
    fun resetColors() { editColors(if (colorsEditDay) ColorSet.DAY else ColorSet.NIGHT); saveColors() }
    /** Открыть «Цвета»: сначала настраивается тот набор, что сейчас на экране. */
    fun openColors() { colorsEditDay = lightTheme; settingsPage = SettingsPage.COLORS }
    fun chooseRailSize(value: RailSize) { railSize = value; prefs.railSize = value }
    fun chooseMapLeft(left: Boolean) { mapLeft = left; prefs.mapLeft = left }
    fun chooseAnimations(on: Boolean) { animations = on; prefs.animations = on }
    fun chooseAutoplay(on: Boolean) { autoplay = on; prefs.autoplay = on }
    /** Из «Для разработчиков» на телефоне: лаунчер мог ещё не стартовать, поэтому префы — своим экземпляром. */
    fun chooseHideStatusBar(ctx: Context, on: Boolean) { hideStatusBar = on; LauncherPrefs(ctx.applicationContext).hideStatusBar = on }
    fun chooseAliceVoiceOnly(on: Boolean) {
        if (assistantOpen) LauncherActions.closeAssistant("переключили «Алиса только голосом»")
        aliceVoiceOnly = on; prefs.aliceVoiceOnly = on
    }
    fun chooseNotifications(on: Boolean) { notifications = on; prefs.notifications = on; NotificationsRepo.setEnabled(on) }
    fun chooseLocalPlayer(pkg: String?) { localPlayer = pkg; prefs.localPlayer = pkg }
    fun chooseOnlinePlayer(pkg: String?) { onlinePlayer = pkg; prefs.onlinePlayer = pkg }
    private val currentTiles: HomeTiles get() = HomeTiles(cardView, smallView, tileApp)
    private fun setTiles(t: HomeTiles) {
        cardView = t.big; prefs.cardView = t.big
        smallView = t.small; prefs.smallView = t.small
        if (t.app != tileApp) { tileApp = t.app; prefs.tileApp = t.app }
    }
    /** «Слои»: следующее содержимое большой плашки. */
    fun nextCardView() = setTiles(HomeTilesLogic.nextBig(currentTiles, homeLayout, ::installed, layers))
    /** Блок «Слои» в настройках «Экран»: что листает кнопка, по порядку. */
    fun addLayer(l: Layer) { layers = LayerList.add(layers, l).also { prefs.layers = it } }
    fun removeLayer(l: Layer) { layers = LayerList.remove(layers, l).also { prefs.layers = it } }
    /** ⇄: поменять плашки местами (только в двух плашках). */
    fun swapTiles() = setTiles(HomeTilesLogic.swap(currentTiles, homeLayout, installed(tileApp)))
    /** «Раскладка» в настройках: четыре плашки с картой справа/слева или две плашки. */
    fun chooseHomeLayout(layout: HomeLayout, left: Boolean = mapLeft) {
        homeLayout = layout; prefs.homeLayout = layout
        if (layout == HomeLayout.QUAD) chooseMapLeft(left)
        setTiles(HomeTilesLogic.normalize(currentTiles, layout, installed(tileApp)))
    }
    fun setNavigator(pkg: String?) { savedNavigator = pkg; prefs.navigator = pkg; applyShadowNav() }

    /** «Подсказки на приборке» по навигатору (h800): вкл - машинная часть / теневая AA, выкл - окно как на телефоне. */
    private var navHintsRev by mutableIntStateOf(0)
    fun navHints(pkg: String): Boolean { navHintsRev; return prefs.navHints(pkg) }
    fun chooseNavHints(pkg: String, on: Boolean) {
        prefs.setNavHints(pkg, on); navHintsRev++
        AppLog.i("LAUNCHER подсказки на приборке $pkg: ${if (on) "вкл" else "выкл"}", false)
        // как кнопка «Перезапустить навигатор»: старый режим закрывается целиком (хост или окно на свежей текстуре),
        // reconcile поднимает новый - свой хост Car App или обычное окно (CarAppHost.allowFor уже видит новое значение)
        if (navigatorPkg == pkg) EmbedHost.restartNav()
        applyShadowNav()
    }

    /**
     * Теневая AA (подсказки Google Карт) - только пока у выбранного навигатора подсказки включены: выкл - AA на телефоне
     * не трогаем совсем (h800). Стартует её BridgeService вместе с рабочим столом, здесь - смена навигатора или настройки.
     */
    private fun applyShadowNav() {
        if (!ready || com.yegorkapomidorka.headswap.dex.DexState.displayId <= 0) return
        val want = NavigatorPick.shadowWanted(com.yegorkapomidorka.headswap.aa.AaPrefs(appCtx).metaNavi, navigatorPkg, prefs::navHints)
        if (want) com.yegorkapomidorka.headswap.aa.AaShadowNav.start(appCtx) else com.yegorkapomidorka.headswap.aa.AaShadowNav.stop()
    }

    /** Смена «Масштаба карты» - перерисовать карточку (сами ступени в prefs, по пакету). */
    private var mapScaleRev by mutableIntStateOf(0)
    fun mapScale(pkg: String): Int { mapScaleRev; return prefs.mapScale(pkg) }
    fun chooseMapScale(pkg: String, step: Int) {
        prefs.setMapScale(pkg, step); mapScaleRev++
        if (CarAppHost.pkg != pkg) return
        CarAppHost.setScale(CarConfigMath.step(step)) // 2ГИС меняет сразу
        // Яндекс берёт dpi только при старте сессии (стенд 04.11: новые dpi на той же сессии не меняют карту)
        if (com.yegorkapomidorka.headswap.carapp.CarScaleRestart.needed(pkg)) EmbedHost.restartCar()
    }
    fun chooseMusicPlayer(pkg: String?) { musicPlayer = pkg; prefs.musicPlayer = pkg; noteOnlinePlayer(pkg) }
    /**
     * Выбрали или услышали [pkg]: онлайн-плеер становится вторым слотом чипа, свой - иконкой первого.
     * Иконка каждого слота держится, пока играет плеер другого.
     */
    fun noteOnlinePlayer(pkg: String?) {
        val next = PlayerChip.lastOnline(lastOnlinePlayer, pkg)
        if (next != lastOnlinePlayer) { lastOnlinePlayer = next; prefs.lastOnlinePlayer = next }
        val local = PlayerChip.lastLocal(lastLocalPlayer, pkg?.takeIf { it != context.packageName })
        if (local != lastLocalPlayer) { lastLocalPlayer = local; prefs.lastLocalPlayer = local }
    }
    /** Пропущенные не новее этого момента просмотрены: счётчик сбрасывается после тапа по карточке. */
    var missedSeenMs by mutableLongStateOf(0L)
        private set
    fun markMissedSeen() {
        val now = System.currentTimeMillis()
        missedSeenMs = now
        prefs.missedSeenMs = now
    }

    /** Установленные известные навигаторы (по каталогу приложений с лаунчерным входом). */
    val installedPackages: Set<String> get() = catalog.mapTo(HashSet()) { it.packageName }
    val navigatorPkg: String? get() = NavigatorPick.effective(savedNavigator, installedPackages)
    val installedNavigators: List<String> get() = NavigatorPick.KNOWN.filter { it in installedPackages }

    /** Предыдущий экран лаунчера для «Назад»; после самого «Назад» пуст, чтобы не ходить по кругу. */
    var previous: Area? = null
        private set

    fun go(next: Area) {
        if (next != area) {
            AppLog.i("LAUNCHER экран: $area -> $next", false)
            previous = BackNav.remembered(area)
        }
        area = next
    }
    fun home() { go(Area.Home); previous = null }

    /** «Назад» с экрана лаунчера: на предыдущий экран, если он был не главным, иначе на главный. */
    fun back() {
        val target = BackNav.target(area, previous)
        go(target)
        previous = null
    }

    fun now(): Long = SystemClock.uptimeMillis()

    // --- иконки приложений ---

    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun cachedIcon(ref: AppRef): ImageBitmap? = icons[ref.key]

    /** Вызывать не из UI-потока: иконка пакета может грузиться через другой процесс. */
    fun loadIcon(ref: AppRef): ImageBitmap? {
        icons[ref.key]?.let { return it }
        val drawable = runCatching { appCtx.packageManager.getActivityIcon(ComponentName(ref.pkg, ref.cls)) }.getOrNull()
            ?: return null
        return drawableToImage(drawable).also { icons[ref.key] = it }
    }
}
