package com.yegorkapomidorka.headswap.dex

import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.SparseArray
import android.view.InputDevice
import androidx.annotation.Keep
import java.io.File
import kotlin.system.exitProcess

/**
 * h1007 (Android 13–15): процесс uid 2000 (app_process, запускает лаунчер активации) с тачскрином /dev/uinput на
 * каждый дисплей ГУ. Пока экран телефона погашен, Android 14 выбрасывает программный ввод (injectInputEvent), а ввод
 * от настоящего устройства на включённый не основной дисплей пропускает (PhoneWindowManager
 * shouldDispatchInputWhenNonInteractive). /dev/uinput открыт только для uid 2000 (группа uhid), поэтому отдельный хост.
 *
 * Тачскрин делает класс Device системной команды uinput (/system/framework/uinput.jar в CLASSPATH): его JSON
 * разрешает только usb/bluetooth, а такие устройства Android считает внешними, и первое касание будит телефон.
 * Конструктор принимает bus числом - ставим BUS_VIRTUAL, устройство внутреннее и телефон не будит.
 */
@Keep
class UinputHostMain private constructor(private val args: VdmHostPolicy.Args) {
    private val user = Process.myUid() / 100000
    private val notes = ArrayList<String>()
    private var appUid = -1
    private val devices = HashMap<Int, Dev>()
    @Volatile private var lastError = ""

    private class Dev(val device: Any, val port: String, val name: String)

    @Synchronized private fun note(text: String) { if (notes.size < 40) notes += text; println("UINPUTHOST $text") }

    private fun run() {
        check(Process.myUid() == VdmDesktop.SHELL_UID) { "нужен uid 2000, а не ${Process.myUid()}" }
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
        note("sdk=${Build.VERSION.SDK_INT} uid=${Process.myUid()} pid=${Process.myPid()} uinput=${File(DEV_UINPUT).let { "${it.exists()}/${it.canWrite()}" }}")
        runCatching { deviceClass; note("Device: ${constructor.parameterTypes.size} параметров") }
            .onFailure { note("Device из uinput.jar недоступен: ${it.describe()}") }
        if (!deliver()) exitProcess(2)
        Looper.loop()
    }

    // --- тачскрин ---

    private val deviceClass by lazy { Class.forName("com.android.commands.uinput.Device") }
    private val absInfoClass by lazy { Class.forName("src.com.android.commands.uinput.InputAbsInfo") }
    /** A14: (id, name, vid, pid, bus, cfg, ffMax, abs, port); A15+: (id, name, vid, pid, version, bus, cfg, ffMax, abs, port). */
    private val constructor by lazy {
        deviceClass.constructors.firstOrNull { it.parameterTypes.size == 9 || it.parameterTypes.size == 10 }
            ?: error("нет известного конструктора Device: ${deviceClass.constructors.joinToString { it.parameterTypes.size.toString() }}")
    }
    private val injectMethod by lazy { deviceClass.getMethod("injectEvent", IntArray::class.java) }

    private fun absInfo(max: Int): Any = absInfoClass.getConstructor().newInstance().also { info ->
        absInfoClass.getField("minimum").setInt(info, 0)
        absInfoClass.getField("maximum").setInt(info, max)
        absInfoClass.getField("resolution").setInt(info, 0)
    }

