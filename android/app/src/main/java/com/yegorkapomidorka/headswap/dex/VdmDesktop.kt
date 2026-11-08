package com.yegorkapomidorka.headswap.dex

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.Surface
import com.yegorkapomidorka.headswap.AppLog

/**
 * Этап B (h782, h768, Android 17): дисплей рабочего стола внутри VirtualDevice. На Android 17 обычный виртуальный
 * дисплей попадает в группу 0 (флаг separate_timeouts вшит в сборку и перекрывает OWN_DISPLAY_GROUP) и засыпает
 * вместе с экраном телефона. Дисплей VirtualDevice получает свою группу. VirtualDevice создаёт только настоящий
 * uid 2000 от имени com.android.shell (привязка на наш пакет на 17 не создаётся, vdm1), поэтому его держит
 * отдельный shell-процесс [VdmHostMain], а сюда он отдаёт свой binder через [VdmHostProvider].
 */
internal object VdmDesktop {
    /** Только Android 17 (separate_timeouts); на 16 старый путь с OWN_DISPLAY_GROUP пока работает (Егор, 07.11). */
    const val MIN_SDK = 37
    const val METHOD = "vdm_host"
    const val METHOD_REPORT = "vdm_report"
    const val EXTRA_HOST = "host"
    const val EXTRA_CLIENT = "client"
    const val EXTRA_STATUS = "status"
    const val EXTRA_ACCEPTED = "accepted"
    const val SHELL_UID = 2000
    const val START_FROM_BACKGROUND = "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP / FLAG_ALWAYS_UNLOCKED / FLAG_DEVICE_DISPLAY_GROUP (@hide). */
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

    /**
     * Отладка по USB выключена: без неё Samsung при подключении ГУ кабелем останавливает adbd, а с ним shell-хост
     * (h768, h821, SM-S942B; с галочкой SM-S942B не гаснет). Активация и главный экран на 17 её требуют - только на Samsung:
     * Pixel 7 Pro (h966) с включённой галочкой получал «выключено», и активация не начиналась вовсе.
     */
    fun usbDebugMissing(context: android.content.Context): Boolean = supported &&
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 0

    /** Сырые значения для лога активации: на Pixel проверка ошибалась, видно ли там 0 или null (h966). */
    fun debugSettingsRaw(context: android.content.Context): String {
        val cr = context.contentResolver
        fun raw(key: String) = runCatching { android.provider.Settings.Global.getString(cr, key) }.getOrElse { "err:" + it.javaClass.simpleName }
        return "adb_enabled=${raw(android.provider.Settings.Global.ADB_ENABLED)} adb_wifi_enabled=${raw("adb_wifi_enabled")} " +
            "maker=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT}"
    }

    /** Флаги для дисплея VirtualDevice: своя группа устройства вместо OWN_DISPLAY_GROUP, блокировку снимает устройство. */
    fun hostFlags(flags: Int): Int = flags and (FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED).inv() or FLAG_DEVICE_DISPLAY_GROUP

    /** Группа дисплея из скрытого DisplayInfo (главный процесс под --no-hidden-api-checks); null - не прочитали. */
    fun groupId(display: Display): Int? = runCatching {
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getConstructor().newInstance()
        Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info)
        infoClass.getField("displayGroupId").getInt(info)
    }.getOrNull()
}

/**
 * Связь главного процесса с shell-хостом. Хост один на активацию: он умирает вместе с этим процессом
 * (держит [client]), а смерть хоста просто возвращает рабочий стол на обычный дисплей.
 */
internal object VdmHostLink {
    private val client = Binder()
    @Volatile private var host: IVdmHost? = null
    private val owned = HashMap<Int, Pair<IBinder, IBinder.DeathRecipient>>()

    val connected: Boolean get() = host?.asBinder()?.isBinderAlive == true

    /** Из [VdmHostProvider.call]; вызывающий уже проверен как uid 2000. */
    fun accept(context: android.content.Context, extras: Bundle?): Bundle {
        val binder = extras?.getBinder(VdmDesktop.EXTRA_HOST)
        val status = extras?.getString(VdmDesktop.EXTRA_STATUS) ?: "?"
        if (binder == null || !DelegatedDesktopAccess.isReady(context)) return refused(status)
        appContext = context.applicationContext
        val next = IVdmHost.Stub.asInterface(binder)
        synchronized(this) {
            host = next
            runCatching { binder.linkToDeath({ onHostDied(next) }, 0) }
        }
        AppLog.i("VDM хост подключён: $status", false)
        return Bundle().apply { putBoolean(VdmDesktop.EXTRA_ACCEPTED, true); putBinder(VdmDesktop.EXTRA_CLIENT, client) }
    }

