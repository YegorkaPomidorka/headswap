package com.yegorkapomidorka.headswap

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
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.EqLive
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.ServerPhase
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.autosrv.AutoServerPolicy
import com.yegorkapomidorka.headswap.autosrv.Trigger
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
    /** debug tcp6: слушатель ждёт 6/6 (нить debug-tcp6); Стоп его отменяет. */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    /** Идёт подъём tcp6/беспровода (от старта до 6/6 или отказа); сервис в это время не гасим. Только главная нить. */
    private var linkWaiting = false
    /** Беспровод (бета, ветка B) включён пользователем: после обрыва снова ждём машину. Пишет главная нить, читает и коннектор (автозапуск сервера AA). */
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
    /** Автозапуск пропущен из-за неподключённой службы спецвозможностей: RETRY запустит его, когда она появится. */
    @Volatile private var serverStartPending = false
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                // беспроводную сессию отключение USB-аксессуара не касается
                session?.takeIf { it.transportKind == TransportCaps.USB_AOA.kind }?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
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
            return START_NOT_STICKY
        }
        // автостарт по Bluetooth машины: обрабатываем до проверки session, при идущей сессии нужен только флаг
        if (intent?.action == ACTION_WIFI_B_AUTO) {
            onCarAuto()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WIFI_B_AUTO_OFF) {
            onCarAutoOff()
            return START_NOT_STICKY
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            return START_NOT_STICKY
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
            return START_NOT_STICKY
        }

        // только debug-сборка: беспроводной формат, 6 TCP-сокетов на 127.0.0.1 (adb forward), ГУ подключается сама
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return START_NOT_STICKY
        }
        // беспровод, ветка B (хотспот телефона + маяк): кнопка в настройках бета-сборки или debug-вход
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (!BuildConfig.WIRELESS_BETA && !DebugTransport.wifiB(intent)) {
                AppLog.i("сервис: беспровод в этой сборке выключен")
                failStart()
                return START_NOT_STICKY
            }
            if (wifiBMode && linkWaiting && !stopRequested) {
                // повторное нажатие, ожидание уже идёт: счётчики accept/TARGET_INFO не сбрасываем (иначе ложный bt-hint, h126)
                autoStarted = false
                WLog.ev(WLog.TRIGGER, "manual-mark", "why" to "waiting")
                return START_NOT_STICKY
            }
            wifiBMode = true
            autoStarted = false      // ручной запуск: уход машины по Bluetooth его не гасит
            stopRequested = false
            beginWait()
            startWifiB()
            btProbe.start()      // после WLog.begin в startWifiB: t= в строках WL bt от начала попытки
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
        if (wifiBMode || pendingTcp6 != null) {
            // кабель побеждает воздух: ожидание машины по Wi-Fi отменяем, после USB-сессии беспровод не возобновляем
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
        return START_NOT_STICKY
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
        p2pThisWait = false
        carSkipLogged = false
        btAcceptsThisWait = 0
        btHintShown = false
        main.removeCallbacks(btHintTick)
        main.postDelayed(btHintTick, BT_HINT_MS)
    }

    /** Запрос сети машины: через [CAR_NET_DELAY_MS] от начала ожидания (если сеть задана). Wi-Fi Direct-машине не нужен. */
    private fun scheduleCarNet() {
        main.removeCallbacks(carNetTick)
        if (!CarWifi(this).isSet) return
        if (wifiDirectCar) {
            logCarSkip()
            return
        }
        main.postDelayed(carNetTick, (CAR_NET_DELAY_MS - (SystemClock.elapsedRealtime() - waitSince)).coerceAtLeast(0))
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

    private fun onCarNetAvailable(@Suppress("UNUSED_PARAMETER") network: Network) = restartForCarNet()

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
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "waiting")      // всплеск ACL: ожидание уже идёт
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
        main.removeCallbacks(carNetTick)      // сессия идёт: новых запросов сети машины нет (сам запрос держим, CarNetEvent.SESSION_START)
        cancelBtHint()
        CarLifeState.accessory = transport.label
        CarLifeState.wirelessSession = transport.caps.kind == TransportCaps.TCP6.kind
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

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
        val wantDensity = aaPrefs.aaDensity
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим сменился (${h.core.videoMode.name} -> ${wantMode.name}), тёплую AA закрываю и поднимаю заново")
            h.shutdown("смена видео-режима")
        } else if (h != null && !h.isDead && h.core.densityOverride != wantDensity) {
            AppLog.i("BRIDGE: DPI AA сменился (${h.core.densityOverride} -> $wantDensity, 0 = авто), тёплую AA закрываю и поднимаю заново")
            h.shutdown("смена DPI AA")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, wantMode, wantDensity, previous = h)
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
        // на воздухе перекодирование обязательно: в passthrough битрейт не регулируется. Режим хоста не трогаем
        // (без перекодирования он уже R720, его можно перекодировать), хост не пересоздаём: сервер AA одноразовый
        val transcode = aaPrefs.transcode || transport.caps == TransportCaps.TCP6
        if (h.core.transcodeEnabled != transcode) {
            AppLog.i("BRIDGE видео: ${if (transcode) "воздух, перекодирование включаю принудительно" else "кабель, возвращаю passthrough по настройке"}")
            h.core.transcodeEnabled = transcode
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
    }

    /** Новый AA-хост: ядро, коннектор, сертификаты. null = мост не поднялся (причина в AaState.setupError). */
    private fun createHost(aaPrefs: AaPrefs, mode: VideoMode, densityOverride: Int, previous: BridgeHost?): BridgeHost? {
        return try {
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            core.musicRateProvider = { aaPrefs.musicRate }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            EqLive.settings = aaPrefs.eq
            core.transcodeEnabled = aaPrefs.transcode
            core.videoMode = mode
            core.densityOverride = densityOverride
            core.expectFeatureConfig = true
            if (aaPrefs.videoMode != mode) AppLog.i("BRIDGE видео-режим ${aaPrefs.videoMode.name} недоступен без перекодирования, использую ${mode.name}")
            // аудио-синки (MEDIA/SPEECH) объявляются в момент запроса телефона, когда уже известно решение по звуку
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb, densityOverride = densityOverride, carMake = aaPrefs.carMake) }) },
                serverPrep = { phase -> prepareServer(core, phase) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
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
            // беспровод: пока ждём машину по Wi-Fi или она подключена, сервер перезапускать есть для кого
            usbAlive = core.hasCarlifeLink || wifiBMode, warmAaSession = false, lastLocked = AutoServer.lastLocked,
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
        if (linkWaiting) return      // идёт ожидание машины по воздуху: сервис нужен
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        session = null
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
        val h = host
        if (h == null) {
            if (!wifiBMode || stopRequested) finish()
        } else {
            // обрыв USB: CarLife закрыт, AA держим graceMs (или закрываем сразу, если Стоп)
            h.onUsbSessionClosed(immediate = stopRequested)
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

    private fun finish() {
        stopCarNet(CarNetEvent.STOP, "finish")
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
        alive = false
        autoMode = false
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.DESTROY, "destroy")
        pendingTcp6?.abort()
        btProbe.stop("сервис уничтожен")
        audioDiag.stop()
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** Беспровод (бета): ждать машину по Wi-Fi, ветка B (хотспот телефона + маяк). */
        const val ACTION_WIFI_B = "com.yegorkapomidorka.headswap.WIFI_B"
        /** Выбранная машина подключилась по Bluetooth (ACL_CONNECTED): ждать по Wi-Fi, пока она рядом. */
        const val ACTION_WIFI_B_AUTO = "com.yegorkapomidorka.headswap.WIFI_B_AUTO"
        /** Машина отключилась по Bluetooth (ACL_DISCONNECTED): через debounce снять ожидание. */
        const val ACTION_WIFI_B_AUTO_OFF = "com.yegorkapomidorka.headswap.WIFI_B_AUTO_OFF"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val HINT_NOTIF_ID = 2
        private const val BT_HINT_NOTIF_ID = 3
        private const val WIFI_OFF_NOTIF_ID = 4

        /** Сервис создан и не уничтожен (один процесс): ресиверу ACL незачем будить пустой сервис ради отбоя. */
        @Volatile var alive = false
            private set
        /** Сколько автозапуск ждёт подключения включённой службы спецвозможностей. */
        private const val SERVICE_WAIT_MS = 3_000L
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
