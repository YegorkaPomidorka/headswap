package com.yegorkapomidorka.headswap.dex

import android.companion.CompanionDeviceManager
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor

/**
 * Дисплеи рабочего стола внутри VirtualDevice (ADR 0029). Работает в процессе :delegated, где создаются дисплеи.
 * VirtualDeviceManager.createVirtualDevice / VirtualDevice.createVirtualDisplay - @SystemApi, поэтому через reflection.
 * Привязку CDM делает активация ([VirtualDeviceAssociation.ensure]), пока жив shell. Любой отказ - null, и вызывающий
 * создаёт дисплей прежним путём; причина пишется в [VirtualDeviceStatus], главный процесс переносит её в лог приложения.
 */
@RequiresApi(34)
internal class DesktopVirtualDevice(private val context: Context) {
    private var device: Any? = null
    private val displays = HashSet<VirtualDisplay>()
    private var failedOnce = false

    /** Только worker DexUserService. */
    fun createDisplay(name: String, width: Int, height: Int, density: Int, surface: Surface, flags: Int,
        alwaysUnlocked: Boolean): VirtualDisplay? {
        if (!VirtualDeviceGate.enabled()) return null
        if (failedOnce && device == null) return null // не долбим систему на каждом дочернем экране
        val vd = device ?: openDevice(alwaysUnlocked)?.also { device = it } ?: run { failedOnce = true; return null }
        val tries = listOf(VirtualDevicePolicy.deviceDisplayFlags(flags), VirtualDevicePolicy.deviceDisplayFallbackFlags(flags)).distinct()
        var last: Throwable? = null
        for (f in tries) {
            val display = try { createIn(vd, name, width, height, density, surface, f) }
            catch (e: Throwable) { last = unwrap(e); report("дисплей flags=0x${Integer.toHexString(f)} отклонён: ${describe(last)}"); continue }
                ?: continue
            val group = DisplayGroups.groupId(display.display)
            if (group != null && !VirtualDevicePolicy.ownGroup(group)) {
                // Не лучше прежнего пути: пусть дисплей создаётся как раньше.
                report("дисплей ${display.display.displayId} внутри устройства, но group=$group; откат на обычный дисплей")
                runCatching { display.release() }
                closeIfIdle()
                return null
            }
            displays.add(display)
            report("ok display=${display.display.displayId} group=${group ?: "?"} flags=0x${Integer.toHexString(f)} unlocked=$alwaysUnlocked")
            return display
        }
        report("дисплей не создан (${describe(last)}); откат на обычный дисплей")
        closeIfIdle()
        return null
    }

    /** Дисплей освобождён вызывающим; когда своих дисплеев не осталось, закрываем и устройство. */
    fun released(display: VirtualDisplay?) {
        if (display == null || !displays.remove(display)) return
        closeIfIdle()
    }

    fun owns(display: VirtualDisplay?): Boolean = display != null && display in displays

    private fun closeIfIdle() {
        if (displays.isNotEmpty()) return
        val vd = device ?: return
        device = null
        runCatching { vd.javaClass.getMethod("close").invoke(vd) }
            .onFailure { Log.w(TAG, "VirtualDevice close", it) }
    }

    private fun openDevice(alwaysUnlocked: Boolean): Any? {
        val mac = VirtualDevicePolicy.fakeMac(context.packageName, Process.myUid() / 100000)
        val association = VirtualDeviceAssociation.find(context, mac)
        if (association == null) {
            report("нет привязки $mac (активация не сделала cmd companiondevice associate); откат на обычный дисплей")
            return null
        }
        return try {
            val vdm = checkNotNull(context.getSystemService(Context.VIRTUAL_DEVICE_SERVICE)) { "нет VirtualDeviceManager" }
            val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            if (alwaysUnlocked) builderClass.getMethod("setLockState", Int::class.javaPrimitiveType)
                .invoke(builder, VirtualDevicePolicy.LOCK_STATE_ALWAYS_UNLOCKED)
            runCatching { builderClass.getMethod("setName", String::class.java).invoke(builder, "HeadSwap") }
            // У устройства свой буфер обмена (так по умолчанию, проверено MoonClicker); CUSTOM - общий с телефоном.
            val clipboard = runCatching {
                val type = paramsClass.getField("POLICY_TYPE_CLIPBOARD").getInt(null)
                val custom = paramsClass.getField("DEVICE_POLICY_CUSTOM").getInt(null)
                builderClass.getMethod("setDevicePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(builder, type, custom)
            }.isSuccess
            val params = builderClass.getMethod("build").invoke(builder)
            val device = vdm.javaClass.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsClass)
                .invoke(vdm, association.id, params)
            report("устройство создано association=${association.id} unlocked=$alwaysUnlocked clipboardShared=$clipboard")
            device
        } catch (e: Throwable) {
            report("createVirtualDevice отклонён: ${describe(unwrap(e))}; откат на обычный дисплей")
            null
        }
    }