    private fun refused(status: String): Bundle {
        AppLog.i("VDM хост отклонён (активация не готова): $status", false)
        return Bundle().apply { putBoolean(VdmDesktop.EXTRA_ACCEPTED, false) }
    }

    private fun onHostDied(dead: IVdmHost) {
        synchronized(this) { if (host === dead) { host = null; owned.clear() } }
        AppLog.i("VDM хост завершился; ${debugState()}", false)
        VdmHostRestart.onHostDied(appContext)
    }

    /**
     * Ждёт хост, пока идёт [VdmHostRestart] (до [timeoutMs]); вызывать вне мониторов: [accept] берёт монитор этого
     * объекта. true - хост на месте.
     */
    fun awaitHost(timeoutMs: Long): Boolean {
        if (connected || !VdmDesktop.supported) return connected
        if (!VdmHostRestart.pending) return false
        val started = android.os.SystemClock.elapsedRealtime()
        while (VdmHostRestart.pending && android.os.SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (connected) break
            Thread.sleep(100)
        }
        AppLog.i("VDM ждала хост ${android.os.SystemClock.elapsedRealtime() - started} мс: ${if (connected) "подключён" else "нет"}", false)
        return connected
    }

    @Volatile private var appContext: android.content.Context? = null

    /** Отчёт хоста перед смертью (vdm3, h768): сигнал, падение, смена adbd. */
    fun report(extras: Bundle?) {
        AppLog.i("VDMHOST| отчёт: ${extras?.getString(VdmDesktop.EXTRA_STATUS)?.take(300)}; ${debugState()}", false)
    }

    /** Отладка по USB / беспроводная и экран: хост - потомок adbd, его судьба может зависеть от отладки. */
    private fun debugState(): String {
        val ctx = appContext ?: return "состояние отладки неизвестно"
        fun global(name: String) = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, name) }.getOrNull()
        val interactive = runCatching { ctx.getSystemService(android.os.PowerManager::class.java).isInteractive }.getOrNull()
        return "adb_enabled=${global("adb_enabled")} adb_wifi_enabled=${global("adb_wifi_enabled")} экран=${if (interactive == true) "вкл" else "выкл"}"
    }

    /** id дисплея VirtualDevice или -1 (хоста нет / отказ - причина в логе, рабочий стол идёт обычным путём). */
    @Synchronized fun create(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int, owner: IBinder): Int {
        if (!VdmDesktop.supported) return -1
        val h = host?.takeIf { it.asBinder().isBinderAlive } ?: run {
            AppLog.i("VDM хост не подключён: обычный дисплей (группа 0 на Android 17)", false); return -1
        }
        return try {
            val id = h.createDisplay(surface, name, width, height, density, VdmDesktop.hostFlags(flags))
            if (id <= 0) {
                AppLog.i("VDM дисплей не создан: ${runCatching { h.lastError() }.getOrNull()}, обычный путь", false); return -1
            }
            val death = IBinder.DeathRecipient { release(id) }
            owner.linkToDeath(death, 0)
            owned[id] = owner to death
            AppLog.i("VDM дисплей $id создан хостом: группа=${runCatching { h.displayGroup(id) }.getOrDefault(-1)}", false)
            id
        } catch (e: Exception) {
            AppLog.i("VDM дисплей не создан: ${e.javaClass.simpleName}: ${e.message}, обычный путь", false)
            -1
        }
    }

    @Synchronized fun resize(id: Int, width: Int, height: Int, density: Int) {
        check(owned.containsKey(id)) { "Экран уже закрыт" }
        checkNotNull(host) { "Хост виртуального устройства завершился" }.resizeDisplay(id, width, height, density)
    }

    @Synchronized fun release(id: Int) {
        val (owner, death) = owned.remove(id) ?: return
        runCatching { owner.unlinkToDeath(death, 0) }
        runCatching { host?.releaseDisplay(id) }
        AppLog.i("VDM дисплей $id закрыт", false)
    }

    /** Журнал хоста в лог приложения; хоста нет - так и пишем (не запустился или умер). */
    fun logStatus() {
        val h = host?.takeIf { it.asBinder().isBinderAlive }
        if (h == null) { AppLog.i("VDMHOST| не подключён: хост не запустился или завершился", false); return }
        val text = runCatching { h.status() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        for (line in text.split("; ").take(40)) AppLog.i("VDMHOST| ${line.take(300)}", false)
    }

    @Synchronized fun releaseAll() { for (id in owned.keys.toList()) release(id) }
}
