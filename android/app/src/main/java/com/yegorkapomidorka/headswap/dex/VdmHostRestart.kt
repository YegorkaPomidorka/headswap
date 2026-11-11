package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * vdm4 (h768, h821, Samsung): shell-хост живёт в cgroup adbd, а Samsung перезапускает adbd при смене USB-режима
 * (подключение ГУ) и иногда при гашении экрана. Init убивает всю группу сигналом KILL, выйти из неё shell не может
 * (cgroup.procs принадлежат system, проверено на AVD A17). Поэтому хост поднимаем заново через свою беспроводную
 * отладку ([PhoneAdb]), если активация была с телефона и отладка по Wi-Fi включена.
 */
internal object VdmHostRestart {
    /** Сколько [VdmHostLink.awaitHost] держит создание экрана, пока хост поднимается. */
    const val WAIT_MS = 12_000L
    private const val PENDING_MS = 30_000L
    private const val DISCOVERY_WAIT_MS = 12_000L
    private const val CONNECT_WAIT_MS = 10_000L
    private const val WINDOW_MS = 10 * 60_000L
    /** Считаем только неудачные перезапуски: удачный бывает на каждое переподключение USB (adbd перезапускается). */
    private const val MAX_FAILED_IN_WINDOW = 3
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)
    /**
     * h768 (A55 без Wi-Fi в машине): порт беспроводной отладки после перезапуска adbd новый, а mDNS без Wi-Fi пуст.
     * Пока Wi-Fi есть, [pinViaAdbd] просит adbd tcpip:5555 (как `adb tcpip`); adbd ставит service.adb.tcp.port и
     * дальше слушает этот порт и на loopback - к нему ходим без Wi-Fi. Свойство живёт до перезагрузки телефона.
     */
    const val FIXED_TCP_PORT = 5555
    /** Закреплённый порт только на Samsung: проверен там (h768); Pixel adbd при смене USB-режима не перезапускает. */
    private val pinPort = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private val busy = AtomicBoolean(false)
    @Volatile private var pendingUntil = 0L
    private val failed = ArrayDeque<Long>()

    /** Перезапуск идёт: новые экраны стоит подождать, а не сразу уводить в группу 0. */
    val pending: Boolean get() = busy.get() && SystemClock.elapsedRealtime() < pendingUntil

    fun onHostDied(context: Context?) {
        val ctx = context?.applicationContext ?: return log("перезапуск хоста: нет контекста")
        blocker(ctx)?.let { return log("перезапуск хоста не делаю: $it") }
        val now = SystemClock.elapsedRealtime()
        synchronized(failed) {
            while (failed.isNotEmpty() && now - failed.first() > WINDOW_MS) failed.removeFirst()
            if (failed.size >= MAX_FAILED_IN_WINDOW) return log("перезапуск хоста не делаю: ${failed.size} неудачных за 10 мин")
        }
        if (!busy.compareAndSet(false, true)) return
        pendingUntil = now + PENDING_MS
        thread(name = "vdm-restart", isDaemon = true) {
            val ok = try { restart(ctx) } finally { busy.set(false); pendingUntil = 0 }
            // После busy=false: tcpip перезапускает adbd, хост умрёт ещё раз и должен подняться обычным путём.
            if (ok) pinViaAdbd(ctx)
        }
    }

    /** null - можно перезапускать; иначе причина для лога. */
    private fun blocker(ctx: Context): String? = when {
        !VdmDesktop.supported -> "Android ниже 17"
        !DelegatedDesktopAccess.isReady(ctx) -> "активация закончилась"
        !PhoneActivationState(ctx).paired || !PhoneAdb.hasIdentity(ctx) -> "активация не с телефона (нет пары adb)"
        Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) != 1 -> "отладка по Wi-Fi выключена"
        else -> null
    }

    private fun restart(ctx: Context): Boolean {
        val started = SystemClock.elapsedRealtime()
        for ((attempt, delay) in RETRY_DELAYS_MS.withIndex()) {
            Thread.sleep(delay) // adbd поднимается заново после убийства группы
            blocker(ctx)?.let { log("перезапуск хоста прерван: $it"); return false }
            if (VdmHostLink.connected) return true
            val outcome = runCatching { launch(ctx) }
            outcome.exceptionOrNull()?.let { e ->
                val reason = (e as? AdbAccessException)?.reason?.name ?: "${e.javaClass.simpleName}: ${e.message}"
                log("перезапуск хоста, попытка ${attempt + 1}: $reason")
            }
            if (outcome.isSuccess && waitConnected()) {
                log("хост перезапущен за ${SystemClock.elapsedRealtime() - started} мс (попытка ${attempt + 1})")
                return true
            }
        }
        synchronized(failed) { failed.addLast(SystemClock.elapsedRealtime()) }
        log("перезапуск хоста не удался: новые экраны пойдут обычным путём")
        return false
    }

    private val tcpipTried = AtomicBoolean(false)

    /**
     * h768test2: setprop service.adb.tcp.port из-под shell на Samsung A17 запрещён, поэтому просим сам adbd -
     * сервис `tcpip:5555`, как `adb tcpip 5555`. adbd ставит свойство и перезапускается (хост умрёт, поднимется
     * через Wi-Fi). Делаем только при Wi-Fi и один раз за процесс, чтобы не зациклиться, если свойство не держится.
     */
    private fun pinViaAdbd(ctx: Context) {
        if (!pinPort) return
        if (tcpPortProperty() == FIXED_TCP_PORT.toString()) return
        if (!PhoneAdb.wifiAvailable(ctx)) return
        if (!tcpipTried.compareAndSet(false, true)) return
        val port = PhoneActivationState(ctx).connectPort
        if (port !in 1..65535) return log("tcpip: нет порта")
        val reply = runCatching {
            PhoneAdb.session(ctx, LocalAddressPolicy.LOOPBACK, port) { adb ->
                adb.open("tcpip:$FIXED_TCP_PORT").use { stream ->
                    runCatching { stream.source.readUtf8Line() }.getOrNull()?.trim().orEmpty()
                }
            }
        }
        log("tcpip:$FIXED_TCP_PORT через порт $port: ${reply.getOrNull()?.ifEmpty { "без ответа" }
            ?: reply.exceptionOrNull()?.let { (it as? AdbAccessException)?.reason?.name ?: it.javaClass.simpleName }}")
        Thread.sleep(3_000)
        log("tcpip: tcp.port=${tcpPortProperty() ?: "нет"}")
    }

    /**
     * Порт: сначала порт активации (если adbd не перезапускался, он жив), потом закреплённый 5555, потом свойство
     * adbd, потом mDNS.
     * vdm4 у h821 и SM-S942B: свойство не прочиталось, mDNS за 12 с ничего не нашёл - всё пишем в лог.
     */
    private fun launch(ctx: Context) {
        val saved = PhoneActivationState(ctx).connectPort
        if (saved in 1..65535) {
            try { return start(ctx, AdbEndpoint(LocalAddressPolicy.LOOPBACK, saved)) }
            catch (e: AdbAccessException) { log("порт активации $saved: ${e.reason}") }
        }
        if (pinPort && saved != FIXED_TCP_PORT) {
            try { return start(ctx, AdbEndpoint(LocalAddressPolicy.LOOPBACK, FIXED_TCP_PORT)) }
            catch (e: AdbAccessException) { log("закреплённый порт $FIXED_TCP_PORT: ${e.reason} (tcp.port=${tcpPortProperty() ?: "нет"})") }
        }
        val property = propertyEndpoint()
        log("порт из свойства: ${property?.port ?: "нет"}; wifi=${PhoneAdb.wifiAvailable(ctx)} adb_wifi=${Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0)}")
        val endpoint = property?.takeIf { it.port != saved } ?: discover(ctx)
            ?: throw IllegalStateException("порт отладки по Wi-Fi не найден")
        start(ctx, endpoint)
        PhoneActivationState(ctx).connectPort = endpoint.port
    }

    private fun start(ctx: Context, endpoint: AdbEndpoint) {
        val hosts = listOf(LocalAddressPolicy.LOOPBACK, endpoint.host).distinct()
        var last: Exception? = null
        for (host in hosts) {
            try {
                PhoneAdb.session(ctx, host, endpoint.port) { adb ->
                    val uid = adb.shell("id -u")
                    if (uid.exitCode != 0 || !PhoneActivationPolicy.isShellUid(uid.output))
                        throw AdbAccessException(AdbStage.SHELL, AdbReason.SHELL_REJECTED)
                    adb.shell(VdmHostPolicy.startCommand(ctx.applicationInfo.sourceDir, ctx.packageName, Process.myPid()))
                }
                log("хост запущен заново через ${if (host == LocalAddressPolicy.LOOPBACK) "loopback" else "адрес телефона"}:${endpoint.port}")
                return
            } catch (e: AdbAccessException) {
                last = e
                val retry = e.reason == AdbReason.REFUSED || e.reason == AdbReason.TIMEOUT || e.reason == AdbReason.NETWORK_UNREACHABLE
                if (!retry) throw e
            }
        }
        throw last ?: IllegalStateException("нет адреса")
    }

    /** service.adb.tls.port: главный процесс без проверок hidden API; SELinux может не пустить - тогда mDNS. */
    private fun propertyEndpoint(): AdbEndpoint? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        val port = (get.invoke(null, "service.adb.tls.port") as? String)?.trim()?.toIntOrNull()
        port?.takeIf { it in 1..65535 }?.let { AdbEndpoint(LocalAddressPolicy.LOOPBACK, it) }
    }.getOrNull()

    private fun tcpPortProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, "service.adb.tcp.port") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun discover(ctx: Context): AdbEndpoint? {
        val found = AtomicReference<AdbEndpoint?>(null)
        var discovery: AdbDiscovery? = null // только на main
        val main = Handler(Looper.getMainLooper())
        main.post { discovery = runCatching { AdbDiscovery(ctx) { _, c -> if (c != null) found.set(c) }.also { it.start() } }.getOrNull() }
        try {
            val until = SystemClock.elapsedRealtime() + DISCOVERY_WAIT_MS
            while (found.get() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(200)
        } finally {
            main.post { discovery?.close() }
        }
        log("mDNS: ${found.get()?.let { "порт ${it.port}" } ?: "порт connect не найден за $DISCOVERY_WAIT_MS мс"}")
        return found.get()
    }

    private fun waitConnected(): Boolean {
        val until = SystemClock.elapsedRealtime() + CONNECT_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (VdmHostLink.connected) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun log(text: String) = AppLog.i("VDM $text", false)
}