    private fun openDevice(displayId: Int, width: Int, height: Int) {
        closeDevice(displayId)
        val unique = displayUniqueId(displayId)
        val port = "headswap-touch-$displayId"
        val name = "Headswap Touch $displayId"
        associate(port, unique) // до создания устройства: InputReader сразу видит его на дисплее ГУ
        val config = SparseArray<IntArray>().apply {
            put(UI_SET_EVBIT, intArrayOf(EV_SYN, EV_KEY, EV_ABS))
            put(UI_SET_KEYBIT, intArrayOf(BTN_TOUCH))
            put(UI_SET_ABSBIT, intArrayOf(ABS_X, ABS_Y, ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID))
            put(UI_SET_PROPBIT, intArrayOf(INPUT_PROP_DIRECT))
        }
        val abs = SparseArray<Any>().apply {
            put(ABS_X, absInfo(width - 1)); put(ABS_Y, absInfo(height - 1))
            put(ABS_MT_POSITION_X, absInfo(width - 1)); put(ABS_MT_POSITION_Y, absInfo(height - 1))
            put(ABS_MT_SLOT, absInfo(UinputTouchEvents.MAX_SLOTS - 1)); put(ABS_MT_TRACKING_ID, absInfo(65535))
        }
        val device = if (constructor.parameterTypes.size == 10)
            constructor.newInstance(displayId, name, VID, PID, 1, BUS_VIRTUAL, config, 0, abs, port)
        else constructor.newInstance(displayId, name, VID, PID, BUS_VIRTUAL, config, 0, abs, port)
        devices[displayId] = Dev(device, port, name)
        note("тачскрин дисплея $displayId ${width}x$height порт=$port дисплей=$unique")
    }

    private fun closeDevice(displayId: Int) {
        val dev = devices.remove(displayId) ?: return
        runCatching { deviceClass.getMethod("close").invoke(dev.device) }
        runCatching { unassociate(dev.port) }
        note("тачскрин дисплея $displayId закрыт")
    }

