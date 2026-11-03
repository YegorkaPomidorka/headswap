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

    // TRUSTED / DESTROY_CONTENT / SUPPORTS_TOUCH are permission-gated platform flags omitted by the public SDK IntDef.
    @SuppressLint("WrongConstant")
    override fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int): VirtualDisplay {
        DelegatedDesktopPolicy.requireMode(mode)
        return checkNotNull(displays.createVirtualDisplay("HeadSwap ${DesktopModePolicy.label(mode)}",
            width, height, density, surface,
            DesktopModePolicy.displayFlags(mode, Build.VERSION.SDK_INT, DelegatedDesktopAccess.canKeepUnlocked(context)))) {
            "Android отклонил доверенный виртуальный экран"
        }
    }

    // TRUSTED / DESTROY_CONTENT / SUPPORTS_TOUCH are permission-gated platform flags
    // omitted by the public SDK IntDef. The shared engine verifies the returned display.
    @SuppressLint("WrongConstant")
    override fun createChildDisplay(surface: Surface, width: Int, height: Int, density: Int): VirtualDisplay =
        checkNotNull(displays.createVirtualDisplay("HeadSwap navigator widget", width, height, density,
            surface, EmbeddedDisplayPolicy.displayFlags(Build.VERSION.SDK_INT, DelegatedDesktopAccess.canKeepUnlocked(context)))) {
            "Android отклонил экран виджета"
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
    }

    override fun unregisterDisplay(id: Int, token: IBinder) {
        if (inputLeases[id] != token) return
        inputLeases.remove(id)
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
        launch(id, ComponentName(packageName, fullClassName), ownActivity = false, requireLauncher = true)
    }

    private fun launch(id: Int, component: ComponentName, ownActivity: Boolean, requireLauncher: Boolean) {
        require(id > 0) { "Запуск на основном экране запрещён" }
        val pm = context.packageManager
        val info = pm.getActivityInfo(component, 0)
        check(info.enabled && info.applicationInfo.enabled && (info.exported || ownActivity &&
            info.applicationInfo.uid == context.applicationInfo.uid && component.packageName == context.packageName)) {
            "Приложение рабочего стола отключено или не разрешает внешний запуск"
        }
        if (requireLauncher) {
            val candidates = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(component.packageName), 0)
            check(candidates.any { it.activityInfo?.let { candidate ->
                candidate.packageName == component.packageName && candidate.name == component.className &&
                    candidate.exported && candidate.enabled && candidate.applicationInfo.enabled
            } == true }) { "Виджет принимает только доступное приложение из списка запуска" }
        }
        val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (requireLauncher) intent.addCategory(Intent.CATEGORY_LAUNCHER)
        check(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, intent)) {
            "Android не разрешил запуск приложения на собственном экране"
        }
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
    }

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
}
