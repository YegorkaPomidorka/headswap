package com.yegorkapomidorka.headswap.dex

import android.app.UiAutomation
import android.companion.CompanionDeviceManager
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.Surface
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.Executor

/**
 * Прототип vdm1 (h782, Android 17): дисплей рабочего стола внутри VirtualDevice. На Android 17 обычный виртуальный
 * дисплей попадает в группу 0 (флаг separate_timeouts вшит в сборку и перекрывает OWN_DISPLAY_GROUP) и засыпает
 * вместе с экраном телефона. Дисплей VirtualDevice получает группу устройства. Нужна CDM-привязка на наш пакет:
 * роль APP_STREAMING только для системных, поэтому пробуем профиль VIRTUAL_DEVICE в обход проверки роли.
 * Всё лучшим усилием: не вышло - строка VDM в логе и прежний путь.
 */
internal object VdmDesktop {
    const val CREATE_VIRTUAL_DEVICE = "android.permission.CREATE_VIRTUAL_DEVICE"
    /** Android 16 тоже: на нём проверяем механику на своём телефоне, у юзеров там и старый путь работает. */
    const val MIN_SDK = 36
    private const val MAC = "02:48:53:57:41:50"
    private val PROFILES = listOf("android.app.role.COMPANION_DEVICE_VIRTUAL_DEVICE",
        "android.app.role.COMPANION_DEVICE_APP_STREAMING")
    private val FLAG_LINES = Regex("""(?i)enable_limited_vdm_role|cmd_options|separate_timeouts|virtual_secondary_displays""")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

    /** Наша привязка, если есть (публичный API, только привязки вызывающего пакета). */
    fun associationId(context: Context): Int? = runCatching {
        context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations
            ?.firstOrNull { it.deviceMacAddress?.toString().equals(MAC, ignoreCase = true) }?.id
    }.getOrNull()

    /**
     * В момент активации, пока shell жив: снимок флагов и ролей в лог и, если привязки нет, попытка создать её.
     * executeShellCommand идёт через Runtime.exec в shell без sh: ни пайпов, ни кавычек.
     */
    fun prepare(context: Context, shell: UiAutomation) {
        if (!supported) return
        val user = context.applicationInfo.uid / 100000
        val pkg = context.packageName
        runCatching {
            val flags = run(shell, "aflags list").lines().filter { FLAG_LINES.containsMatchIn(it) }
            if (flags.isEmpty()) AppLog.i("VDM флаги: aflags ничего не отдал", false)
            for (line in flags.take(12)) AppLog.i("VDM флаг| ${line.trim().take(200)}", false)
            for (profile in PROFILES) AppLog.i("VDM роль ${profile.substringAfterLast('.')}: " +
                run(shell, "cmd role get-role-holders --user $user $profile").oneLine(), false)
        }.onFailure { AppLog.i("VDM снимок не снят: ${it.javaClass.simpleName}: ${it.message}", false) }
        associationId(context)?.let { AppLog.i("VDM привязка уже есть: id=$it", false); return }
        for (profile in PROFILES) {
            for (command in listOf("cmd companiondevice associate $user $pkg $MAC $profile false",
                "cmd companiondevice associate --user $user --package $pkg --mac-address $MAC --profile $profile")) {
                val out = runCatching {
                    run(shell, "cmd role set-bypassing-role-qualification true")
                    run(shell, command).also { run(shell, "cmd role set-bypassing-role-qualification false") }
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                val id = associationId(context)
                AppLog.i("VDM associate ${profile.substringAfterLast('.')} ${if (command.contains("--")) "опции" else "позиционно"}: " +
                    "id=${id ?: "нет"} вывод=${out.oneLine()}", false)
                if (id != null) return
            }
        }
        AppLog.i("VDM привязка не создана, список: ${runCatching { run(shell, "cmd companiondevice list $user") }.getOrDefault("").oneLine()}", false)
    }

    private fun run(shell: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun String.oneLine() = trim().replace(Regex("""\s+"""), " ").take(300).ifEmpty { "(пусто)" }

    /** Группа дисплея из скрытого DisplayInfo (главный процесс под --no-hidden-api-checks); null - не прочитали. */
    fun groupId(display: Display): Int? = runCatching {
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getConstructor().newInstance()
        Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info)
        infoClass.getField("displayGroupId").getInt(info)
    }.getOrNull()
}

/**
 * Виртуальное устройство и его дисплеи; живёт в главном (инструментированном) процессе, где разрешены скрытые API
 * и есть принятые права shell. Устройство закрывается вместе с сеансом и при смерти владельца дисплея.
 */
internal class VdmDisplays(private val context: Context) {
    private var device: Any? = null
    private val owned = HashMap<Int, Pair<VirtualDisplay, IBinder.DeathRecipient>>()

    @Synchronized fun create(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int, owner: IBinder): Int {
        if (!VdmDesktop.supported) return -1
        return try {
            val association = VdmDesktop.associationId(context) ?: run {
                AppLog.i("VDM дисплей: привязки нет, обычный путь", false); return -1
            }
            val dev = device ?: newDevice(association).also { device = it }
            val config = VirtualDisplayConfig.Builder(name, width, height, density).setSurface(surface).setFlags(flags).build()
            val display = dev.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java,
                Executor::class.java, VirtualDisplay.Callback::class.java).invoke(dev, config, null, null) as VirtualDisplay
            val id = display.display.displayId
            val death = IBinder.DeathRecipient { release(id) }
            owner.linkToDeath(death, 0)
            owned[id] = display to death
            AppLog.i("VDM дисплей $id создан: группа=${VdmDesktop.groupId(display.display)} " +
                "flags=0x${Integer.toHexString(display.display.flags)} state=${display.display.state}", false)
            id
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            AppLog.i("VDM дисплей не создан: ${cause.javaClass.simpleName}: ${cause.message}, обычный путь", false)
            if (owned.isEmpty()) closeDevice()
            -1
        }
    }

    @Synchronized fun resize(id: Int, width: Int, height: Int, density: Int) {
        checkNotNull(owned[id]) { "Экран уже закрыт" }.first.resize(width, height, density)
    }

    @Synchronized fun release(id: Int) {
        val (display, _) = owned.remove(id) ?: return
        runCatching { display.release() }
        AppLog.i("VDM дисплей $id закрыт", false)
        if (owned.isEmpty()) closeDevice()
    }

    @Synchronized fun releaseAll() { for (id in owned.keys.toList()) release(id); closeDevice() }

    private fun newDevice(association: Int): Any {
        val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, 1) // LOCK_STATE_ALWAYS_UNLOCKED
        val params = builderClass.getMethod("build").invoke(builder)
        val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val vdm = checkNotNull(context.getSystemService(vdmClass)) { "VirtualDeviceManager недоступен" }
        val dev = vdmClass.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, params.javaClass).invoke(vdm, association, params)
        AppLog.i("VDM устройство создано по привязке $association", false)
        return checkNotNull(dev)
    }

    private fun closeDevice() {
        val dev = device ?: return
        device = null
        runCatching { dev.javaClass.getMethod("close").invoke(dev) }
        AppLog.i("VDM устройство закрыто", false)
    }
}
