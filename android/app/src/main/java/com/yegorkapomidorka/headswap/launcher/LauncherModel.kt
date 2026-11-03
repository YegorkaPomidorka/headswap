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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    var mapLeft by mutableStateOf(false)
    var animations by mutableStateOf(true)
    var autoplay by mutableStateOf(true)
    var localPlayer by mutableStateOf<String?>(null)
    var onlinePlayer by mutableStateOf<String?>(null)
    /** Режим темы ГУ (CHANGE_MD_THEME, 0 день, 1 ночь, 2 авто); null - ГУ ещё не присылала. Обновляет [LauncherRoot]. */
    var huTheme by mutableStateOf<Int?>(null)
    /** Дисплей, на котором музыку уже включили автоматически: автозапуск один раз на рабочий стол. */
    var autoplayedDisplay = -1
    /** Светлая ли палитра сейчас: выбор «Тема» и, для «Авто», режим фар машины. */
    val lightTheme: Boolean get() = ThemeChoice.light(theme, huTheme)
    var cardView by mutableStateOf(CardView.NAVIGATOR)
    var apps by mutableStateOf<List<AppRef>>(emptyList())
    var savedNavigator by mutableStateOf<String?>(null)
    var musicPlayer by mutableStateOf<String?>(null)
    var lastOnlinePlayer by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<List<DesktopAppCatalog.Entry>>(emptyList())
    var catalogLoaded by mutableStateOf(false)
    var status by mutableStateOf(StatusInfo())
    /** Подтверждение удаления приложения из списка («Убрать?»). */
    var confirmRemove by mutableStateOf<AppRef?>(null)
    /** Область справа от рейки и карточка навигации - в координатах корня Compose. */
    var areaRect by mutableStateOf<Rect?>(null)
    var navCardRect by mutableStateOf<Rect?>(null)

    /** Чужой лаунчер из DexPrefs.desktopComponent в окне над панелью навигации; null - наш рабочий стол. Ведёт [ForeignActions]. */
    var foreign by mutableStateOf<AppRef?>(null)
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
        mapLeft = prefs.mapLeft
        animations = prefs.animations
        autoplay = prefs.autoplay
        localPlayer = prefs.localPlayer
        onlinePlayer = prefs.onlinePlayer
        cardView = prefs.cardView
        apps = prefs.apps
        savedNavigator = prefs.navigator
        musicPlayer = prefs.musicPlayer
        lastOnlinePlayer = prefs.lastOnlinePlayer
        missedSeenMs = prefs.missedSeenMs
        EmbedHost.onGone = { LauncherActions.embedGone(it) }
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
            main.post { catalog = selected; catalogLoaded = true }
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
    fun chooseMapLeft(left: Boolean) { mapLeft = left; prefs.mapLeft = left }
    fun chooseAnimations(on: Boolean) { animations = on; prefs.animations = on }
    fun chooseAutoplay(on: Boolean) { autoplay = on; prefs.autoplay = on }
    fun chooseLocalPlayer(pkg: String?) { localPlayer = pkg; prefs.localPlayer = pkg }
    fun chooseOnlinePlayer(pkg: String?) { onlinePlayer = pkg; prefs.onlinePlayer = pkg }
    fun nextCardView() { cardView = cardView.next().also { prefs.cardView = it } }
    fun setNavigator(pkg: String?) { savedNavigator = pkg; prefs.navigator = pkg }
    fun chooseMusicPlayer(pkg: String?) { musicPlayer = pkg; prefs.musicPlayer = pkg; noteOnlinePlayer(pkg) }
    /** Выбрали или услышали [pkg]: если это онлайн-плеер, он становится вторым слотом чипа. */
    fun noteOnlinePlayer(pkg: String?) {
        val next = PlayerChip.lastOnline(lastOnlinePlayer, pkg)
        if (next != lastOnlinePlayer) { lastOnlinePlayer = next; prefs.lastOnlinePlayer = next }
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
