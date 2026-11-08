// Association via apply-restored-payload and the com.android.shell VirtualDevice context follow MoonClicker
// (github.com/kuomartin/MoonClicker, MIT, Copyright (c) 2026 Martin Kuo); binder hand-off follows Shizuku.
package com.yegorkapomidorka.headswap.dex

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface
import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executor
import kotlin.system.exitProcess

/**
 * Этап B: процесс uid 2000 (app_process, запускает лаунчер активации), который держит VirtualDevice от имени
 * com.android.shell. Дисплей VirtualDevice на Android 17 получает свою группу и не засыпает с экраном телефона.
 * Binder отдаёт главному процессу через [VdmHostProvider] один раз и живёт, пока жив ответный binder приложения.
 * Скрытые API здесь доступны: app_process не из zygote, ограничения не включены.
 */
@Keep
class VdmHostMain private constructor(private val args: VdmHostPolicy.Args) {
    private val user = Process.myUid() / 100000
    private val notes = ArrayList<String>()
    private lateinit var context: Context
    private var associationId = -1
    private var appUid = -1
    private var device: Any? = null
    private val displays = HashMap<Int, VirtualDisplay>()
    @Volatile private var lastError = ""

    @Synchronized private fun note(text: String) { if (notes.size < 60) notes += text; println("VDMHOST $text") }

    private fun run() {
        check(Process.myUid() == VdmDesktop.SHELL_UID) { "нужен uid 2000, а не ${Process.myUid()}" }
        if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
        note("sdk=${Build.VERSION.SDK_INT} uid=${Process.myUid()} pid=${Process.myPid()}")
        context = runCatching { ShellContext(systemContext()) }
            .onFailure { note("контекст: ${it.describe()}") }.getOrThrow()
        runCatching { associationId = ensureAssociation() }.onFailure { note("привязка: ${it.describe()}") }
        note("привязка id=${if (associationId > 0) associationId else "нет"}")
        runCatching { note("ppid=${ppidOf(Process.myPid())} adbd=${adbdPid() ?: "нет"} cgroup=${File("/proc/self/cgroup").readText().trim().replace('\n', '|').take(200)}") }
        if (!deliver()) exitProcess(2)
        reportTcpPort()
        watchForDeath()
        Looper.loop()
    }

    // --- контекст: системный контекст ActivityThread (как UserService Shizuku), снаружи - com.android.shell ---

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    // --- CDM-привязка на com.android.shell (на 35+ через restored-payload, как MoonClicker) ---

