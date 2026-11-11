package com.yegorkapomidorka.headswap

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaShadowNav
import com.yegorkapomidorka.headswap.aa.DesktopNavHints
import com.yegorkapomidorka.headswap.carapp.CarAppHost
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.aa.CallStateSource
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.DesktopVideoSelection
import com.yegorkapomidorka.headswap.aa.EqLive
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneCall
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.RefDecoder
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.DelegatedDexConnector
import com.yegorkapomidorka.headswap.dex.DesktopAccess
import com.yegorkapomidorka.headswap.dex.DesktopAudioCapture
import com.yegorkapomidorka.headswap.dex.DesktopAudioState
import com.yegorkapomidorka.headswap.dex.DesktopFailure
import com.yegorkapomidorka.headswap.dex.DesktopKeys
import com.yegorkapomidorka.headswap.dex.DesktopMusicPlayback
import com.yegorkapomidorka.headswap.dex.DesktopRecoveryPolicy
import com.yegorkapomidorka.headswap.dex.DexPrefs
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.dex.PhoneAssistant
import com.yegorkapomidorka.headswap.dex.UsbDetachPolicy
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.LinkSetupException
import com.yegorkapomidorka.headswap.link.StreamTransport
import com.yegorkapomidorka.headswap.link.Tcp6Listener
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.bt.CarBtState
import com.yegorkapomidorka.headswap.link.bt.CarlifeBtProbe
import com.yegorkapomidorka.headswap.link.car.AutoOffDebounce
import com.yegorkapomidorka.headswap.link.car.BT_HINT_MS
import com.yegorkapomidorka.headswap.link.car.BtReconnectHint
import com.yegorkapomidorka.headswap.link.car.CAR_NET_DELAY_MS
import com.yegorkapomidorka.headswap.link.car.CAR_NET_TIMEOUT_MS
import com.yegorkapomidorka.headswap.link.car.CarConnMode
import com.yegorkapomidorka.headswap.link.car.CarMac
import com.yegorkapomidorka.headswap.link.car.CarNetEvent
import com.yegorkapomidorka.headswap.link.car.CarNetPolicy
import com.yegorkapomidorka.headswap.link.car.CarNetState
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.HotspotBeacon
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import com.yegorkapomidorka.headswap.ui.HeadswapNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Foreground-сервис (connectedDevice): держит транспорт (USB-аксессуар или debug-TCP) и сессию с ГУ.
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

    // --- режим Headswap Desktop (BridgeMode.DESKTOP); в режиме Android Auto всё ниже не используется ---
    /** Режим текущего/последнего AA- или desktop-хоста и сессии: снимается в startSession, не читается из prefs на лету. Только главная нить. */
    private var hostMode = BridgeMode.ANDROID_AUTO
    private var sessionMode = BridgeMode.ANDROID_AUTO
    /** В этом экземпляре сервиса была desktop-сессия: onDestroy/finish чистят её глобальное состояние, даже если режим уже сменился. */
    private var desktopUsed = false
    private var desktopAudioImpl: DesktopAudioCapture? = null
    private val desktopAudio: DesktopAudioCapture
        get() = desktopAudioImpl ?: DesktopAudioCapture(applicationContext).also { desktopAudioImpl = it }
    private var lastSessionWasUsb = false
    private var physicalUsbDetached = false
    private val desktopRecovery = DesktopRecoveryPolicy()
    private data class Recovery(val attempt: DesktopRecoveryPolicy.Attempt, val session: CarLifeSession, val owner: BridgeHost)
    private var pendingDesktopRecovery: Recovery? = null

    /** debug tcp6: слушатель ждёт 6/6 (нить debug-tcp6); Стоп его отменяет. */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    /** Идёт подъём tcp6/беспровода (от старта до 6/6 или отказа); сервис в это время не гасим. Только главная нить. */
    private var linkWaiting = false
    /** Беспровод (бета, ветка B) включён пользователем: после обрыва снова ждём машину. Пишет главная нить, читает и коннектор. */
    @Volatile private var wifiBMode = false
    /**
     * Авторежим: выбранная машина рядом по Bluetooth (ACL_CONNECTED), ожидание по Wi-Fi держим, пока она рядом.
     * Пишет главная нить, читают колбэки статуса. Зеркало в CarLifeState.carAuto для отчёта.
     */
    @Volatile private var autoMode = false
        set(v) {
            field = v
            CarLifeState.carAuto = v
        }
    /** Ожидание запущено/принято авторежимом: после ухода машины его не возобновляем. Только главная нить. */
    private var autoStarted = false
    /** USB-сессия кончилась при машине рядом: через [USB_TO_AIR_MS] начнём ждать по воздуху. Только главная нить. */
    private var usbToAirPending = false
    private val usbToAirTick = Runnable { usbToAir() }
    private val autoOff = AutoOffDebounce(AUTO_OFF_DEBOUNCE_MS) { SystemClock.elapsedRealtime() }
    private val autoOffTick = Runnable { if (autoOff.fire()) applyAutoOff() }
    /** Подсказка «подключи телефон к Wi-Fi машины» уже показана (один раз на заход авторежима). */
    private var hintShown = false
    private val hintTick = Runnable { showWifiHint() }
    /** Вход в Wi-Fi машины (WifiNetworkSpecifier); создаётся при первом запросе: без сети машины (и на USB) его нет вовсе. */
    private var carNetImpl: CarNetwork? = null
    private val carNet: CarNetwork
        get() = carNetImpl ?: CarNetwork(this) { CarLifeState.carNet = it.label }.also { carNetImpl = it }
    private val carNetBusy: Boolean
        get() = carNetImpl?.state.let { it == CarNetState.REQUESTING || it == CarNetState.AVAILABLE }
    /** Начало ожидания по Wi-Fi (авто или ручного): от него считается [CAR_NET_DELAY_MS]. Только главная нить. */
    private var waitSince = 0L
    /** Когда машина последний раз была на связи (сессия USB/воздух, сеть машины); 0 = не была. Только главная нить. */
    private var carSeenAt = 0L
    /** В этом ожидании выбранная машина оказалась подключена по Bluetooth. Только главная нить. */
    private var carBtNear = false
    /** 6/6 получены, сессия вот-вот стартует: запрос сети машины уже не нужен. Только главная нить. */
    private var sixReady = false
    /** Ожидание сознательно прерывается ради нового маяка в сети машины: ветка отказа в startTcp6 это не сдача. Только главная нить. */
    private var restartingForCarNet = false
    private val carNetTick = Runnable { requestCarNet() }
    /** В этом ожидании ГУ прислала TARGET_INFO по Bluetooth: Wi-Fi Direct, сеть машины не нужна. Только главная нить. */
    private var p2pThisWait = false
    /** Wi-Fi Direct-машина (TARGET_INFO в этом ожидании или в профиле): сеть машины не запрашиваем, wifi-подсказку не показываем. */
    private val wifiDirectCar: Boolean
        get() = p2pThisWait || CarProfile(this).connMode == CarConnMode.BT_CARLIFE
    /** `car-skip` уже записан в этом ожидании. Только главная нить. */
    private var carSkipLogged = false
    /** Подключений ГУ к RFCOMM `carlife` в этом ожидании и показана ли подсказка «переподключи Bluetooth». Только главная нить. */
    private var btAcceptsThisWait = 0
    private var btHintShown = false
    /** Висит уведомление «включите Wi-Fi» (Wi-Fi Direct упёрся в выключенный Wi-Fi). Только главная нить. */
    private var wifiOffShown = false
    private val btHintTick = Runnable { checkBtHint() }
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** Ветка A, диагностика: RFCOMM `carlife` слушаем, пока ждём машину по Wi-Fi. Колбэки с bt-нити перекидываем на главную. */
    private val btProbe by lazy {
        CarlifeBtProbe(
            this,
            onAccept = { main.post { onBtAccept() } },
            onTargetInfo = { mac -> main.post { onBtTargetInfo(mac) } },
            onP2pBusy = { main.post { onP2pBusy() } },
            onWifi = { on -> main.post { onP2pWifi(on) } },
        )
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                if (sessionMode == BridgeMode.DESKTOP) {
                    // старое сообщение не должно гасить новый аксессуар или беспроводную сессию: отсутствие проверяем по списку
                    observePhysicalUsbDetach("USB accessory detached broadcast")
                    return
                }
                // беспроводную сессию отключение USB-аксессуара не касается
                session?.takeIf { it.transportKind == TransportCaps.USB_AOA.kind }?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    /** Режим DESKTOP: USB остановлен физически; 8-секундный grace рабочего стола и активация остаются. */
    private fun observePhysicalUsbDetach(reason: String): Boolean {
        val present = runCatching { getSystemService(UsbManager::class.java).accessoryList.orEmpty().isNotEmpty() }.getOrNull()
        if (!UsbDetachPolicy.confirmedDetached(lastSessionWasUsb, present)) return false
        if (physicalUsbDetached) return true
        physicalUsbDetached = true
        DesktopMusicPlayback.physicalDetach(this)
        cancelDesktopRecovery()
        autoMode = false
        wifiBMode = false
        autoStarted = false
        usbToAirPending = false
        main.removeCallbacks(usbToAirTick)
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.STOP, "usb-detach")
        pendingTcp6?.abort()
        CarLifeState.wireless = null
        btProbe.stop("USB отключён")
        AppLog.i("DESKTOP USB отключён: рабочий стол держу 8 с, активация остаётся ($reason)", false)
        val current = session
        if (current?.transportKind == TransportCaps.USB_AOA.kind) current.close("физически отключён USB")
        else if (host == null) finish()      // grace и закрытие хоста ведёт сам BridgeHost
        return true
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        AppLog.init(this)
        AaPrefs(this).migrateTo30()        // приложение могло запуститься от USB, не открывшись (то же в MainActivity.onCreate)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HU_DESKTOP) {
            // долгое «Домой» в лаунчере (h1061): ГУ уходит в своё меню (радио, флешка), возврат - иконкой CarLife на ГУ.
            // PAUSE/START от ГУ мост рабочего стола обрабатывает сам (onCarlifePause гасит вывод).
            val s = session
            val accepted = !stopRequested && s != null
            AppLog.i("DESKTOP «Домой» долгое: меню ГУ, accepted=$accepted", true)
            if (accepted) s!!.goToDesktop()
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        if (intent?.action == ACTION_DESKTOP_HOME) {
            // команда относится только к живой desktop-сессии: опоздавший клик не должен поднимать сервис и новый рабочий стол
            val accepted = !stopRequested && session != null && host?.showDesktop() == true
            AppLog.i("DESKTOP home request accepted=$accepted", false)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        if (intent?.action == ACTION_LAUNCHER_DEBUG) {
            // стенд лаунчера: экран, раздел, тема и карта без тапов и без окна MainActivity поверх рабочего стола
            com.yegorkapomidorka.headswap.launcher.LauncherDebug.apply(this, intent)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        DexState.desktopSession = foregroundMode() == BridgeMode.DESKTOP
        // startForegroundService требует startForeground в течение 5 с, делаем сразу
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        if (intent == null) {
            // процесс убит системой (LMK посреди сессии по воздуху, h610), Android поднял сервис по START_STICKY
            try {
                startForegroundTyped(n)
            } catch (e: Exception) {
                AppLog.e("сервис: перезапуск системой, foreground не дали", e)
                WLog.ev(WLog.TRIGGER, "sticky-fail", "err" to e.javaClass.simpleName)
                stopSelf()
                return START_NOT_STICKY
            }
            onStickyRestart()
            return stickiness()
        }
        startForegroundTyped(n)
        command(intent)
        return stickiness()
    }

    /**
     * Беспровод или авторежим включён: после убийства процесса Android перезапустит сервис ([onStickyRestart]).
     * Сам ACL_CONNECTED не придёт - Bluetooth машины не рвался, и без этого воздух не поднимался до следующей поездки (h610).
     */
    private fun stickiness() = if ((wifiBMode || autoMode) && !stopRequested) START_STICKY else START_NOT_STICKY

    /** Перезапуск после убийства процесса: состояние потеряно; выбранная машина рядом по Bluetooth - ждём её, как по ACL_CONNECTED. */
    private fun onStickyRestart() {
        AppLog.i("сервис: перезапущен системой после убийства процесса")
        val mac = CarProfile(this).btMac
        if (!BuildConfig.WIRELESS_BETA || mac == null) {
            WLog.ev(WLog.TRIGGER, "sticky-restart", "resume" to false, "why" to "no-car")
            finish()
            return
        }
        CarBtState.check(this, mac) { near ->
            WLog.ev(WLog.TRIGGER, "sticky-restart", "near" to near)
            if (session != null || wifiBMode || stopRequested) return@check      // пока проверяли, пришла команда (ACL, кабель, Стоп)
            if (near) onCarAuto() else if (host == null && !linkWaiting) finish()
        }
    }

    private fun command(intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            if (sessionMode == BridgeMode.DESKTOP) {
                DesktopMusicPlayback.cancel("explicit Stop")
                cancelDesktopRecovery()
            }
            wifiBMode = false
            autoMode = false      // до следующего ACL_CONNECTED
            autoStarted = false
            cancelAutoOff()
            cancelHint()
            stopCarNet(CarNetEvent.STOP, "stop")
            CarLifeState.wireless = null
            btProbe.stop("стоп")
            pendingTcp6?.abort()
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed увидит stopRequested: AA закрывается сразу
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return
        }
        // автостарт по Bluetooth машины: обрабатываем до проверки session, при идущей сессии нужен только флаг
        if (intent?.action == ACTION_WIFI_B_AUTO) {
            onCarAuto()
            return
        }
        if (intent?.action == ACTION_WIFI_B_AUTO_OFF) {
            onCarAutoOff()
            return
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            val wanted = BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)
            if (wanted != sessionMode) AppLog.i("сервис: режим ${sessionMode.key} -> ${wanted.key}, применится к следующему подключению (текущая сессия не прерывается)")
            return
        }

        // только debug-сборка: вместо USB-аксессуара TCP к эмулятору ГУ на ПК (adb reverse), в release port() всегда null
        val dbgPort = DebugTransport.port(intent)
        if (dbgPort != null) {
            AppLog.i("сервис: debug-транспорт, подключаюсь к 127.0.0.1:$dbgPort")
            Thread({
                val pfd = DebugTransport.connect(dbgPort)
                main.post {
                    if (pfd == null) {
                        CarLifeState.status = "debug TCP: нет соединения"
                        failStart()
                    } else if (session != null) {
                        pfd.close()
                    } else {
                        startSession(StreamTransport.fromPfd(pfd, TransportCaps.TCP_DEBUG, "TCP 127.0.0.1:$dbgPort"))
                    }
                }
            }, "debug-tcp").start()
            return
        }

        // только debug-сборка: беспроводной формат, 6 TCP-сокетов на 127.0.0.1 (adb forward), ГУ подключается сама
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return
        }
        // беспровод, ветка B (хотспот телефона + маяк): кнопка в настройках бета-сборки или debug-вход
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (!BuildConfig.WIRELESS_BETA && !DebugTransport.wifiB(intent)) {
                AppLog.i("сервис: беспровод в этой сборке выключен")
                failStart()
                return
            }
            if (wifiBMode && linkWaiting && !stopRequested) {
                // повторное нажатие, ожидание уже идёт: счётчики accept/TARGET_INFO не сбрасываем (иначе ложный bt-hint, h126)
                autoStarted = false
                WLog.ev(WLog.TRIGGER, "manual-mark", "why" to "waiting")
                return
            }
            wifiBMode = true
            autoStarted = false      // ручной запуск: уход машины по Bluetooth его не гасит
            stopRequested = false
            beginWait()
            startWifiB()
            btProbe.start()      // после WLog.begin в startWifiB: t= в строках WL bt от начала попытки
            return
        }

        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return
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
            return
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        if (wifiBMode || pendingTcp6 != null) {
            // кабель побеждает воздух: ожидание машины по Wi-Fi отменяем; после USB-сессии возобновим, только если
            // машина рядом по Bluetooth и кабеля нет (usbToAir)
            AppLog.i("сервис: кабель подключён, ожидание по Wi-Fi отменяю (USB побеждает)")
            WLog.ev(WLog.LINK, "usb-wins")
            wifiBMode = false
            autoStarted = false
            cancelHint()
            stopCarNet(CarNetEvent.USB_WINS, "usb")
            CarLifeState.wireless = null
            btProbe.stop("кабель")
            pendingTcp6?.abort()
        }
        startSession(StreamTransport.fromPfd(pfd, TransportCaps.USB_AOA, MainActivity.describe(acc)))
    }

    /** debug tcp6: слушать 6 портов на loopback, ждать ГУ (pc-headunit --transport tcp6), затем обычная сессия. */
    private fun startTcp6Debug(bind: String) = startTcp6("tcp6", bind, TCP6_WAIT_MS, beacon = null)

    /**
     * Ветка B (хотспот телефона): маяк «CarlifeHost» на 7999 и ответчик 8999, 6 портов на 0.0.0.0, пускаем только
     * адреса из подсетей, куда идёт маяк. Хотспот включает человек. Пока только debug-вход (`--ez debug_wifi_b true`).
     */
    private fun startWifiB() {
        CarLifeState.wireless = waitingText("…")
        // телефон уже в сети машины (WifiNetworkSpecifier): маяк и ответчик только в ней, сокеты привязаны к этой сети
        val carIface = carNetImpl?.ifaceName
        val carNetwork = if (carIface != null) carNetImpl?.current else null
        val beacon = HotspotBeacon(
            ifaces = { LanIface.scan().let { all -> if (carNetwork != null) all.filter { it.name == carIface } else all } },
            bindNetwork = carNetwork,
            onIfaces = { list ->
                // Wi-Fi Direct-машине хотспот не нужен, а включённый он занимает Wi-Fi Direct (h214): не просим его включить
                CarLifeState.wireless = if (list.isEmpty()) {
                    if (wifiDirectCar) waitingText("Wi-Fi Direct") else getString(R.string.wl_state_no_hotspot)
                }
                else waitingText(list.joinToString(", ") { it.name })
            },
        )
        startTcp6("wifi-b", "0.0.0.0", WIFI_B_WAIT_MS, beacon)
        if (linkWaiting) WLog.ev(WLog.LINK, "car-wifi", "carWifi" to if (CarWifi(this).isSet) "set" else "unset", "bound" to (carNetwork != null))
        if (autoMode && !hintShown && linkWaiting) {
            main.removeCallbacks(hintTick)
            main.postDelayed(hintTick, AUTO_HINT_MS)
        }
        if (linkWaiting) scheduleCarNet()
    }

    /** Новое ожидание по Wi-Fi (авто или ручное): сброс счётчиков и таймер подсказки про Bluetooth. */
    private fun beginWait() {
        waitSince = SystemClock.elapsedRealtime()
        carBtNear = false
        p2pThisWait = false
        carSkipLogged = false
        btAcceptsThisWait = 0
        btHintShown = false
        main.removeCallbacks(btHintTick)
        main.postDelayed(btHintTick, BT_HINT_MS)
    }

    private fun markCarSeen() {
        carSeenAt = SystemClock.elapsedRealtime()
    }

    private fun sinceCarMs(): Long? = if (carSeenAt == 0L) null else SystemClock.elapsedRealtime() - carSeenAt

    /**
     * Запрос сети машины: через [CAR_NET_DELAY_MS] от начала ожидания (если сеть задана), а если машина только что была
     * на связи - сразу ([CarNetPolicy.delayMs]). Wi-Fi Direct-машине не нужен.
     */
    private fun scheduleCarNet() {
        main.removeCallbacks(carNetTick)
        if (!CarWifi(this).isSet) return
        if (wifiDirectCar) {
            logCarSkip()
            return
        }
        val since = sinceCarMs()
        val proven = CarWifi(this).proven
        val delay = CarNetPolicy.delayMs(since, carBtNear, proven)
        if (delay == 0L) WLog.ev(WLog.LINK, "car-net-now", "sinceCarMs" to since, "carBt" to carBtNear, "proven" to proven)
        main.postDelayed(carNetTick, (delay - (SystemClock.elapsedRealtime() - waitSince)).coerceAtLeast(0))
        if (delay > 0L) checkCarBtNear()
    }

    /** Выбранная машина подключена по Bluetooth: она рядом, сеть машины просим сразу. Без выбранной машины не смотрим (наушники). */
    private fun checkCarBtNear() {
        val mac = CarProfile(this).btMac ?: return
        CarBtState.check(this, mac) { near ->
            if (!near || carBtNear || !wifiBMode || stopRequested || !linkWaiting || session != null) return@check
            carBtNear = true
            scheduleCarNet()
        }
    }

    private fun requestCarNet() {
        val w = CarWifi(this)
        val ssid = w.ssid ?: return
        val ok = CarNetPolicy.shouldRequest(
            carWifiSet = w.isSet,
            autoOrManualWaiting = wifiBMode && linkWaiting && !stopRequested,
            sessionActive = session != null,
            usbActive = session?.transportKind == TransportCaps.USB_AOA.kind,
            alreadyRequesting = carNetBusy,
            waitedMs = SystemClock.elapsedRealtime() - waitSince,
            sixOfSixReady = sixReady,
            wifiDirectCar = wifiDirectCar,
            delayMs = CarNetPolicy.delayMs(sinceCarMs(), carBtNear, w.proven),
        )
        if (!ok) return
        carNet.request(ssid, w.pass, CAR_NET_TIMEOUT_MS, ::onCarNetAvailable, ::onCarNetUnavailable, ::onCarNetLost)
    }

    /** Остановка ожидания (или смерть сервиса): снять отложенный запрос и, по [CarNetPolicy], сам запрос сети. */
    private fun stopCarNet(event: CarNetEvent, why: String) {
        main.removeCallbacks(carNetTick)
        restartingForCarNet = false
        if (CarNetPolicy.shouldRelease(event)) carNetImpl?.release(why)
    }

    private fun logCarSkip() {
        if (carSkipLogged) return
        carSkipLogged = true
        WLog.ev(WLog.LINK, "car-skip", "why" to "wifi-direct")
    }

    /** ГУ прислала TARGET_INFO (Wi-Fi Direct): сеть машины не нужна, wifi-подсказка тоже. */
    private fun onBtTargetInfo(mac: String?) {
        if (!wifiBMode || stopRequested) return
        val profile = CarProfile(this)
        // машина выбрана и это она: запоминаем навсегда; не выбрана или чужая - только на это ожидание
        if (CarMac.same(profile.btMac, mac)) profile.connMode = CarConnMode.BT_CARLIFE
        p2pThisWait = true
        main.removeCallbacks(carNetTick)
        if (carNetBusy) carNetImpl?.release("p2p")      // колбэки снимаются без вызовов: onUnavailable/onLost не придут
        if (CarWifi(this).isSet) logCarSkip()
        cancelHint()
    }

    /** discoverPeers вернул BUSY: на Samsung это включённая точка доступа, просим её выключить. */
    private fun onP2pBusy() {
        if (!wifiBMode || stopRequested || !linkWaiting) return
        CarLifeState.wireless = getString(R.string.wl_state_p2p_busy)
    }

    private fun onBtAccept() {
        if (!wifiBMode || stopRequested) return
        btAcceptsThisWait++
        cancelBtHint()
    }

    private fun onCarNetAvailable(@Suppress("UNUSED_PARAMETER") network: Network) {
        markCarSeen()
        restartForCarNet()
    }

    /** Сеть машины появилась или пропала, пока ждём: ожидание заново, чтобы маяк шёл (или перестал идти) через неё. Сессию не трогаем. */
    private fun restartForCarNet() {
        if (!wifiBMode || stopRequested || session != null || sixReady || !linkWaiting || wifiDirectCar) return
        val l = pendingTcp6
        if (l == null) {      // слушатель ещё открывается (или уже отдал результат): повторим, пока ждём
            main.postDelayed({ restartForCarNet() }, 300)
            return
        }
        restartingForCarNet = true
        WLog.ev(WLog.LINK, "rearm", "after" to "car-net")
        l.abort()
    }

    private fun onCarNetUnavailable(reason: String) {
        if (wifiDirectCar) return
        if (autoMode && !hintShown) showWifiHint()      // сети машины не будет: пусть человек подключит Wi-Fi сам
    }

    private fun onCarNetLost() {
        markCarSeen()
        if (!wifiBMode || stopRequested || session != null) return
        restartForCarNet()
        scheduleCarNet()
    }

    /** Статус ожидания; в авторежиме с пометкой, что машина рядом по Bluetooth. */
    private fun waitingText(ifaces: String) = getString(if (autoMode) R.string.wl_state_auto else R.string.wl_state_waiting, ifaces)

    /** ACL_CONNECTED выбранной машины: ждать по Wi-Fi, пока она рядом. Идущую сессию (USB или воздух) не трогаем. */
    private fun onCarAuto() {
        if (!BuildConfig.WIRELESS_BETA) {
            AppLog.i("сервис: беспровод в этой сборке выключен")
            failStart()
            return
        }
        cancelAutoOff()
        autoMode = true
        autoStarted = true
        if (session != null) {
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "session", "kind" to session?.transportKind)
            return
        }
        if (wifiBMode && linkWaiting) {
            // всплеск ACL: ожидание уже идёт. Без RFCOMM и группы перезапускаем пробник - ГУ открывает SPP на свежих слушателях (h211)
            val restart = btProbe.idle()
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "waiting", "btRestart" to restart)
            if (restart) btProbe.restart("ACL вернулся")
            return
        }
        hintShown = false
        wifiBMode = true
        stopRequested = false
        beginWait()
        startWifiB()
        btProbe.start()
        WLog.ev(WLog.TRIGGER, "auto-start")
    }

    /** ACL_DISCONNECTED выбранной машины: гасим не сразу (всплески ACL у ГУ), а через [AUTO_OFF_DEBOUNCE_MS]. */
    private fun onCarAutoOff() {
        if (session == null && host == null && !linkWaiting && !wifiBMode) {
            // сервис поднят с нуля только ради отбоя (процесс убит, ресивер дошёл до пустого сервиса): гасить нечего
            autoMode = false
            finish()
            return
        }
        autoOff.arm()
        main.removeCallbacks(autoOffTick)
        main.postDelayed(autoOffTick, AUTO_OFF_DEBOUNCE_MS)
        WLog.ev(WLog.TRIGGER, "auto-off-armed", "ms" to AUTO_OFF_DEBOUNCE_MS)
    }

    private fun cancelAutoOff() {
        autoOff.cancel()
        main.removeCallbacks(autoOffTick)
    }

    /** Машина ушла по Bluetooth и новый ACL_CONNECTED не пришёл: авторежим выключаем. USB-сессию не трогаем никогда. */
    private fun applyAutoOff() {
        autoMode = false
        cancelHint()
        if (session != null) {
            // сессия идёт (воздух: ГУ может держать Wi-Fi дольше Bluetooth; кабель - не наше дело); после её конца ожидание не возобновим
            WLog.ev(WLog.TRIGGER, "auto-off", "session" to true)
            return
        }
        if (wifiBMode && autoStarted) {
            // порядок важен: режим выключен до abort, ветка отказа в startTcp6 увидит wifiBMode=false
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            pendingTcp6?.abort()
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to true)
            failStart()
        } else {
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to false)
        }
    }

    /**
     * Через [USB_TO_AIR_MS] после конца USB-сессии: кабеля нет (на G6S EIO часто тут же переподключает аксессуар),
     * машина рядом по Bluetooth - уведомление «пробую по воздуху» и ожидание, как по ACL_CONNECTED.
     */
    private fun usbToAir() {
        usbToAirPending = false
        if (session != null || wifiBMode || !autoMode || stopRequested) {
            if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
            return
        }
        if (getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true) {
            WLog.ev(WLog.LINK, "usb-to-air", "skip" to "кабель на месте")
            if (session == null && host == null) finish()
            return
        }
        WLog.ev(WLog.LINK, "usb-to-air")
        AppLog.i("сервис: кабель отключён, машина рядом по Bluetooth - жду её по воздуху")
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_usb_to_air_title))
            .setContentText(getString(R.string.wl_usb_to_air_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(USB_TO_AIR_NOTIF_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(USB_TO_AIR_NOTIF_ID, n)
        onCarAuto()
    }

    /** Снять обе подсказки ожидания (про Wi-Fi и про Bluetooth) и их таймеры. */
    private fun cancelHint() {
        main.removeCallbacks(hintTick)
        getSystemService(NotificationManager::class.java).cancel(HINT_NOTIF_ID)
        cancelBtHint()
    }

    private fun cancelBtHint() {
        main.removeCallbacks(btHintTick)
        getSystemService(NotificationManager::class.java).cancel(BT_HINT_NOTIF_ID)
        cancelWifiOff()
    }

    private fun cancelWifiOff() {
        if (!wifiOffShown) return
        wifiOffShown = false
        getSystemService(NotificationManager::class.java).cancel(WIFI_OFF_NOTIF_ID)
    }

    /**
     * Вход в группу ГУ упёрся в выключенный Wi-Fi (Android держит P2P выключенным, discover отвечает BUSY): просим включить
     * Wi-Fi и выключить точку доступа. Wi-Fi включили - снимаем просьбу, P2pJoin входит сам. Нажатие открывает панель Wi-Fi.
     */
    private fun onP2pWifi(on: Boolean) {
        if (on) {
            if (CarLifeState.wireless == getString(R.string.wl_state_wifi_off)) CarLifeState.wireless = waitingText("…")
            cancelWifiOff()
            return
        }
        if (!wifiBMode || stopRequested || session != null) return
        wifiOffShown = true
        CarLifeState.wireless = getString(R.string.wl_state_wifi_off)
        val open = PendingIntent.getActivity(
            this, 4, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_wifi_off_title))
            .setContentText(getString(R.string.wl_wifi_off_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.wl_wifi_off_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WIFI_OFF_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-off-hint")
    }

    /** 30 с ожидания в авторежиме, а 6/6 нет: ГУ, возможно, не в сети телефона. Нажатие открывает панель Wi-Fi. */
    private fun showWifiHint() {
        if (!autoMode || !wifiBMode || !linkWaiting || session != null || hintShown) return
        if (wifiDirectCar) return      // Wi-Fi Direct: телефон сам входит в группу ГУ, Wi-Fi подключать не нужно
        // сеть машины запрашивается или уже получена: человеку подключать Wi-Fi не нужно, подсказка придёт по onUnavailable
        if (carNetBusy) return
        hintShown = true
        val open = PendingIntent.getActivity(
            this, 2, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_hint_title))
            .setContentText(getString(R.string.wl_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(HINT_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-hint")
    }

    /** 15 с ожидания, а ГУ к RFCOMM `carlife` не подключалась: если телефон с машиной по Bluetooth, просим переподключить его. */
    private fun checkBtHint() {
        if (!wifiBMode || stopRequested || session != null || btHintShown || btAcceptsThisWait > 0 || p2pThisWait) return
        CarBtState.check(this, CarProfile(this).btMac) { carBt ->
            val mode = CarProfile(this).connMode
            val show = BtReconnectHint.shouldShow(
                waiting = wifiBMode && linkWaiting && !stopRequested,
                sessionActive = session != null,
                waitedMs = SystemClock.elapsedRealtime() - waitSince,
                acceptsThisWait = btAcceptsThisWait,
                targetInfoThisWait = p2pThisWait,
                carBtConnected = carBt,
                mode = mode,
                alreadyShown = btHintShown,
            )
            if (show) showBtHint(carBt, mode)
        }
    }

    /** Нажатие открывает настройки Bluetooth. */
    private fun showBtHint(carBt: Boolean, mode: CarConnMode) {
        btHintShown = true
        val open = PendingIntent.getActivity(
            this, 3, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_bt_hint_title))
            .setContentText(getString(R.string.wl_bt_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(BT_HINT_NOTIF_ID, n)
        CarLifeState.btProbe = "машина подключена по Bluetooth, но carlife не открыла: переподключите Bluetooth"
        WLog.ev(WLog.TRIGGER, "bt-hint", "carBt" to carBt, "mode" to mode.name)
    }

    private fun startTcp6(kind: String, bind: String, waitMs: Long, beacon: HotspotBeacon?) {
        if (linkWaiting) {
            AppLog.i("сервис: $kind: уже жду ГУ, повторный старт игнорирую")
            return
        }
        linkWaiting = true
        sixReady = false
        WLog.begin(kind, "bind" to bind, "ACCESS_LOCAL_NETWORK" to NetProbe.localNetworkGranted(this))
        AppLog.i("сервис: $kind, слушаю 6 портов на $bind, жду ГУ ${waitMs / 1000} с")
        CarLifeState.status = "$kind: жду ГУ"
        val waitStart = SystemClock.elapsedRealtime()
        Thread({
            var hotspotPeer = false      // 6/6 собрала не P2P-группа ГУ, а подсеть маяка
            val result: Any = try {
                val peerOk: (java.net.InetAddress) -> Boolean =
                    if (beacon == null) { _ -> true } else { a -> beacon.current.any { it.contains(a) } || btProbe.ownsPeer(a) }
                val l = Tcp6Listener(java.net.InetAddress.getByName(bind), peerOk = peerOk).open()
                pendingTcp6 = l
                beacon?.start()
                l.await(waitMs, "$kind $bind").also { hotspotPeer = beacon != null && l.peerAddress?.let { !btProbe.ownsPeer(it) } == true }
            } catch (e: Exception) {
                e
            } finally {
                pendingTcp6 = null
            }
            beacon?.stop(if (result is CarTransport) "6/6" else "нет ГУ")
            main.post {
                linkWaiting = false
                val restarting = restartingForCarNet
                restartingForCarNet = false
                when {
                    result is CarTransport && session != null -> result.close()
                    result is CarTransport -> {
                        sixReady = true
                        // подключение без Wi-Fi Direct: этой машине P2P не нужен (если в этом ожидании TARGET_INFO был, режим не трогаем)
                        if (hotspotPeer && !p2pThisWait) CarProfile(this).connMode = CarConnMode.HOTSPOT
                        // 6/6 пришли из сети машины (маяк ходил только в ней): дальше просим её сразу, без CAR_NET_DELAY_MS
                        if (hotspotPeer && carNetImpl?.current != null) CarWifi(this).let { w ->
                            if (w.isSet && !w.proven) {
                                w.markProven()
                                WLog.ev(WLog.LINK, "car-net-proven")
                            }
                        }
                        if (beacon != null) {
                            cancelHint()      // 6/6: подсказка про Wi-Fi больше не нужна
                            CarLifeState.wireless = getString(R.string.wl_state_connected)
                        }
                        startSession(result)
                    }
                    // abort ради нового маяка в сети машины: не сдача, режим и статус не трогаем, через паузу ждём заново
                    restarting && beacon != null && wifiBMode && !stopRequested && session == null -> {
                        main.postDelayed({
                            if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                        }, WIFI_B_REARM_MS)
                    }
                    else -> {
                        val msg = (result as Exception).message
                        if (result !is LinkSetupException) AppLog.e(kind, result)
                        AppLog.i("сервис: $msg")
                        // авторежим: машина рядом по Bluetooth, таймаут не сдача, а новый круг ожидания. Отказ раньше срока
                        // (порт занят и т.п.) сюда не попадает: иначе цикл раз в 1,5 с
                        val timedOut = SystemClock.elapsedRealtime() - waitStart >= waitMs
                        if (beacon != null && wifiBMode && autoMode && !stopRequested && session == null && timedOut) {
                            WLog.ev(WLog.LINK, "rearm", "after" to "timeout-auto")
                            main.postDelayed({
                                if (wifiBMode && autoMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                            }, WIFI_B_REARM_MS)
                            return@post
                        }
                        if (beacon != null && wifiBMode) {
                            wifiBMode = false
                            autoStarted = false
                            cancelHint()
                            stopCarNet(CarNetEvent.AUTO_OFF, "gave-up")      // ожидание закончилось: сеть машины больше не нужна
                            btProbe.stop("не дождались")
                            CarLifeState.wireless = getString(R.string.wl_state_gave_up, (WIFI_B_WAIT_MS / 60_000).toInt())
                        }
                        // отмена ожидания кабелем: статус уже USB-сессии, не перетираем
                        if (session == null) CarLifeState.status = msg ?: "$kind: ошибка"
                        failStart()
                    }
                }
            }
        }, "link-$kind").start()
    }

    private fun startSession(transport: CarTransport) {
        main.removeCallbacks(usbToAirTick)
        usbToAirPending = false
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        main.removeCallbacks(carNetTick)      // сессия идёт: новых запросов сети машины нет (сам запрос держим, CarNetEvent.SESSION_START)
        cancelBtHint()
        CarLifeState.accessory = transport.label
        CarLifeState.wirelessSession = transport.caps.kind == TransportCaps.TCP6.kind
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

        // режим моста снимается на подключение; DESKTOP на Android ниже 13 трактуется как Android Auto
        val wantedMode = BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)
        if (wantedMode == BridgeMode.DESKTOP && desktopNotActivated(transport)) return
        reconcileHostMode(wantedMode)
        sessionMode = wantedMode
        DexState.desktopSession = wantedMode == BridgeMode.DESKTOP
        if (wantedMode == BridgeMode.DESKTOP) {
            startDesktopSession(transport)
            return
        }

        val aaPrefs = AaPrefs(this)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, aaPrefs.micVrStatus, aaPrefs.fpsSetting,
            metaMusic = aaPrefs.metaMusic, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // AA-сессия к серверу ГУ на 127.0.0.1:5277 живёт на уровне сервиса (BridgeHost), а не этой USB-сессии
        var h = host
        // режим фиксируется на подключение (SDR уже ушёл). Сервер ГУ одноразовый, а перезапустить его теперь некому
        // (службы спецвозможностей нет), поэтому живую AA с другой геометрией или DPI не закрываем: работаем со старым режимом
        val chosenGeometry = aaPrefs.videoGeometry
        val wantMode = chosenGeometry.effective(aaPrefs.transcode)
        val wantDensity = aaPrefs.aaDensity
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (${h.core.videoMode.label} -> ${wantMode.label}), применится после перезапуска сервера ГУ")
        } else if (h != null && !h.isDead && h.core.densityOverride != wantDensity) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (DPI ${h.core.densityOverride} -> $wantDensity, 0 = авто), применится после перезапуска сервера ГУ")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, chosenGeometry, wantMode, wantDensity, previous = h)
            host = h
            if (h == null) {
                // нет сертификата head unit или мост не собрался: причина уже в AaState.setupError, USB-сессию не открываем
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("BRIDGE: AA-хост уже есть, переиспользую (grace=${h.isInGrace})")
        }
        // перекодирование - по настройке; прямой проброс по воздуху тоже разрешён: битрейт AA не регулируется, ГУ восстанавливается
        // нашим IDR. Режим хоста не трогаем (без перекодирования он уже R720, его можно перекодировать), хост не пересоздаём:
        // сервер AA одноразовый. Старый режим живого хоста с кропом (WIDE*, R1024) в passthrough живёт только с SPS-кропом (h529):
        // если область масштабируется (WIDE_976, своё с растяжением) - перекодирование.
        val hostMode = h.core.videoMode
        val transcode = aaPrefs.transcode || (hostMode.needsTranscode && !hostMode.spsCropable)
        if (h.core.transcodeEnabled != transcode) {
            AppLog.i("BRIDGE видео: ${if (transcode) "перекодирование включаю принудительно (режим хоста ${h.core.videoMode.label})" else "возвращаю passthrough по настройке"}")
            h.core.transcodeEnabled = transcode
        }
        if (!transcode && transport.caps == TransportCaps.TCP6) AppLog.i("BRIDGE видео: воздух, прямой проброс${if (aaPrefs.ownIdr) " со своим IDR" else ""} (битрейт не регулируется)")
        // свой IDR только в passthrough: при перекодировании его нет; выключается галкой для разработчиков
        val spliceMs = if (transcode || !aaPrefs.ownIdr) 0L else IDR_SPLICE_MS
        if (h.core.spliceMs != spliceMs) {
            AppLog.i("BRIDGE свой IDR в passthrough: ${if (spliceMs > 0) "раз в $spliceMs мс" else if (transcode) "выключен (перекодирование)" else "выключен в настройках для разработчиков"}")
            h.core.spliceMs = spliceMs
        }
        h.core.carVwId = aaPrefs.carPreset == CarPreset.VW_ID_UNYX
        h.core.phoneAssistant = null   // режим AA: кнопка голоса идёт в AA
        DesktopKeys.voiceStatus = null
        acquireSessionWakeLocks()
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core, PhoneThermal(this), PhoneCalls(this, aaPrefs.callsToHu), onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) }) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
    }

    /**
     * Живой хост (grace после обрыва USB) другого режима новой сессии не годится: закрываем его, новый хост создастся
     * под нужный режим и дождётся закрытия прежнего (previous). Хост того же режима не трогаем.
     */
    private fun reconcileHostMode(wanted: BridgeMode) {
        val h = host
        if (h != null && !h.isDead && !BridgeMode.hostReusable(hostMode, wanted)) {
            AppLog.i("BRIDGE: режим сменился (${hostMode.key} -> ${wanted.key}), прежний хост закрываю")
            h.shutdown("смена режима")
        }
        hostMode = wanted
    }

    /** Режим, под который сейчас работает сервис: идущая сессия держит свой, иначе берём настройку. */
    private fun foregroundMode(): BridgeMode =
        if (session != null) sessionMode else BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)

    /**
     * Режим DESKTOP без активации с ПК: сессия не стартует, причина в логе, статусе и отдельном уведомлении
     * (foreground-уведомление уйдёт вместе с сервисом). true = старт отменён.
     */
    private fun desktopNotActivated(transport: CarTransport): Boolean {
        if (DesktopAccess.isReady(this)) return false
        val msg = getString(R.string.desk_not_activated)
        AppLog.i("DESKTOP: сессия не стартует, доступ не активирован (${DelegatedDesktopAccess.status(this)}): $msg")
        DexState.error = msg
        CarLifeState.status = msg
        transport.close()
        val open = PendingIntent.getActivity(
            this, 6, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.desk_notif_title))
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DESKTOP_NOT_ACTIVATED_NOTIF_ID, n)
        failStart()
        return true
    }

    /** Сессия в режиме DESKTOP (DelegatedDexConnector вместо AA): видео с виртуального дисплея, CarLife-звук по настройке. */
    private fun startDesktopSession(transport: CarTransport) {
        getSystemService(NotificationManager::class.java).cancel(DESKTOP_NOT_ACTIVATED_NOTIF_ID)
        desktopUsed = true
        val isUsb = transport.caps.kind == TransportCaps.USB_AOA.kind
        lastSessionWasUsb = isUsb
        physicalUsbDetached = false
        DexState.error = null

        val aaPrefs = AaPrefs(this)
        val dexPrefs = DexPrefs(this)
        // размер дисплея - то же разрешение, что выбрано в разделе «Видео»
        val desktopVideo = DesktopVideoSelection.forAaVideo(aaPrefs.videoGeometry.effective(aaPrefs.transcode), dexPrefs.desktopDensity)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, false, dexPrefs.desktopFps,
            // ПРОБНИК: навигация из теневой AA-сессии (AaShadowNav), без неё подписку ГУ просто никто не кормит
            metaMusic = false, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6, desktopVideo = desktopVideo,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // новое физическое подключение: бюджет восстановления заново; восстановление его не обновляет
        cancelDesktopRecovery()
        desktopRecovery.newConnection()
        // источник рабочего стола переживает короткий обрыв USB в grace BridgeHost
        var h = host
        val wantGeometry: VideoGeometry = desktopVideo.initialGeometry()
        val wantDensity = desktopVideo.densityFor(wantGeometry)
        if (h == null || h.isDead) {
            h = createDesktopHost(aaPrefs, wantGeometry, wantDensity, previous = h)
            host = h
            if (h == null) {
                // источник не собрался: открытый USB без видео оставлять нельзя
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("DEX: источник уже есть, переиспользую (grace=${h.isInGrace})")
        }
        val owner: BridgeHost = h
        // темп кадров CarLife, повтор статичных кадров и энкодер остаются как в основном видеоконвейере
        owner.core.transcodeEnabled = true
        owner.core.desktopTranscode = dexPrefs.desktopTranscode
        owner.core.spliceMs = 0L
        owner.core.carVwId = aaPrefs.carPreset == CarPreset.VW_ID_UNYX
        // кнопка голоса: системный помощник клавишей KEYCODE_ASSIST (как долгое нажатие питания), см. createDesktopHost
        acquireSessionWakeLocks()
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, owner.core, PhoneThermal(this), CallStateSource.NONE, onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) }) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        // tcp-debug есть только в debug/smoke (эмулятор ГУ): для звука считаем его USB, чтобы проверять захват на стенде
        val audioUsb = isUsb || transport.caps === TransportCaps.TCP_DEBUG
        DesktopMusicPlayback.attach(applicationContext, this, s, audioUsb,
            ready = { session === s && DexState.running && s.hasFreshDesktopVideo() },
            audioReady = { !DexPrefs(this).usbPlaybackAudio || !owner.core.audioViaUsb || DesktopAudioState.running })
        // h658: по Wi-Fi (tcp6) звук CarLife в режиме AA ходит, а BT A2DP ГУ рвёт сама — захват рабочего стола тоже пускаем в tcp6
        val desktopAudioLink = audioUsb || transport.caps === TransportCaps.TCP6
        desktopAudio.attach(owner.core, desktopAudioLink) { session === s && s.canCaptureDesktopAudio() }
        s.start()
        owner.onUsbSessionStarted()
    }

    /** Режим DESKTOP использует видеоконвейер CarLife; сертификаты Android Auto не нужны. */
    private fun createDesktopHost(
        aaPrefs: AaPrefs, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?, recoveredCore: AaBridgeCore? = null,
    ): BridgeHost? {
        return try {
            AaState.setupError = null
            AaState.reset()
            AaState.cert = getString(R.string.desk_cert_na)
            AaState.status = "рабочий стол: подключение"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = recoveredCore ?: AaBridgeCore(
                log,
                clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 },
                connectingText = getString(R.string.desk_connecting),
                waitingText = getString(R.string.desk_waiting_frame),
                disconnectedText = getString(R.string.desk_disconnected),
            )
            core.micModeProvider = { MicMode.CAR }
            core.phoneMic = null
            core.usbAudioEnabled = false
            core.mapHomeKey = true
            core.desktopInputEnabled = true
            core.volumeSwipeProvider = { aaPrefs.volumeSwipe }
            DesktopNavHints.target = core   // подсказки навигатора рабочего стола (свой хост Car App, теневая AA) в MetaRelay
            com.yegorkapomidorka.headswap.dex.DesktopMediaMeta.start(this, core)   // трек плеера телефона на приборку (h1054)
            // в машине adb нет: стартуем вместе с рабочим столом; у навигатора выключены подсказки на приборке - AA не трогаем (h800).
            // Действующий навигатор как у лаунчера: сохранённый, пока установлен, иначе первый известный (LauncherModel.init
            // зовётся один раз на процесс - на вторую сессию рабочего стола его applyShadowNav не придёт)
            val lp = com.yegorkapomidorka.headswap.launcher.LauncherPrefs(this)
            val pick = com.yegorkapomidorka.headswap.launcher.NavigatorPick
            val arabic = AppLocale.usesGlobalApps(this)
            val installed = pick.known(arabic).filterTo(HashSet()) { packageManager.getLaunchIntentForPackage(it) != null }
            if (pick.shadowWanted(aaPrefs.metaNavi, pick.effective(lp.navigator, installed, arabic), lp::navHints)) AaShadowNav.start(this)
            else if (aaPrefs.metaNavi) AppLog.i("SHADOWNAV не стартую: у навигатора выключены подсказки на приборке", false)
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.dupDownFilterProvider = { aaPrefs.dupDownFilter }
            core.transcodeEnabled = true
            core.refDecoderFactory = { sink -> RefDecoder(sink) { AppLog.i(it, false) } }
            core.videoMode = mode
            core.densityOverride = densityOverride
            core.marginsAtBottom = false
            core.spliceMs = 0L
            core.expectFeatureConfig = false
            val ref = arrayOfNulls<BridgeHost>(1)
            val conn = DelegatedDexConnector(applicationContext, core) { reason ->
                main.post { ref[0]?.let { onDesktopSourceFailed(it, reason) } }
            }
            // кнопка голоса: панель Алисы в своём лаунчере (DesktopKeys); лаунчер не на экране или чужой - Алиса на весь
            // дисплей рабочего стола (dex/PhoneAssistant, ADR 0026). Лямбда зовётся не с главного потока
            core.phoneAssistant = {
                if (DesktopKeys.sink?.voice() != true) {
                    AppLog.i("ASSIST панель не взята лаунчером, Алиса на весь дисплей")
                    main.post { PhoneAssistant.launch(applicationContext, DexState.displayId) }
                }
            }
            DesktopKeys.voiceStatus = { on -> core.desktopVoiceStatus(on) }
            AppLog.i("DEX: видео ${mode.outW}x${mode.outH} через CarLife; звук по USB по настройке; микрофон CarLife не передаётся")
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post { ref[0]?.let(::onDesktopHostTornDown) }
            })
            ref[0] = h
            h
        } catch (e: Exception) {
            AppLog.e("DEX: мост не стартовал", e)
            DexState.error = "${e.javaClass.simpleName}: ${e.message}"
            AaState.setupError = DexState.error
            AaState.status = "ошибка: рабочий стол не стартовал"
            null
        }
    }

    /** Всё состояние восстановления принадлежит сервису, а не Activity. */
    private fun canRecoverDesktop(current: CarLifeSession): Boolean = runCatching {
        alive && !stopRequested && session === current &&
            current.transportKind == TransportCaps.USB_AOA.kind && DesktopAccess.isReady(this) &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            getSystemService(UsbManager::class.java).let { usb ->
                usb.accessoryList.orEmpty().any { usb.hasPermission(it) }
            }
    }.getOrDefault(false)

    private fun onDesktopSourceFailed(owner: BridgeHost, reason: DesktopFailure) {
        if (host !== owner) {
            // переподключение могло уже заменить завершённый хост: закрываем только защёлку старого, её может ждать преемник
            owner.shutdown("завершение предыдущего источника")
            return
        }
        val current = session
        val attempt = desktopRecovery.begin(reason == DesktopFailure.HELPER_UNAVAILABLE && current != null && canRecoverDesktop(current))
        if (attempt != null && current != null) {
            pendingDesktopRecovery = Recovery(attempt, current, owner)
            AppLog.i("DESKTOP recovery planned attempt=${attempt.number}/2; server ready, keep CarLife transport", false)
        }
        // TerminalSource не даёт переиспользовать хост в grace ещё до этого колбэка
        owner.shutdown("источник рабочего стола завершился")
    }

    private fun onDesktopHostTornDown(owner: BridgeHost) {
        if (host === owner) host = null
        AppLog.i("DEX: источник закрыт")
        AaShadowNav.stop()
        CarAppHost.stop()
        DesktopNavHints.target = null
        com.yegorkapomidorka.headswap.dex.DesktopMediaMeta.stop()
        val recovery = pendingDesktopRecovery
        if (recovery != null && recovery.owner === owner && desktopRecovery.isCurrent(recovery.attempt)) {
            // колбэк подтверждает закрытие и привилегированного производителя, и энкодера с Surface
            DexState.error = null
            DexState.status = getString(R.string.desk_recovering, recovery.attempt.number)
            main.postDelayed({ recoverDesktop(recovery) }, recovery.attempt.delayMs)
        }
        if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
    }

    private fun recoverDesktop(recovery: Recovery) {
        if (pendingDesktopRecovery !== recovery || !desktopRecovery.consume(recovery.attempt)) return
        pendingDesktopRecovery = null
        if (host != null || session !== recovery.session || !recovery.owner.isTornDown) return
        // после паузы проверяем заново: Стоп, отключение, отозванный доступ, смерть сервера или блокировка отменяют восстановление
        if (!canRecoverDesktop(recovery.session)) {
            DexState.error = getString(R.string.desk_recovery_stopped)
            AppLog.i("DESKTOP recovery cancelled: current session/access no longer eligible", false)
            return
        }
        val core = recovery.owner.core
        if (!core.rearmDesktopSurfaceAfterStop()) {
            DexState.error = getString(R.string.desk_recovery_screen_busy)
            return
        }
        val replacement = createDesktopHost(AaPrefs(this), core.videoMode, core.densityOverride,
            previous = recovery.owner, recoveredCore = core) ?: return
        host = replacement
        AppLog.i("DESKTOP recovery bind attempt=${recovery.attempt.number}/2; fresh producer and encoder generation", false)
        replacement.onUsbSessionStarted()
    }

    private fun cancelDesktopRecovery() {
        pendingDesktopRecovery = null
        desktopRecovery.cancel()
    }

    /** Закрытие сессии режима DESKTOP: физический detach USB, звук, музыка, восстановление. */
    private fun onDesktopSessionClosing(s: CarLifeSession) {
        if (!stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
            observePhysicalUsbDetach("USB session closed and accessory absent")
        }
        desktopAudioImpl?.detach()
        DesktopMusicPlayback.linkClosed(s)
        cancelDesktopRecovery()
    }

    /** Новый AA-хост: ядро, коннектор, сертификаты. null = мост не поднялся (причина в AaState.setupError). */
    private fun createHost(aaPrefs: AaPrefs, chosen: VideoGeometry, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?): BridgeHost? {
        return try {
            AaShadowNav.stop()      // ПРОБНИК: одноразовый сервер 5277 нужен самому мосту AA
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            val audio = getSystemService(AudioManager::class.java)
            core.phoneCallProvider = {
                when (audio?.mode) {
                    AudioManager.MODE_RINGTONE -> PhoneCall.RINGING
                    AudioManager.MODE_IN_CALL -> PhoneCall.ACTIVE
                    else -> PhoneCall.IDLE                  // IN_COMMUNICATION не берём: так же выглядит голос ассистента
                }
            }
            core.musicRateProvider = { aaPrefs.musicRate }
            core.mediaChunkProvider = { aaPrefs.mediaChunk }
            core.voiceChunkProvider = { aaPrefs.voiceChunk }
            core.ttsPrerollProvider = { aaPrefs.ttsPreroll }
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.dupDownFilterProvider = { aaPrefs.dupDownFilter }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            EqLive.settings = aaPrefs.eq
            core.transcodeEnabled = aaPrefs.transcode
            core.refDecoderFactory = { sink -> RefDecoder(sink) { AppLog.i(it, false) } }
            core.videoMode = mode
            core.densityOverride = densityOverride
            // h529: поля справа/снизу только для SPS-кропа в прямом проброске; транскодер режет центр
            val bottom = !aaPrefs.transcode && mode.spsCropable
            core.marginsAtBottom = bottom
            if (bottom) AppLog.i("BRIDGE SPS-кроп: поля ${mode.marginW} px справа и ${mode.marginH} px снизу (ui_config.margins), интерфейс в левом верхнем углу")
            core.expectFeatureConfig = true
            if (chosen != mode) AppLog.i("BRIDGE видео-режим ${chosen.label} недоступен без перекодирования, использую ${mode.label}")
            // аудио-синки (MEDIA/SPEECH) объявляются в момент запроса телефона, когда уже известно решение по звуку
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb, densityOverride = densityOverride, carMake = aaPrefs.carMake, marginsAtBottom = bottom) }) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
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
        if (foregroundMode() == BridgeMode.DESKTOP) {
            // Headswap Desktop не снимает микрофон ни телефона, ни ГУ: только connectedDevice
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            return
        }
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

    /** Старт не удался: если жив AA-хост (идёт grace), сервис не гасим, иначе finish. */
    private fun failStart() {
        if (linkWaiting) return      // идёт ожидание машины по воздуху: сервис нужен
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        if (sessionMode == BridgeMode.DESKTOP) onDesktopSessionClosing(s)
        session = null
        markCarSeen()
        CarLifeState.wirelessSession = false
        if (wifiBMode && autoStarted && !autoMode && !stopRequested) {
            // машина ушла по Bluetooth, пока шла беспроводная сессия (ГУ держала Wi-Fi): ожидание заново не начинаем
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            WLog.ev(WLog.TRIGGER, "auto-off", "after" to "session")
        }
        if (!wifiBMode && autoMode && !stopRequested && BuildConfig.WIRELESS_BETA && s.transportKind == TransportCaps.USB_AOA.kind) {
            // кабель выдернули, а машина рядом по Bluetooth: новый ACL не придёт, ждать по воздуху начнём сами (h214)
            usbToAirPending = true
            main.removeCallbacks(usbToAirTick)
            main.postDelayed(usbToAirTick, USB_TO_AIR_MS)
            WLog.ev(WLog.LINK, "usb-to-air-armed", "ms" to USB_TO_AIR_MS)
        }
        val h = host
        if (h == null) {
            if ((!wifiBMode && !usbToAirPending) || stopRequested) finish()
        } else {
            // обрыв USB: CarLife закрыт, AA держим graceMs (или закрываем сразу, если Стоп)
            h.onUsbSessionClosed(immediate = stopRequested)
            if (sessionMode == BridgeMode.DESKTOP && !stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
                // часть прошивок обновляет accessoryList чуть позже сообщения об ошибке чтения
                for (delay in longArrayOf(250, 1000)) main.postDelayed({
                    if (!stopRequested && session == null && host === h) observePhysicalUsbDetach("post-close USB absence check")
                }, delay)
            }
        }
        if (wifiBMode && !stopRequested) {
            // беспровод: ГУ ушла (обрыв Wi-Fi, выключили машину) - снова маяк и ожидание, AA пока тёплая (grace)
            CarLifeState.wireless = getString(R.string.wl_state_lost)
            WLog.ev(WLog.LINK, "rearm", "after" to reason)
            main.postDelayed({
                if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
            }, WIFI_B_REARM_MS)
        }
    }

    /** Partial wake lock: keep the bridge and the encoders running while the session is live. */
    private fun acquireSessionWakeLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseSessionWakeLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun finish() {
        if (desktopUsed) cancelDesktopRecovery()
        stopCarNet(CarNetEvent.STOP, "finish")
        CarLifeState.running = false
        releaseSessionWakeLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        alive = false
        autoMode = false
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.DESTROY, "destroy")
        pendingTcp6?.abort()
        btProbe.stop("сервис уничтожен")
        audioDiag.stop()
        if (desktopUsed) {
            cancelDesktopRecovery()
            desktopAudioImpl?.close()
            DesktopMusicPlayback.serviceDestroyed(this, physicalUsbDetached)
        }
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        releaseSessionWakeLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** Режим DESKTOP: показать рабочий стол (кнопка HOME для идущей сессии). Только к живой сессии, сервис сам не поднимает. */
        const val ACTION_DESKTOP_HOME = "com.yegorkapomidorka.headswap.DESKTOP_HOME"
        /** Режим DESKTOP: CarLife GO_TO_DESKTOP - ГУ показывает своё меню. Только к живой сессии. */
        const val ACTION_HU_DESKTOP = "com.yegorkapomidorka.headswap.HU_DESKTOP"
        /** adb shell am startservice -a com.yegorkapomidorka.headswap.LAUNCHER_DEBUG --es area settings --es page music --es theme light --es map left */
        const val ACTION_LAUNCHER_DEBUG = "com.yegorkapomidorka.headswap.LAUNCHER_DEBUG"
        /** Беспровод (бета): ждать машину по Wi-Fi, ветка B (хотспот телефона + маяк). */
        const val ACTION_WIFI_B = "com.yegorkapomidorka.headswap.WIFI_B"
        /** Выбранная машина подключилась по Bluetooth (ACL_CONNECTED): ждать по Wi-Fi, пока она рядом. */
        const val ACTION_WIFI_B_AUTO = "com.yegorkapomidorka.headswap.WIFI_B_AUTO"
        /** Машина отключилась по Bluetooth (ACL_DISCONNECTED): через debounce снять ожидание. */
        const val ACTION_WIFI_B_AUTO_OFF = "com.yegorkapomidorka.headswap.WIFI_B_AUTO_OFF"
        private const val CHANNEL = "bridge"
        private const val IDR_SPLICE_MS = 2000L             // период своего IDR в passthrough
        private const val NOTIF_ID = 1
        private const val HINT_NOTIF_ID = 2
        private const val USB_TO_AIR_NOTIF_ID = 5
        /** Пауза после конца USB-сессии перед ожиданием по воздуху: успеть увидеть переподключение аксессуара. */
        private const val USB_TO_AIR_MS = 3_000L
        private const val USB_TO_AIR_NOTIF_MS = 60_000L
        private const val BT_HINT_NOTIF_ID = 3
        private const val WIFI_OFF_NOTIF_ID = 4
        private const val DESKTOP_NOT_ACTIVATED_NOTIF_ID = 6

        /** Из UI (приложение на экране): startService, не startForegroundService, чтобы не требовать startForeground для команды без сессии. */
        fun requestDesktopHome(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_DESKTOP_HOME))
        }

        /** Лаунчер рабочего стола: выйти в меню ГУ. Без живого сервиса не шлём - нечего будить. */
        fun requestHuDesktop(ctx: Context) {
            if (!alive) return
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_HU_DESKTOP))
        }

        /** Сервис создан и не уничтожен (один процесс): ресиверу ACL незачем будить пустой сервис ради отбоя. */
        @Volatile var alive = false
            private set
        /** debug tcp6: сколько ждать все 6 сокетов ГУ. */
        private const val TCP6_WAIT_MS = 60_000L
        /** Ветка B: сколько маячить и ждать ГУ (человек включает хотспот и открывает CarLife на ГУ). */
        private const val WIFI_B_WAIT_MS = 10 * 60_000L
        /** Пауза перед новым ожиданием после обрыва беспроводной сессии. */
        private const val WIFI_B_REARM_MS = 1_500L
        /** Авторежим: сколько ждать нового ACL_CONNECTED после ACL_DISCONNECTED, прежде чем гасить ожидание. */
        private const val AUTO_OFF_DEBOUNCE_MS = 10_000L
        /** Авторежим: через сколько ожидания без 6/6 показать подсказку про Wi-Fi. */
        private const val AUTO_HINT_MS = 30_000L
    }
}