    private fun createIn(vd: Any, name: String, width: Int, height: Int, density: Int, surface: Surface, flags: Int): VirtualDisplay? {
        val config = VirtualDisplayConfig.Builder(name, width, height, density).setSurface(surface).setFlags(flags).build()
        return vd.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java, Executor::class.java,
            VirtualDisplay.Callback::class.java).invoke(vd, config, null, null) as VirtualDisplay?
    }

    private fun report(text: String) {
        Log.i(TAG, "VDEV $text")
        VirtualDeviceStatus.write(context, text)
    }

    companion object {
        private const val TAG = "HeadSwap"
        private fun unwrap(e: Throwable): Throwable = if (e is InvocationTargetException) e.targetException ?: e else e
        private fun describe(e: Throwable?): String = e?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "нет ответа"
    }
}

/** Включён ли путь VirtualDevice: Android 17+ или `setprop debug.headswap.vdev 1` (для проверки на 14-16). */
internal object VirtualDeviceGate {
    @Volatile private var cached: Boolean? = null

    fun enabled(): Boolean = cached ?: VirtualDevicePolicy.enabled(Build.VERSION.SDK_INT, readOverride()).also { cached = it }

    private fun readOverride(): String? = runCatching {
        val p = ProcessBuilder("getprop", VirtualDevicePolicy.OVERRIDE_PROPERTY).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.trim().also { p.waitFor() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Привязка CDM нашего пакета с фиктивным MAC. Создаётся из shell при активации, ищется без особых прав. */
@RequiresApi(34)
internal object VirtualDeviceAssociation {
    class Found(val id: Int, val profile: String?)

    fun find(context: Context, mac: String): Found? = runCatching {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }
            ?.let { Found(it.id, it.deviceProfile) }
    }.getOrNull()

    /**
     * Из активации: shell жив, `cmd companiondevice` доступна. Повторная активация находит прежнюю привязку
     * (MAC постоянный). Удалять её нельзя: удаление сразу закрывает VirtualDevice и дисплей. Возвращает строку для лога.
     */
    fun ensure(context: Context, shell: (String) -> String): String {
        val sdk = Build.VERSION.SDK_INT
        val user = Process.myUid() / 100000
        val mac = VirtualDevicePolicy.fakeMac(context.packageName, user)
        val existing = find(context, mac)
        if (existing != null && VirtualDevicePolicy.associationUsable(existing.profile, sdk)) return "привязка есть id=${existing.id}"
        if (existing != null) shell(VirtualDevicePolicy.disassociateCommand(user, context.packageName, mac))
        val out = shell(VirtualDevicePolicy.associateCommand(user, context.packageName, mac, sdk)).trim()
        repeat(50) {
            find(context, mac)?.let { return "привязка создана id=${it.id} profile=${it.profile}" }
            Thread.sleep(100)
        }
        return "привязка не появилась (${out.take(200).ifEmpty { "cmd молчит" }})"
    }
}

/** Последняя строка о VirtualDevice из :delegated; главный процесс пишет её в лог приложения (ADR 0016). */
internal object VirtualDeviceStatus {
    private fun file(context: Context) = File(context.filesDir, "vdev-status.txt")

    fun write(context: Context, text: String) {
        runCatching { file(context).appendText(text.replace('\n', ' ') + "\n") }
    }

    /** Забирает накопленные строки и очищает файл. */
    fun drain(context: Context): List<String> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        f.delete()
        lines
    }.getOrDefault(emptyList())
}
