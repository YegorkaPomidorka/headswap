package com.yegorkapomidorka.headswap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.ServerPhase
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.autosrv.AutoServerPolicy
import com.yegorkapomidorka.headswap.autosrv.Trigger
import com.yegorkapomidorka.headswap.ui.HeadswapNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Foreground-сервис (connectedDevice): держит транспорт (ParcelFileDescriptor аксессуара или ADB-сокеты) и сессию с ГУ.
 * AA-сторона моста (BridgeHost) живёт на время сервиса, а не одной USB-сессии: обрыв USB закрывает
 * CarLife-сессию, AA остаётся живой BridgeHost.GRACE_MS и подхватывается следующей USB-сессией.
 * Всё состояние сервиса (session, host, stopRequested) трогает только главная нить.
 */
class BridgeService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: CarLifeSession? = null
    private var host: BridgeHost? = null
    private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** Автозапуск пропущен из-за неподключённой службы спецвозможностей: RETRY запустит его, когда она появится. */
    @Volatile private var serverStartPending = false
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                session?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService требует startForeground в течение 5 с, делаем сразу
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        startForegroundTyped(n)

        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed увидит stopRequested: AA закрывается сразу
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB) {
            val io = AdbLink.take()
            if (io == null) {
                AppLog.i("сервис: ADB-набор сокетов уже забран или сброшен, останавливаюсь")
                CarLifeState.status = "ADB: набор сокетов не найден"
                failStart()
                return START_NOT_STICKY
            }
            val old = session
            if (old != null) {
                // ГУ переподключилось: старая сессия умирает от EOF сама, но ждать её не будем.
                // onSessionClosed старой проигнорируется (session !== s), AA-хост переиспользует новая сессия
                AppLog.i("сервис: новое ADB-подключение, закрываю прежнюю сессию")
                old.close("новое ADB-подключение")
            }
            CarLifeState.accessory = "ADB (TCP 127.0.0.1)"
            return startSession(io)
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            return START_NOT_STICKY
        }

        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return START_NOT_STICKY
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return START_NOT_STICKY
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            AppLog.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            AppLog.i("сервис: openAccessory вернул null")
            CarLifeState.status = "openAccessory: ошибка"
            failStart()
            return START_NOT_STICKY
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        CarLifeState.accessory = MainActivity.describe(acc)
        return startSession(AoaIo(pfd))
    }

    /** Общая часть старта для AOA и ADB: настройки, AA-хост, wakelock, сессия поверх открытого транспорта. */
    private fun startSession(io: CarLifeIo): Int {
        val aaPrefs = AaPrefs(this)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, aaPrefs.micVrStatus, aaPrefs.fpsSetting,
            metaMusic = aaPrefs.metaMusic, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // AA-сессия к серверу ГУ на 127.0.0.1:5277 живёт на уровне сервиса (BridgeHost), а не этой USB-сессии
        var h = host
        // режим фиксируется на подключение; тёплая AA с другой геометрией не годится (SDR уже ушёл), её закрываем
        val wantMode = aaPrefs.videoMode.effective(aaPrefs.transcode)
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим сменился (${h.core.videoMode.name} -> ${wantMode.name}), тёплую AA закрываю и поднимаю заново")
            h.shutdown("смена видео-режима")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, wantMode, previous = h)
            host = h
            if (h == null) {
                // нет сертификата head unit или мост не собрался: причина уже в AaState.setupError, USB-сессию не открываем
                io.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return START_NOT_STICKY
            }
        } else {
            AppLog.i("BRIDGE: AA-хост уже есть, переиспользую (grace=${h.isInGrace})")
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(io, cfg, h.core) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
        return START_NOT_STICKY
    }

    /** Новый AA-хост: ядро, коннектор, сертификаты. null = мост не поднялся (причина в AaState.setupError). */
    private fun createHost(aaPrefs: AaPrefs, mode: VideoMode, previous: BridgeHost?): BridgeHost? {
        return try {
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            core.transcodeEnabled = aaPrefs.transcode
            core.videoMode = mode
            core.expectFeatureConfig = true
            if (aaPrefs.videoMode != mode) AppLog.i("BRIDGE видео-режим ${aaPrefs.videoMode.name} недоступен без перекодирования, использую ${mode.name}")
            // аудио-синки (MEDIA/SPEECH) объявляются в момент запроса телефона, когда уже известно решение по звуку
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb) }) },
                serverPrep = { phase -> prepareServer(core, phase) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null) finish()
                }
            })
            ref[0] = h
            h
        } catch (e: AaCertsMissingException) {
            AppLog.e("BRIDGE не стартовал: нет сертификата head unit", e)
            AaState.setupError = e.message
            AaState.status = "ошибка: нет сертификата"
            null
        } catch (e: Throwable) {
            AppLog.e("BRIDGE не стартовал (сертификаты AA)", e)
            AaState.setupError = getString(R.string.svc_bridge_failed, "${e.javaClass.simpleName}: ${e.message}")
            AaState.status = "ошибка: мост не стартовал"
            null
        }
    }

    /**
     * Foreground-тип connectedDevice; microphone добавляем, только если есть RECORD_AUDIO и микрофон телефона
     * вообще может понадобиться (режим не «машина»): иначе SecurityException. Из фона Android 14+ тип microphone
     * не даёт (while-in-use) - тогда остаёмся на connectedDevice.
     */
    private fun startForegroundTyped(n: Notification) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val micWanted = AaPrefs(this).micMode != MicMode.CAR &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micWanted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, n, type)
            if (micWanted) AppLog.i("сервис: foreground-тип connectedDevice|microphone")
        } catch (e: Exception) {      // SecurityException или ForegroundServiceStartNotAllowedException
            AppLog.e("сервис: foreground-тип с microphone не дали, остаюсь на connectedDevice", e)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    /**
     * Автозапуск сервера ГУ AA на нити коннектора: до первой попытки подключения (USB подключён), после конца
     * AA-сессии и после блокировки экрана. Ждём окончания не дольше AutoServerPolicy.waitMs, дальше коннектор идёт как обычно.
     */
    private fun prepareServer(core: AaBridgeCore, phase: ServerPhase) {
        val trigger = when (phase) {
            ServerPhase.FIRST -> Trigger.USB_ATTACH
            ServerPhase.AFTER_SESSION -> Trigger.AFTER_SESSION
            ServerPhase.RETRY -> Trigger.RETRY
        }
        val autoPref = AaPrefs(this).autoServer
        // после старта процесса служба спецвозможностей подключается позже сервиса: даём ей время
        if (trigger != Trigger.RETRY && autoPref && !AutoServer.isConnected && AutoServer.isListed(this)) {
            AutoServer.log("жду подключения службы спецвозможностей (${trigger.label})")
            AutoServer.awaitService(SERVICE_WAIT_MS)
        }
        val skip = AutoServerPolicy.skipReason(
            trigger, autoPref, AutoServer.isConnected,
            usbAlive = core.hasCarlifeLink, warmAaSession = false, lastLocked = AutoServer.lastLocked,
            startPending = serverStartPending, consentHold = AutoServer.consentPending(),
        )
        if (skip != null) {
            if (skip == AutoServerPolicy.CONSENT_SKIP) core.showStatus(AaBridgeCore.TEXT_AA_CONSENT)
            if (trigger != Trigger.RETRY && !AutoServer.isConnected) serverStartPending = true
            // RETRY вызывается каждые несколько секунд и почти всегда пропускается: молча
            if (trigger != Trigger.RETRY) AutoServer.log("пропуск (${trigger.label}): $skip")
            return
        }
        serverStartPending = false
        core.showStatus(AutoServerPolicy.runningText(trigger))
        val run = AutoServer.start(trigger)
        val out = AutoServer.await(run, AutoServerPolicy.waitMs(trigger))
        if (out == null) {
            AutoServer.log("автоматизация ещё идёт после ${AutoServerPolicy.waitMs(trigger) / 1000} с, подключаюсь как обычно")
            return
        }
        AutoServer.log("итог для коннектора (${trigger.label}): $out")
        AutoServerPolicy.resultText(trigger, out.result)?.let { core.showStatus(it) }
    }

    /** Старт не удался: если жив AA-хост (идёт grace), сервис не гасим, иначе finish. */
    private fun failStart() {
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        session = null
        val h = host
        if (h == null) {
            finish()
        } else {
            // обрыв USB: CarLife закрыт, AA держим graceMs (или закрываем сразу, если Стоп)
            h.onUsbSessionClosed(immediate = stopRequested)
        }
    }

    private fun finish() {
        CarLifeState.running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        audioDiag.stop()
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** ADB-режим: AdbLink собрал шесть сокетов от ГУ и просит начать сессию. */
        const val ACTION_ADB = "com.yegorkapomidorka.headswap.ADB"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        /** Сколько автозапуск ждёт подключения включённой службы спецвозможностей. */
        private const val SERVICE_WAIT_MS = 3_000L
    }
}
