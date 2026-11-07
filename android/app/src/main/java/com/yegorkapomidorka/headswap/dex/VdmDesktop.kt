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
    /** Android 16 тоже: на Samsung с 16 тот же симптом (MAA-Meow), у остальных и старый путь работает. */
    const val MIN_SDK = 36
    const val METHOD = "vdm_host"
    const val EXTRA_HOST = "host"
    const val EXTRA_CLIENT = "client"
    const val EXTRA_STATUS = "status"
    const val EXTRA_ACCEPTED = "accepted"
    const val SHELL_UID = 2000
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP / FLAG_ALWAYS_UNLOCKED / FLAG_DEVICE_DISPLAY_GROUP (@hide). */
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

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
        AppLog.i("VDM хост завершился: новые экраны пойдут обычным путём", false)
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

    @Synchronized fun releaseAll() { for (id in owned.keys.toList()) release(id) }
}