    private fun cdm(): Any {
        val stub = Class.forName("android.companion.ICompanionDeviceManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, service("companiondevice"))!!
    }

    private fun existingAssociation(): Int? {
        val cdm = cdm()
        @Suppress("UNCHECKED_CAST")
        val list = runCatching { cdm.javaClass.getMethod("getAllAssociationsForUser", Int::class.javaPrimitiveType).invoke(cdm, user) }
            .recoverCatching { cdm.javaClass.getMethod("getAssociations", String::class.java, Int::class.javaPrimitiveType).invoke(cdm, SHELL_PACKAGE, user) }
            .getOrThrow() as List<android.companion.AssociationInfo>
        val pkgOf = android.companion.AssociationInfo::class.java.getMethod("getPackageName")
        return list.firstOrNull { pkgOf.invoke(it) == SHELL_PACKAGE && it.deviceMacAddress?.toString().equals(MAC, true) }?.id
    }

    private fun ensureAssociation(): Int {
        existingAssociation()?.let { note("привязка уже есть"); return it }
        val tries = ArrayList<Pair<String, Array<String>>>()
        if (Build.VERSION.SDK_INT >= 35) {
            tries += "restored-payload" to arrayOf("apply-restored-payload", user.toString(), Base64.getEncoder().encodeToString(restorePayload()))
        }
        tries += "associate" to arrayOf("associate", user.toString(), SHELL_PACKAGE, MAC, APP_STREAMING)
        for ((name, cmd) in tries) {
            val out = runCmd(*cmd)
            repeat(20) {
                existingAssociation()?.let { note("привязка $name: создана"); return it }
                Thread.sleep(50)
            }
            note("привязка $name: нет (${out.take(160)})")
        }
        error("CDM не создал привязку")
    }

    private fun restorePayload(): ByteArray {
        val associations = ("<state persistence-version=\"1\"><associations max-id=\"1\">" +
            "<association id=\"1\" profile=\"$APP_STREAMING\" package=\"$SHELL_PACKAGE\" mac_address=\"$MAC\" " +
            "display_name=\"HeadSwap\" self_managed=\"false\" notify_device_nearby=\"false\" /></associations></state>").toByteArray()
        val requests = "<requests />".toByteArray()
        return ByteBuffer.allocate(12 + associations.size + requests.size)
            .putInt(0).putInt(associations.size).put(associations).putInt(requests.size).put(requests).array()
    }

    private fun runCmd(vararg args: String): String = runCatching {
        val p = ProcessBuilder("cmd", "companiondevice", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        "exit=${p.exitValue()} ${out.trim().replace(Regex("\\s+"), " ")}"
    }.getOrElse { it.describe() }

    // --- VirtualDevice и дисплеи ---

    private fun newDevice(): Any {
        check(associationId > 0) { "нет CDM-привязки" }
        val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, 1) // LOCK_STATE_ALWAYS_UNLOCKED
        val params = builderClass.getMethod("build").invoke(builder)
        val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val iface = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val service = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service("virtualdevice"))
        val vdm = vdmClass.getConstructor(iface, Context::class.java).newInstance(service, context)
        val dev = vdmClass.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, params.javaClass).invoke(vdm, associationId, params)
        note("устройство создано")
        return checkNotNull(dev)
    }

    private fun closeDevice() {
        val dev = device ?: return
        device = null
        runCatching { dev.javaClass.getMethod("close").invoke(dev) }
        note("устройство закрыто")
    }

    private fun groupOf(display: Display): Int = runCatching {
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getConstructor().newInstance()
        Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info)
        infoClass.getField("displayGroupId").getInt(info)
    }.getOrDefault(-1)

    private val binder = object : IVdmHost.Stub() {
        private fun requireApp() { if (getCallingUid() != appUid) throw SecurityException("Только HeadSwap") }

        override fun status(): String { requireApp(); return synchronized(this@VdmHostMain) { notes.joinToString("; ") } }

        override fun lastError(): String { requireApp(); return lastError }

        override fun createDisplay(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int): Int {
            requireApp()
            synchronized(this@VdmHostMain) {
                return try {
                    val dev = device ?: newDevice().also { device = it }
                    val config = VirtualDisplayConfig.Builder(name, width, height, density).setSurface(surface).setFlags(flags).build()
                    val display = dev.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java,
                        Executor::class.java, VirtualDisplay.Callback::class.java).invoke(dev, config, null, null) as VirtualDisplay?
                    checkNotNull(display) { "VirtualDevice вернул null" }
                    val id = display.display.displayId
                    displays[id] = display
                    note("дисплей $id группа=${groupOf(display.display)} flags=0x${Integer.toHexString(display.display.flags)}")
                    id
                } catch (e: Throwable) {
                    lastError = e.describe()
                    note("дисплей не создан: $lastError")
                    if (displays.isEmpty()) closeDevice()
                    -1
                }
            }
        }

        override fun resizeDisplay(displayId: Int, width: Int, height: Int, density: Int) {
            requireApp()
            synchronized(this@VdmHostMain) { checkNotNull(displays[displayId]) { "Экран уже закрыт" }.resize(width, height, density) }
        }

        override fun releaseDisplay(displayId: Int) {
            requireApp()
            synchronized(this@VdmHostMain) {
                displays.remove(displayId)?.let { runCatching { it.release() } }
                if (displays.isEmpty()) closeDevice()
            }
        }

        override fun displayGroup(displayId: Int): Int {
            requireApp()
            return synchronized(this@VdmHostMain) { displays[displayId]?.let { groupOf(it.display) } ?: -1 }
        }
    }

    // --- передача binder приложению: getContentProviderExternal -> call -> remove (как Shizuku) ---

    private fun deliver(): Boolean {
        if (!appAlive()) { note("процесс приложения ${args.appPid} уже завершён"); return false }
        return try {
            val reply = providerCall(VdmDesktop.METHOD, Bundle().apply {
                putBinder(VdmDesktop.EXTRA_HOST, binder)
                putString(VdmDesktop.EXTRA_STATUS, notes.joinToString("; "))
            })
            val client = reply?.getBinder(VdmDesktop.EXTRA_CLIENT)
            if (reply?.getBoolean(VdmDesktop.EXTRA_ACCEPTED) != true || client == null) { note("приложение отказало"); return false }
            client.linkToDeath({
                note("приложение завершилось, выхожу")
                synchronized(this) { displays.values.forEach { runCatching { it.release() } }; displays.clear(); closeDevice() }
                exitProcess(0)
            }, 0)
            note("binder отдан приложению uid=$appUid")
            true
        } catch (e: Throwable) {
            note("передача не удалась: ${e.describe()}")
            false
        }
    }

    /** Вызов нашего провайдера только у живого проверенного процесса (иначе AMS поднял бы приложение без активации). */
    private fun providerCall(method: String, extras: Bundle): Bundle? {
        check(appAlive()) { "процесс приложения ${args.appPid} завершён" }
        val authority = args.packageName + ".vdmhost"
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("activity"))!!
        try {
            val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java).invoke(am, authority, user, null, "headswap-vdm")
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

    // --- vdm3: почему хост умирает на Samsung (h768) - отчёт в лог приложения до смерти, где это возможно ---

    private fun report(text: String) {
        println("VDMHOST отчёт: $text")
        runCatching { providerCall(VdmDesktop.METHOD_REPORT, Bundle().apply { putString(VdmDesktop.EXTRA_STATUS, text) }) }
    }

    private fun watchForDeath() {
        Thread.setDefaultUncaughtExceptionHandler { t, e -> report("падение в ${t.name}: ${e.describe()}"); exitProcess(3) }
        // Хук только на exit: от сигналов (init убивает cgroup adbd) отчёта не будет, их ловит слежка за adbd ниже.
        Runtime.getRuntime().addShutdownHook(Thread { report("выход по сигналу/exit, adbd=${adbdPid() ?: "нет"}") })
        val adbd = adbdPid()
        Thread({
            var last = adbd
            while (true) {
                Thread.sleep(500)
                val now = adbdPid()
                if (now != last) { report("adbd: было ${last ?: "нет"}, стало ${now ?: "нет"}"); last = now }
            }
        }, "vdm-watch").apply { isDaemon = true }.start()
    }

    /**
     * h768: TCP-порт adbd ставит сам adbd по просьбе приложения ([VdmHostRestart] шлёт tcpip:5555);
     * setprop из-под shell на Samsung A17 запрещён. Здесь только отчёт о состоянии в лог приложения.
     */
    private fun reportTcpPort() {
        val port = getprop("service.adb.tcp.port").ifEmpty { "нет" }
        note("tcp.port=$port")
        report("tcp.port=$port")
    }

    private fun getprop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("").trim()

    private fun adbdPid(): Int? = File("/proc").listFiles()?.firstNotNullOfOrNull { dir ->
        dir.name.toIntOrNull()?.takeIf { runCatching { File(dir, "comm").readText().trim() == "adbd" }.getOrDefault(false) }
    }

    private fun ppidOf(pid: Int): Int? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')[1].toInt()
    }.getOrNull()

    /** Провайдер ищем только у живого проверенного процесса: иначе AMS поднял бы приложение без активации. */
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
        private const val APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
        private const val MAC = "02:48:53:57:41:50"

        @Keep @JvmStatic fun main(argv: Array<String>) {
            try { VdmHostMain(VdmHostPolicy.Args.parse(argv)).run() }
            catch (e: Throwable) { println("VDMHOST ошибка:${e.javaClass.simpleName}: ${e.message}"); exitProcess(1) }
        }
    }
}