    private fun displayUniqueId(displayId: Int): String {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, displayId)
            ?: error("дисплей $displayId не найден")
        return checkNotNull(info.javaClass.getField("uniqueId").get(info) as String?) { "у дисплея $displayId нет uniqueId" }
    }

    private val inputManager by lazy {
        Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("input"))!!
    }

    private fun associate(port: String, unique: String) {
        val im = inputManager
        val method = listOf("addUniqueIdAssociation", "addUniqueIdAssociationByPort").firstNotNullOfOrNull { name ->
            runCatching { im.javaClass.getMethod(name, String::class.java, String::class.java) }.getOrNull()
        } ?: error("нет addUniqueIdAssociation")
        method.invoke(im, port, unique)
    }

    private fun unassociate(port: String) {
        val im = inputManager
        val method = listOf("removeUniqueIdAssociation", "removeUniqueIdAssociationByPort").firstNotNullOfOrNull { name ->
            runCatching { im.javaClass.getMethod(name, String::class.java) }.getOrNull()
        } ?: return
        method.invoke(im, port)
    }

    private fun describeDevice(displayId: Int): String {
        val dev = devices[displayId] ?: return ""
        val im = inputManager
        val ids = im.javaClass.getMethod("getInputDeviceIds").invoke(im) as IntArray
        val get = im.javaClass.getMethod("getInputDevice", Int::class.javaPrimitiveType)
        val found = ids.asSequence().mapNotNull { get.invoke(im, it) as InputDevice? }.firstOrNull { it.name == dev.name }
            ?: return "устройство ${dev.name} ещё не появилось в InputManager"
        fun hidden(name: String) = runCatching { InputDevice::class.java.getMethod(name).invoke(found) }.getOrElse { "?" }
        return "id=${found.id} источники=0x${Integer.toHexString(found.sources)} внешнее=${hidden("isExternal")} " +
            "включено=${hidden("isEnabled")} дисплей=${hidden("getAssociatedDisplayId")}"
    }

    private val binder = object : IUinputHost.Stub() {
        private fun requireApp() { if (getCallingUid() != appUid) throw SecurityException("Только HeadSwap") }

        override fun status(): String { requireApp(); return synchronized(this@UinputHostMain) { notes.joinToString("; ") } }
        override fun lastError(): String { requireApp(); return lastError }

        override fun open(displayId: Int, width: Int, height: Int): Boolean {
            requireApp()
            require(displayId > 0 && width in 2..8192 && height in 2..8192) { "Недопустимый дисплей" }
            return synchronized(this@UinputHostMain) {
                try { openDevice(displayId, width, height); true }
                catch (e: Throwable) { lastError = e.describe(); note("тачскрин дисплея $displayId не создан: $lastError"); false }
            }
        }

        override fun events(displayId: Int, triplets: IntArray) {
            if (getCallingUid() != appUid || triplets.size % 3 != 0 || triplets.size > 3 * 64) return
            val dev = synchronized(this@UinputHostMain) { devices[displayId] } ?: return
            runCatching { injectMethod.invoke(dev.device, triplets) }.onFailure { lastError = it.describe() }
        }

        override fun close(displayId: Int) {
            requireApp()
            synchronized(this@UinputHostMain) { closeDevice(displayId) }
        }

        override fun describe(displayId: Int): String {
            requireApp()
            return synchronized(this@UinputHostMain) { runCatching { describeDevice(displayId) }.getOrElse { it.describe() } }
        }
    }

    // --- передача binder приложению: как в VdmHostMain (getContentProviderExternal -> call -> remove) ---

    private fun deliver(): Boolean {
        if (!appAlive()) { note("процесс приложения ${args.appPid} уже завершён"); return false }
        return try {
            val reply = providerCall(UinputTouch.METHOD, Bundle().apply {
                putBinder(VdmDesktop.EXTRA_HOST, binder)
                putString(VdmDesktop.EXTRA_STATUS, notes.joinToString("; "))
            })
            val client = reply?.getBinder(VdmDesktop.EXTRA_CLIENT)
            if (reply?.getBoolean(VdmDesktop.EXTRA_ACCEPTED) != true || client == null) { note("приложение отказало"); return false }
            client.linkToDeath({
                note("приложение завершилось, выхожу")
                synchronized(this) { devices.keys.toList().forEach(::closeDevice) }
                exitProcess(0)
            }, 0)
            note("binder отдан приложению uid=$appUid")
            true
        } catch (e: Throwable) {
            note("передача не удалась: ${e.describe()}")
            false
        }
    }

    private fun providerCall(method: String, extras: Bundle): Bundle? {
        check(appAlive()) { "процесс приложения ${args.appPid} завершён" }
        val authority = args.packageName + ".vdmhost"
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("activity"))!!
        try {
            val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java).invoke(am, authority, user, null, "headswap-uinput")
                ?: error("провайдер $authority не найден")
            val info = holder.javaClass.getField("info").get(holder) as android.content.pm.ProviderInfo
            check(info.packageName == args.packageName) { "провайдер чужого пакета ${info.packageName}" }
            appUid = info.applicationInfo.uid
            val provider = checkNotNull(holder.javaClass.getField("provider").get(holder)) { "провайдер не ответил" }
            val call = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
            return call.invoke(provider, AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(),
                authority, method, null, extras) as Bundle?
        } finally {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                    Int::class.javaPrimitiveType).invoke(am, authority, null, user)
            }
        }
    }

    private fun appAlive(): Boolean = runCatching {
        File("/proc/${args.appPid}/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray()
            .toString(Charsets.UTF_8) == args.packageName
    }.getOrDefault(false)

    private fun service(name: String): IBinder = checkNotNull(Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, name) as IBinder?) { "нет сервиса $name" }

    private fun Throwable.describe(): String {
        val cause = (this as? java.lang.reflect.InvocationTargetException)?.targetException ?: this
        return "${cause.javaClass.simpleName}: ${cause.message}".take(300)
    }

    companion object {
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val DEV_UINPUT = "/dev/uinput"
        /** pid.codes: открытый vendor id, IDC-файла с таким именем в системе нет. */
        private const val VID = 0x1209
        private const val PID = 0x4853
        /** linux/input.h: BUS_VIRTUAL - не USB/Bluetooth, значит устройство внутреннее и касание не будит телефон. */
        private const val BUS_VIRTUAL = 0x06
        private const val UI_SET_EVBIT = 100
        private const val UI_SET_KEYBIT = 101
        private const val UI_SET_ABSBIT = 103
        private const val UI_SET_PROPBIT = 110
        private const val INPUT_PROP_DIRECT = 1
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val BTN_TOUCH = 0x14a
        const val ABS_X = 0
        const val ABS_Y = 1
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39

        @Keep @JvmStatic fun main(argv: Array<String>) {
            try { UinputHostMain(VdmHostPolicy.Args.parse(argv)).run() }
            catch (e: Throwable) { println("UINPUTHOST ошибка:${e.javaClass.simpleName}: ${e.message}"); exitProcess(1) }
        }
    }
}
