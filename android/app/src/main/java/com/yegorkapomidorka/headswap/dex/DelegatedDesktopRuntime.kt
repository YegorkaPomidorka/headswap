// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.InputEvent
import android.view.Surface

/** Real app Context only: no shell attribution, app_process workarounds, commands or UiAutomation IPC. */
internal class DelegatedDesktopRuntime(private val context: Context, private val inputGateway: IDelegatedInputGateway) : DesktopRuntimeAccess {
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    // TestApi input access is process-local. Only the instrumented main process may
    // set display/actionButton; this ordinary helper owns and releases the producers.
    private val inputLeases = HashMap<Int, IBinder>() // Worker only, just like the display leases.
    // Дочерний экран автомобильного интерфейса Amap: id -> id родителя, и последний отправленный
    // screenMode. Только рабочий поток, как и leases.
    // Amap car UI child display: id -> parent id, plus the last screenMode sent. Worker thread only,
    // same as the leases.
    private val carShellDisplays = HashMap<Int, Int>()
    private val carShellMode = HashMap<Int, Int>()

    // TRUSTED / DESTROY_CONTENT / SUPPORTS_TOUCH are permission-gated platform flags omitted by the public SDK IntDef.
    @SuppressLint("WrongConstant")
    override fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int): OwnedDisplay {
        DelegatedDesktopPolicy.requireMode(mode)
        val name = "HeadSwap ${DesktopModePolicy.label(mode)}"
        if (mode == DesktopModePolicy.ANDROID_DESKTOP) deviceDisplay(surface, name, width, height, density,
            DesktopModePolicy.displayFlags(mode, Build.VERSION.SDK_INT, alwaysUnlocked = false))?.let { return it }
        return LocalDisplay(checkNotNull(displays.createVirtualDisplay(name, width, height, density, surface,
            DesktopModePolicy.displayFlags(mode, Build.VERSION.SDK_INT, DelegatedDesktopAccess.canKeepUnlocked(context)))) {
            "Android отклонил доверенный виртуальный экран"
        })
    }

    /**
     * Этап B: дисплей внутри VirtualDevice shell-хоста (своя группа на Android 17). Блокировку снимает
     * LOCK_STATE_ALWAYS_UNLOCKED устройства, OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED хост снимает сам. null - обычный путь.
     */
    private fun deviceDisplay(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int): OwnedDisplay? {
        if (!VdmDesktop.supported) return null
        val owner = Binder()
        val id = runCatching { inputGateway.createDeviceDisplay(surface, name, width, height, density, flags, owner) }.getOrDefault(-1)
        if (id <= 0) return null
        return runCatching { GatewayDisplay(inputGateway, id, owner, displays) }.getOrElse {
            android.util.Log.w("HeadSwap", "VDM display $id not visible in :delegated", it)
            runCatching { inputGateway.releaseDeviceDisplay(id) }
            null
        }
    }

    // TRUSTED / DESTROY_CONTENT / SUPPORTS_TOUCH are permission-gated platform flags
    // omitted by the public SDK IntDef. The shared engine verifies the returned display.
    @SuppressLint("WrongConstant")
    override fun createChildDisplay(surface: Surface, width: Int, height: Int, density: Int): OwnedDisplay {
        val name = "HeadSwap navigator widget"
        deviceDisplay(surface, name, width, height, density, EmbeddedDisplayPolicy.displayFlags(Build.VERSION.SDK_INT, false))?.let { return it }
        return LocalDisplay(checkNotNull(displays.createVirtualDisplay(name, width, height, density,
            surface, EmbeddedDisplayPolicy.displayFlags(Build.VERSION.SDK_INT, DelegatedDesktopAccess.canKeepUnlocked(context)))) {
            "Android отклонил экран виджета"
        })
    }

    override fun inject(event: InputEvent, id: Int, actionButton: Int) {
        check(id > 0) { "Ввод на основной экран запрещён" }
        inputGateway.injectInput(id, checkNotNull(inputLeases[id]) { "Экран ввода уже закрыт" }, event, actionButton)
    }

    override fun registerParentDisplay(id: Int, token: IBinder, width: Int, height: Int) {
        check(id > 0 && !inputLeases.containsKey(id)) { "Неверный собственный экран ввода" }
        // Remember before IPC: if registration succeeded but its reply was lost, cleanup
        // must still revoke this exact token before releasing the producer.
        inputLeases[id] = token
        inputGateway.registerParent(id, token, width, height)
    }

    override fun registerChildDisplay(parentId: Int, id: Int, token: IBinder, width: Int, height: Int) {
        EmbeddedDisplayPolicy.requireChildId(id, parentId)
        check(inputLeases.containsKey(parentId) && !inputLeases.containsKey(id)) { "Родительский экран ввода уже закрыт" }
        inputLeases[id] = token
        inputGateway.registerChild(parentId, id, token, width, height)
    }

    override fun resizeRegisteredDisplay(id: Int, token: IBinder, width: Int, height: Int) {
        check(inputLeases[id] == token) { "Экран ввода уже закрыт" }
        inputGateway.resizeDisplay(id, token, width, height)
        // Окно автомобильного интерфейса Amap изменило размер: досылаем ему тот же singleTask-intent
        // с новым screenMode. Activity не перезапускается, intent приходит в onActivityNewIntent,
        // который перечитывает screenMode и зовёт setScreenMode.
        // The Amap car UI window changed size: re-send the same singleTask intent with a new screenMode.
        // The Activity is not restarted; the intent lands in onActivityNewIntent, which re-reads
        // screenMode and calls setScreenMode.
        carShellDisplays[id]?.let { parentId -> syncCarScreenMode(id, parentId, width, height) }
    }

    /** Размер окна -> режим: почти весь экран это полный интерфейс (1), иначе карточка (2).
     *  Window size -> mode: near the whole screen is the full UI (1), anything smaller is the card (2). */
    private fun carScreenModeFor(id: Int, parentId: Int, width: Int = -1, height: Int = -1): Int {
        val parent = displays.getDisplay(parentId) ?: return UcarBind.MODE_FULL
        val child = displays.getDisplay(id) ?: return UcarBind.MODE_FULL
        val parentSize = android.graphics.Point().also { @Suppress("DEPRECATION") parent.getRealSize(it) }
        val childSize = android.graphics.Point().also { @Suppress("DEPRECATION") child.getRealSize(it) }
        val w = if (width > 0) width else childSize.x
        val h = if (height > 0) height else childSize.y
        if (parentSize.x <= 0 || parentSize.y <= 0 || w <= 0 || h <= 0) return UcarBind.MODE_FULL
        val percent = w.toLong() * h * 100 / (parentSize.x.toLong() * parentSize.y)
        return if (percent >= CAR_FULL_SCREEN_PERCENT) UcarBind.MODE_FULL else UcarBind.MODE_CARD
    }

    private fun syncCarScreenMode(id: Int, parentId: Int, width: Int, height: Int) {
        val mode = carScreenModeFor(id, parentId, width, height)
        if (carShellMode[id] == mode) return
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(UcarBind.PKG, UcarBind.CAR_ACT))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        UcarBind.apply(intent, mode)
        runCatching {
            check(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, intent)) {
                "Android не разрешил запуск приложения на собственном экране"
            }
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            carShellMode[id] = mode
            android.util.Log.i("HeadSwap", "UCAR screenMode=$mode display=$id ${width}x$height: intent sent")
        }.onFailure {
            android.util.Log.w("HeadSwap", "UCAR screenMode=$mode display=$id ${width}x$height: not sent, ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private companion object {
        /** Порог «окно занимает почти весь экран», ниже него считаем карточкой.
         *  Threshold for "the window covers nearly the whole screen"; below it the window is a card. */
        const val CAR_FULL_SCREEN_PERCENT = 85L
    }

    override fun unregisterDisplay(id: Int, token: IBinder) {
        if (inputLeases[id] != token) return
        inputLeases.remove(id)
        carShellDisplays.remove(id)
        carShellMode.remove(id)
        inputGateway.unregisterDisplay(id, token)
    }

    override fun launchDesktop(mode: Int, id: Int) {
        val component = DelegatedDesktopPolicy.desktopComponent(mode, id, context.packageName)
        launch(id, ComponentName(component.packageName, component.className), component.ownActivity, requireLauncher = false)
    }

    override fun launchChild(parentId: Int, id: Int, packageName: String, className: String) {
        // Reuse explicit component validation without executing the resulting shell arguments.
        EmbeddedDisplayPolicy.launchCommand(id, parentId, context.applicationInfo.uid / 100000, packageName, className)
        val fullClassName = if (className.startsWith('.')) packageName + className else className
        // Алиса - не LAUNCHER-activity: открывается по ASSIST, список запуска её не знает
        val assist = EmbeddedDisplayPolicy.assistLaunch(packageName, fullClassName)
        // У автомобильного интерфейса Amap нет записи LAUNCHER, запуск только явным компонентом,
        // поэтому пропускаем проверку «компонент должен быть в списке лаунчеров».
        // Amap's car UI has no LAUNCHER entry and can only be started as an explicit component,
        // so the "must be in the launcher list" check is skipped for it.
        // Пакет Amap: здесь ещё телефонный вход (из каталога лаунчера), переадресация на
        // UcarMapActivity происходит ниже, в launch(). Поэтому проверяем пакет, а не класс:
        // иначе экран не регистрируется и screenMode никогда не меняется.
        // Amap package: at this point it is still the phone entry from the launcher catalog;
        // the reroute to UcarMapActivity happens later inside launch(). So test the package, not
        // the class name: otherwise the display is never registered and screenMode never changes.
        val carShell = UcarBind.isAmap(ComponentName(packageName, fullClassName))
        // Экран автомобильного интерфейса Amap: запоминаем родителя и стартовый режим -
        // при последующих resize экран переключается на 1/2, не перезапуская Activity.
        // Amap car UI display: remember the parent and the initial mode; later resizes switch
        // the screen between 1 and 2 without restarting the Activity.
        val carMode = if (carShell) carScreenModeFor(id, parentId) else UcarBind.MODE_FULL
        if (carShell) {
            carShellDisplays[id] = parentId
            carShellMode[id] = carMode
            android.util.Log.i("HeadSwap", "UCAR register display=$id parent=$parentId startMode=$carMode")
        } else {
            // Экран переиспользуется (слот навигатора): сюда запустили другой пакет, значит
            // регистрация Amap больше не актуальна - иначе следующий resize отправит в этот
            // экран intent UcarMapActivity и выбьет чужую карту.
            // The display is reused (navigator slot): another package was launched here, so the
            // Amap registration is stale - otherwise the next resize would send a UcarMapActivity
            // intent into this display and knock out the other map.
            if (carShellDisplays.remove(id) != null) {
                carShellMode.remove(id)
                android.util.Log.i("HeadSwap", "UCAR unregister display=$id: now $packageName")
            }
        }
        launch(id, ComponentName(packageName, fullClassName), ownActivity = false,
            requireLauncher = !assist && !carShell, assist = assist, carMode = carMode)
    }

    private fun launch(id: Int, component: ComponentName, ownActivity: Boolean, requireLauncher: Boolean,
                       assist: Boolean = false, carMode: Int = UcarBind.MODE_FULL) {
        require(id > 0) { "Запуск на основном экране запрещён" }
        val pm = context.packageManager
        // Amap: любой вход (плитка, клавиша NAVI, настройки навигатора) ведёт во встроенный автомобильный
        // интерфейс UcarMapActivity, с extra isUcarMode / screenMode=1 / isActivityMetricsFirst.
        // Amap: every entry point (tile, NAVI key, navigator settings) goes to its built-in car UI
        // UcarMapActivity, with isUcarMode / screenMode=1 / isActivityMetricsFirst extras.
        val comp = if (UcarBind.isAmap(component)) ComponentName(UcarBind.PKG, UcarBind.CAR_ACT) else component
        val carShell = UcarBind.isCarShell(comp)
        // выключенный компонент getActivityInfo без MATCH_DISABLED_COMPONENTS не найдёт; info.enabled - значение из манифеста,
        // у activity-alias, включённого на ходу (сменённая иконка Телеграма), там false
        val info = pm.getActivityInfo(comp, 0)
        check(info.applicationInfo.enabled && (info.exported || ownActivity &&
            info.applicationInfo.uid == context.applicationInfo.uid && comp.packageName == context.packageName)) {
            "Приложение рабочего стола отключено или не разрешает внешний запуск"
        }
        // У автомобильного интерфейса Amap нет intent-filter LAUNCHER, запуск только явным компонентом.
        // Amap's car UI has no LAUNCHER intent-filter; it is started as an explicit component only.
        if (requireLauncher && !carShell) {
            val candidates = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(comp.packageName), 0)
            check(candidates.any { it.activityInfo?.let { candidate ->
                candidate.packageName == comp.packageName && candidate.name == comp.className &&
                    candidate.exported && candidate.applicationInfo.enabled
            } == true }) { "Виджет принимает только доступное приложение из списка запуска" }
        }
        val action = if (assist) EmbeddedDisplayPolicy.assistAction(comp.packageName, comp.className) else Intent.ACTION_MAIN
        val intent = Intent(action).setComponent(comp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (requireLauncher && !carShell) intent.addCategory(Intent.CATEGORY_LAUNCHER)
        if (carShell) UcarBind.apply(intent, carMode)
        check(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, intent)) {
            "Android не разрешил запуск приложения на собственном экране"
        }
        val pkgOrientations = if (ownActivity) emptyList() else EmbeddedDisplayPolicy.packageOrientations(pm, comp.packageName)
        val options = if (!ownActivity && EmbeddedDisplayPolicy.multiWindowLaunch(comp.packageName, info.screenOrientation, pkgOrientations)) {
            // Без границ запуска: в многооконном режиме система держит их буквально, и окно не следует за размером плитки.
            ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
                .apply { putInt(EmbeddedDisplayPolicy.KEY_LAUNCH_WINDOWING_MODE, EmbeddedDisplayPolicy.WINDOWING_MULTI_WINDOW) }
        } else fullscreenOptions(id).toBundle()
        context.startActivity(intent, options)
    }

    /**
     * h658: с включёнными «окнами произвольной формы» / режимом рабочего стола (Sony, Android 14) система кладёт
     * лаунчер на наш дисплей портретным окном размером с телефон. Явно просим полноэкранный режим (hidden API,
     * WINDOWING_MODE_FULLSCREEN = 1) и, на случай отказа, границы на весь дисплей.
     */
    private fun fullscreenOptions(id: Int): ActivityOptions {
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(id)
        val mode = runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, 1)
        }
        displays.getDisplay(id)?.let { d ->
            val size = android.graphics.Point().also { @Suppress("DEPRECATION") d.getRealSize(it) }
            if (size.x > 0 && size.y > 0) opts.setLaunchBounds(android.graphics.Rect(0, 0, size.x, size.y))
        }
        if (!fullscreenLogged) {
            fullscreenLogged = true
            android.util.Log.i("HeadSwap", "DESKTOP launch fullscreen windowingMode=${if (mode.isSuccess) "ok" else mode.exceptionOrNull()?.javaClass?.simpleName}")
        }
        return opts
    }
    @Volatile private var fullscreenLogged = false

    override fun childTaskState(id: Int, packageName: String): Int {
        check(id > 0) { "Проверка основного экрана запрещена" }
        val token = inputLeases[id] ?: return -1
        return inputGateway.childTaskState(id, token, packageName)
    }

    override fun childTaskPackages(id: Int): List<String>? {
        check(id > 0) { "Проверка основного экрана запрещена" }
        val token = inputLeases[id] ?: return null
        return inputGateway.childTaskPackages(id, token)?.toList()
    }

    override fun childFullscreen(id: Int, packageName: String): Int {
        check(id > 0) { "Проверка основного экрана запрещена" }
        val token = inputLeases[id] ?: return -1
        return inputGateway.childFullscreen(id, token, packageName)
    }
}
