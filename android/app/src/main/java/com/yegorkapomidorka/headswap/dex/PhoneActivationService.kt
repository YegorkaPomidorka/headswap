package com.yegorkapomidorka.headswap.dex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pairs with this phone's wireless debugging (the code is typed into a notification, so the user never leaves
 * the system pairing dialog) and starts the same activation the computer command does. A foreground service keeps
 * the process alive while the user is in system settings. The activation itself restarts the app, which ends
 * this service with the process; the new process reads the outcome from its own access state.
 */
class PhoneActivationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val codes = LinkedBlockingQueue<String>()
    @Volatile private var cancelled = false
    @Volatile private var pairing: AdbEndpoint? = null
    @Volatile private var connect: AdbEndpoint? = null
    private var discovery: AdbDiscovery? = null
    private var worker: Thread? = null
    private lateinit var manager: NotificationManager

    private class Cancelled : Exception()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, getString(R.string.pa_notif_channel_progress), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CODE, getString(R.string.pa_notif_channel_code), NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() obliges us to call this within seconds, whatever the intent turns out to be.
        startForeground(ID_PROGRESS, progressNotification(getString(R.string.pa_s_searching)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        when (intent?.action) {
            ACTION_START -> if (worker?.isAlive != true) {
                cancelled = false
                codes.clear()
                val pairPort = intent.getIntExtra(EXTRA_PAIR_PORT, 0)
                val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.isNotEmpty() }
                val connectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0)
                worker = Thread({ run(pairPort, code, connectPort) }, "headswap-phone-activation").also { it.start() }
            }
            ACTION_COLLECT -> if (worker?.isAlive != true) {
                cancelled = false
                worker = Thread({ collect() }, "headswap-phone-collect").also { it.start() }
            }
            ACTION_CODE -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                // ответ принят: обновить уведомление, иначе шторка держит его с крутилкой и после cancel()
                codeReceived()
                if (worker?.isAlive == true) codes.offer(typed.trim()) else { manager.cancel(ID_CODE); stopSelf() }
            }
            ACTION_CANCEL -> {
                cancelled = true
                codes.offer("")
                if (worker?.isAlive != true) stopSelf()
            }
            else -> if (worker?.isAlive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        codes.offer("")
        stopDiscovery()
        manager.cancel(ID_CODE)
        super.onDestroy()
    }

    // --- the activation ---

    private fun run(manualPair: Int, manualCode: String?, manualConnect: Int) {
        val state = PhoneActivationState(this)
        try {
            step(PhoneActivation.Step.SEARCHING)
            if (!PhoneAdb.wifiAvailable(this)) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.NO_WIFI)
            startDiscovery()
            if (state.paired && PhoneAdb.hasIdentity(this)) {
                try {
                    connectAndActivate(manualConnect, state)
                    return
                } catch (e: AdbAccessException) {
                    if (e.reason != AdbReason.NOT_PAIRED) throw e
                    state.paired = false // the phone forgot our key: pair again with a new code
                }
            }
            pair(manualPair, manualCode, state)
            connectAndActivate(manualConnect, state)
        } catch (e: Cancelled) {
            PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
        } catch (e: AdbAccessException) {
            fail(e.reason)
        } catch (e: InterruptedException) {
            PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
        } catch (e: Exception) {
            AppLog.e("PHONE activation failed trace=${AdbDiagnostics.safeTrace(e)}")
            fail(AdbReason.INTERNAL)
        } finally {
            finishWork()
        }
    }

    private fun pair(manualPair: Int, manualCode: String?, state: PhoneActivationState) {
        val deadline = SystemClock.elapsedRealtime() + CODE_WAIT_MS
        var code = manualCode
        var lastError: AdbReason? = null
        while (true) {
            if (code == null) {
                step(PhoneActivation.Step.WAIT_CODE)
                showCodeNotification(lastError)
                val left = deadline - SystemClock.elapsedRealtime()
                code = (if (left > 0) codes.poll(left, TimeUnit.MILLISECONDS) else null)
                    ?: throw AdbAccessException(AdbStage.PAIR_CONNECT, AdbReason.TIMEOUT)
                if (cancelled || code.isEmpty()) throw Cancelled()
            }
            step(PhoneActivation.Step.PAIRING)
            val typed: String = code
            try {
                if (!PhoneActivationPolicy.isValidCode(typed)) throw AdbAccessException(AdbStage.VALIDATE, AdbReason.INVALID_CODE)
                val endpoint = if (manualPair > 0) AdbEndpoint(LocalAddressPolicy.LOOPBACK, manualPair)
                    else waitFor(PAIRING_WAIT_MS) { pairing } ?: throw AdbAccessException(AdbStage.PAIR_CONNECT, AdbReason.NO_PAIRING_PORT)
                withHosts(endpoint) { host -> PhoneAdb.pair(this, host, endpoint.port, typed) }
                state.paired = true
                manager.cancel(ID_CODE)
                return
            } catch (e: AdbAccessException) {
                // A wrong code, a closed dialog or a typo is asked again in the notification; typed-in values are not.
                val again = manualCode == null && e.reason in ASK_AGAIN && SystemClock.elapsedRealtime() < deadline
                if (!again) throw e
                lastError = e.reason
                code = null
            }
        }
    }

    private fun connectAndActivate(manualConnect: Int, state: PhoneActivationState) {
        step(PhoneActivation.Step.CONNECTING)
        val endpoint = if (manualConnect > 0) AdbEndpoint(LocalAddressPolicy.LOOPBACK, manualConnect)
            else waitFor(CONNECT_WAIT_MS) { connect } ?: throw AdbAccessException(AdbStage.CONNECT, AdbReason.NO_CONNECT_PORT)
        step(PhoneActivation.Step.STARTING)
        val wasReady = DelegatedDesktopAccess.isReady(this)
        val audio = DexPrefs(this).usbPlaybackAudio
        var usedHost = LocalAddressPolicy.LOOPBACK
        val nonce = withHosts(endpoint) { host ->
            usedHost = host
            PhoneAdb.activate(this, host, endpoint.port, audio, state)
        }
        state.paired = true // the phone accepted the key
        manager.cancel(ID_CODE)
        // Normally the process is replaced within seconds and nothing below runs. If it is not, see why.
        val until = SystemClock.elapsedRealtime() + SURVIVE_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (!wasReady && DelegatedDesktopAccess.isReady(this)) {
                PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
                return
            }
            if (cancelled) throw Cancelled()
            Thread.sleep(500)
        }
        val log = runCatching { PhoneAdb.readLog(this, usedHost, endpoint.port, nonce) }.getOrNull()
        val failure = PhoneActivationPolicy.failureMessage(log)
        state.failure = failure
        state.failureChecked = true
        if (failure == null && (PhoneActivationPolicy.logShowsReady(log) || DelegatedDesktopAccess.isReady(this))) {
            PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
        } else {
            PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.FAILED, AdbReason.DISPATCH_UNCONFIRMED, failure)
            postResult(failure ?: getString(AdbReason.DISPATCH_UNCONFIRMED.messageRes))
        }
    }

    /** After an attempt expired in a restarted process: read the launcher's failure line once. */
    private fun collect() {
        val state = PhoneActivationState(this)
        try {
            val nonce = state.nonce
            if (nonce == null || !PhoneAdb.hasIdentity(this) || !PhoneAdb.wifiAvailable(this)) return
            startDiscovery()
            val endpoint = waitFor(COLLECT_WAIT_MS) { connect } ?: return
            val log = withHosts(endpoint) { host -> PhoneAdb.readLog(this, host, endpoint.port, nonce) }
            state.failure = PhoneActivationPolicy.failureMessage(log)
        } catch (e: Exception) {
            AppLog.i("PHONE failure log unavailable ${e.javaClass.simpleName}")
        } finally {
            state.failureChecked = true // one attempt per expired activation, whatever came out
            finishWork()
        }
    }

    private fun fail(reason: AdbReason) {
        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.FAILED, reason)
        AppLog.i("PHONE activation failed reason=$reason")
        postResult(getString(reason.messageRes))
    }

    private fun finishWork() {
        stopDiscovery()
        manager.cancel(ID_CODE)
        if (PhoneActivation.status.step != PhoneActivation.Step.FAILED) PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
        main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    /** Loopback needs no local-network permission; the discovered address of this phone is the fallback. */
    private fun <T> withHosts(endpoint: AdbEndpoint, block: (String) -> T): T {
        val hosts = listOf(LocalAddressPolicy.LOOPBACK, endpoint.host).distinct()
        for ((index, host) in hosts.withIndex()) {
            if (cancelled) throw Cancelled()
            try {
                return block(host)
            } catch (e: AdbAccessException) {
                val retry = e.reason == AdbReason.REFUSED || e.reason == AdbReason.TIMEOUT || e.reason == AdbReason.NETWORK_UNREACHABLE
                if (!retry || index == hosts.lastIndex) throw e
            }
        }
        throw AdbAccessException(AdbStage.CONNECT, AdbReason.INTERNAL)
    }

    private fun <T : Any> waitFor(timeoutMs: Long, value: () -> T?): T? {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            value()?.let { return it }
            if (cancelled) throw Cancelled()
            if (SystemClock.elapsedRealtime() >= until) return null
            Thread.sleep(200)
        }
    }

    private fun step(step: PhoneActivation.Step) {
        PhoneActivation.status = PhoneActivation.Status(step)
        val text = when (step) {
            PhoneActivation.Step.WAIT_CODE -> R.string.pa_s_wait_code
            PhoneActivation.Step.PAIRING -> R.string.pa_s_pairing
            PhoneActivation.Step.CONNECTING -> R.string.pa_s_connecting
            PhoneActivation.Step.STARTING -> R.string.pa_s_starting
            else -> R.string.pa_s_searching
        }
        manager.notify(ID_PROGRESS, progressNotification(getString(text)))
    }

    // --- discovery (main thread: NSD callbacks) ---

    private fun startDiscovery() {
        pairing = null
        connect = null
        main.post {
            if (discovery == null) discovery = AdbDiscovery(this) { p, c -> pairing = p; connect = c }.also { it.start() }
        }
    }

    private fun stopDiscovery() {
        main.post { discovery?.close(); discovery = null }
    }

    // --- notifications ---

    private fun progressNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.pa_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_headswap),
                getString(R.string.pa_notif_cancel), servicePending(ACTION_CANCEL, 1, mutable = false)).build())
            .build()

    /** The only place the code is entered: a reply field in the shade, over the system pairing dialog. */
    private fun showCodeNotification(error: AdbReason?) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pa_notif_code_label)).build()
        val reply = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_headswap),
            getString(R.string.pa_notif_reply), servicePending(ACTION_CODE, 2, mutable = true))
            .addRemoteInput(input).build()
        val settings = PendingIntent.getActivity(this, 3, PhoneActivation.developerSettingsIntent(), PendingIntent.FLAG_IMMUTABLE)
        val text = if (error == null) getString(R.string.pa_notif_code_text)
            else getString(R.string.pa_notif_code_error, getString(error.messageRes))
        manager.notify(ID_CODE, Notification.Builder(this, CHANNEL_CODE)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.pa_notif_code_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings)
            .setOngoing(true)
            .addAction(reply)
            .build())
    }

    /**
     * Код из поля ответа получен. Пока уведомление не обновлено, SystemUI крутит индикатор отправки и продлевает ему
     * жизнь даже после cancel(): шторка показывала крутилку рядом с «Headswap Desktop активирован». Обновление без поля
     * ответа индикатор снимает; при неверном коде showCodeNotification вернёт поле.
     */
    private fun codeReceived() {
        manager.notify(ID_CODE, Notification.Builder(this, CHANNEL_CODE)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.pa_notif_code_title))
            .setContentText(getString(R.string.pa_s_pairing))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build())
    }

    private fun postResult(text: String) {
        manager.notify(ID_RESULT, Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.pa_notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }

    /** Explicit intent to this service only; a reply field needs a mutable PendingIntent. */
    private fun servicePending(action: String, code: Int, mutable: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getForegroundService(this, code, intent(this, action), flags)
    }

    companion object {
        const val ACTION_START = "com.yegorkapomidorka.headswap.PHONE_ACTIVATION_START"
        const val ACTION_COLLECT = "com.yegorkapomidorka.headswap.PHONE_ACTIVATION_COLLECT"
        const val ACTION_CODE = "com.yegorkapomidorka.headswap.PHONE_ACTIVATION_CODE"
        const val ACTION_CANCEL = "com.yegorkapomidorka.headswap.PHONE_ACTIVATION_CANCEL"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_CODE = "code"
        const val EXTRA_CONNECT_PORT = "connect_port"
        private const val KEY_CODE = "pairing_code"
        private const val CHANNEL_PROGRESS = "phone_activation"
        private const val CHANNEL_CODE = "phone_activation_code"
        private const val ID_PROGRESS = 41
        private const val ID_CODE = 42
        private const val ID_RESULT = 43
        private const val CODE_WAIT_MS = 5 * 60_000L
        private const val PAIRING_WAIT_MS = 8_000L
        private const val CONNECT_WAIT_MS = 15_000L
        private const val COLLECT_WAIT_MS = 10_000L
        private const val SURVIVE_MS = 25_000L
        private val ASK_AGAIN = setOf(AdbReason.INVALID_CODE, AdbReason.PAIR_REJECTED, AdbReason.NO_PAIRING_PORT)

        /**
         * From the restarted process once access is ready: the progress notification of the killed process stays
         * spinning otherwise. Replace it with the result, only when an on-phone attempt was pending.
         */
        fun onAccessReady(context: Context) {
            val state = PhoneActivationState(context)
            if (state.nonce == null) return
            state.clear()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(ID_PROGRESS)
            manager.cancel(ID_CODE)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { PendingIntent.getActivity(context, 4, it, PendingIntent.FLAG_IMMUTABLE) }
            manager.notify(ID_RESULT, Notification.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_headswap)
                .setContentTitle(context.getString(R.string.pa_notif_done))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }

        fun intent(context: Context, action: String): Intent =
            Intent(context, PhoneActivationService::class.java).setAction(action)
    }
}
