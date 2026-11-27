// Derived from FT (github.com/OneAboveAll1964/ft-app, Apache-2.0): IccoaDiscovery.kt (скан), IccoaSession.kt (GATT).
// Код свой; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import java.util.Locale
import java.util.UUID

/** Строка лога ICCOA вне сессии (BLE, Wi-Fi машины): формат как у [IccoaSession]. */
internal fun icLog(phase: String, event: String, vararg kv: Pair<String, Any?>) =
    AppLog.i("IC $phase $event" + kv.joinToString("") { " ${it.first}=${it.second ?: "-"}" }, false)

/**
 * Подъём ICCOA по воздуху, часть Bluetooth (Э4, ADR 0066): скан без фильтра → GATT → CLIENT_INFO → SERVER_INFO с Wi-Fi
 * машины. Состояние и колбэки — на главной нити, [start] и [close] звать с неё же. После SERVER_INFO GATT не закрывается:
 * держим до [close] (конец сессии), иначе машина может снять точку доступа. В лог не идут PIN, тело JSON, адреса,
 * полный token и тексты исключений; чужие устройства не логируются вовсе.
 */
@SuppressLint("MissingPermission")      // разрешения проверяет start(); SecurityException уходит в отказ кодом
class IccoaBle(
    ctx: Context,
    private val trust: IccoaTrust,
    /** PIN с экрана машины; null — быстрый вход по сохранённому ключу машины. */
    private val pin: String?,
    /** Машина выбрана, дальше GATT. Аргумент — имя, под которым хранится её ключ. */
    private val onFound: (carId: String) -> Unit,
    private val onWifi: (IccoaWifiInfo) -> Unit,
    /** Короткий код причины; к этому моменту скан и GATT уже закрыты. */
    private val onFail: (code: String) -> Unit,
) {
    private class Car(val device: BluetoothDevice) {
        var name = ""
        var rssi = 0
        var connectable = false
        var token: String? = null
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cars = LinkedHashMap<String, Car>()
    private val serverInfo = IccoaServerInfo()
    private var closed = false
    private var phase = "ble"
    private var phaseAt = 0L
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var pickAt = 0L                 // первая находка + PICK_MS: раньше машину не выбираем
    private var gatt: BluetoothGatt? = null
    private var discoverAsked = false
    private var ready = false               // сервис найден, CLIENT_INFO ушёл
    private var done = false                // SERVER_INFO получен

    private fun now() = SystemClock.elapsedRealtime()
    private fun since() = now() - phaseAt

    private fun guarded(task: () -> Unit) {
        if (closed) return
        try {
            task()
        } catch (e: Exception) {
            fail(e.javaClass.simpleName)
        }
    }

    private fun post(task: () -> Unit) = main.post { guarded(task) }
    private fun later(ms: Long, task: () -> Unit) = main.postDelayed({ guarded(task) }, ms)

    fun start() {
        phaseAt = now()
        val need = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return fail("no-permission")
        val adapter = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        if (runCatching { adapter?.isEnabled }.getOrNull() != true) return fail("bt-off")
        // до Android 12 скан BLE без включённой геолокации молча ничего не находит
        if (Build.VERSION.SDK_INT < 31 && app.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            return fail("location-off")
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                post { if (scan === this) seen(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                post { if (scan === this) results.forEach(::seen) }
            }

            override fun onScanFailed(errorCode: Int) {
                post { if (scan === this) fail("scan-failed", "code" to errorCode) }
            }
        }
        try {
            val s = adapter?.bluetoothLeScanner ?: return fail("bt-off")
            scanner = s
            scan = cb
            // Без фильтра: машины рекламируют ICCOA по-разному (uuid сервиса, serviceData, имя), отбор — в seen().
            s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            return fail("scan-" + e.javaClass.simpleName)
        }
        icLog("ble", "scan-start")
        later(SCAN_MS) {
            if (scan == null) return@later
            icLog("ble", "scan-timeout", "seen" to cars.size, "ms" to since())
            val none = cars.isEmpty()
            close()
            onFail(if (none) "not-found" else "not-connectable")
        }
    }

    private fun seen(result: ScanResult) {
        val record = result.scanRecord
        val data = HashMap<String, ByteArray>()
        record?.serviceData?.forEach { (uuid, bytes) -> if (bytes != null) data[uuid.toString().lowercase(Locale.ROOT)] = bytes }
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val address = result.device.address
        val known = cars[address]
        // Чужие устройства не запоминаем и не логируем.
        if (known == null && !IccoaBleInfo.hint(name, record?.serviceUuids.orEmpty().map { it.toString() } + data.keys)) return
        val car = known ?: Car(result.device).also { cars[address] = it }
        // Имя и token могут прийти разными пакетами рекламы: копим по адресу.
        if (!name.isNullOrBlank()) car.name = name
        car.rssi = result.rssi
        car.connectable = result.isConnectable
        IccoaBleInfo.token(data)?.let { car.token = it }
        if (known == null) icLog("ble", "found", "rssi" to car.rssi, "conn" to car.connectable, "token" to short(car.token),
            "name" to car.name.take(32).ifEmpty { null })
        if (pickAt == 0L) {
            pickAt = now() + PICK_MS
            later(PICK_MS, ::pick)
        } else if (now() >= pickAt) pick()
    }

    /** Самая сильная по rssi из машин, к которым можно подключиться; таких ещё нет — скан идёт дальше. */
    private fun pick() {
        if (scan == null) return
        val car = cars.values.filter { it.connectable }.maxByOrNull { it.rssi } ?: return
        stopScan()
        val carId = car.token ?: car.name.ifBlank { "ble" }
        icLog("ble", "pick", "rssi" to car.rssi, "token" to short(car.token), "cars" to cars.size, "ms" to since())
        if (pin == null && trust.carKey(carId) == null) {
            // Быстрый вход без ключа машины не пройдёт: не тратим на него GATT и Wi-Fi.
            icLog("auth", "normal-required", "at" to "ble")
            close()
            onFail("need-pin")
            return
        }
        onFound(carId)
        if (closed) return
        phase = "gatt"
        phaseAt = now()
        val g = try {
            @Suppress("DEPRECATION")        // замена появилась только в API 37, minSdk 29
            car.device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            return fail("connect-" + e.javaClass.simpleName)
        }
        if (g == null) return fail("connect-null")
        gatt = g
        later(GATT_MS) { if (!ready) fail("timeout") }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    icLog("gatt", "connected", "ms" to since())
                    if (!g.requestMtu(512)) discover(g)
                    later(2_000) { discover(g) }            // onMtuChanged может не прийти
                } else if (done) {
                    // Wi-Fi машины уже известен: конец сессии решают control и сеть, не Bluetooth.
                    icLog("gatt", "lost", "status" to status)
                } else fail("disconnected", "status" to status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            post {
                if (g !== gatt) return@post
                icLog("gatt", "mtu", "v" to mtu, "status" to status)
                discover(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            post {
                if (g !== gatt || ready) return@post
                val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(UUID.fromString(IccoaBleInfo.SERVICE)) else null
                val incoming = service?.getCharacteristic(SERVER_INFO)
                val outgoing = service?.getCharacteristic(CLIENT_INFO)
                // CCCD не пишем: XA60 на лишней ATT-операции зависает (FT), машине хватает самой подписки.
                val ok = incoming != null && outgoing != null && g.setCharacteristicNotification(incoming, true)
                icLog("gatt", "services", "ok" to ok, "status" to status, "ms" to since())
                if (!ok) return@post fail(if (service == null) "no-service" else "no-channel")
                ready = true
                write(g, outgoing)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            post {
                if (g !== gatt || done || status == BluetoothGatt.GATT_SUCCESS) return@post
                fail("write-rejected", "status" to status)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != SERVER_INFO) return
            val copy = value.copyOf()
            post { received(g, copy) }
        }

        @Deprecated("до Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // С Android 13 система зовёт перегрузку с value, и та переопределена: здесь только старые версии.
            if (Build.VERSION.SDK_INT >= 33 || characteristic.uuid != SERVER_INFO) return
            val copy = characteristic.value?.copyOf() ?: return
            post { received(g, copy) }
        }
    }

    private fun discover(g: BluetoothGatt) {
        if (g !== gatt || discoverAsked) return
        discoverAsked = true
        if (!g.discoverServices()) fail("discover")
    }

    private fun write(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val payload = try {
            IccoaBleInfo.clientInfo(trust.clientId, Build.MODEL, trust.mac, channel(), pin)
        } catch (e: IllegalArgumentException) {
            return fail("client-info")
        }
        val size = payload.size
        val queued = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        } finally {
            payload.fill(0)                                 // в нём PIN
        }
        icLog("gatt", "client-info", "bytes" to size, "quick" to (pin == null), "queued" to queued)
        if (!queued) return fail("write")
        later(SERVER_INFO_MS) { if (!done) fail("no-server-info") }
    }

    /** Канал Wi-Fi, на котором телефон сейчас; не подключён или не дали узнать — 1. */
    @Suppress("DEPRECATION")
    private fun channel(): Int = runCatching {
        IccoaBleInfo.wifiChannel(app.getSystemService(WifiManager::class.java).connectionInfo.frequency)
    }.getOrDefault(1)

    private fun received(g: BluetoothGatt, fragment: ByteArray) {
        try {
            if (g !== gatt || !ready || done) return
            val info = try {
                serverInfo.add(fragment)
            } catch (e: IccoaBleError) {
                if (serverInfo.bytes > 0) icLog("gatt", "server-info", "bytes" to serverInfo.bytes, "type" to serverInfo.type)
                return fail(e.code)
            }
            if (info == null) return
            done = true
            icLog("gatt", "server-info", "bytes" to serverInfo.bytes, "type" to serverInfo.type, "ms" to since())
            onWifi(info)
        } finally {
            fragment.fill(0)
        }
    }

    private fun fail(code: String, vararg kv: Pair<String, Any?>) {
        if (closed) return
        icLog(phase, "fail", "why" to code, *kv, "ms" to since())
        close()
        onFail(code)
    }

    private fun stopScan() {
        val cb = scan ?: return
        scan = null
        runCatching { scanner?.stopScan(cb) }
        scanner = null
    }

    /** Снять скан и GATT. Можно звать сколько угодно раз; колбэки после этого не придут. */
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        stopScan()
        serverInfo.reset()
        cars.clear()
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        icLog("gatt", "closed")
    }

    private companion object {
        const val SCAN_MS = 30_000L
        /** Окно после первой находки: если машин несколько, берём ближайшую. */
        const val PICK_MS = 3_000L
        /** От connectGatt до готовности сервисов. */
        const val GATT_MS = 20_000L
        const val SERVER_INFO_MS = 12_000L
        val CLIENT_INFO: UUID = UUID.fromString(IccoaBleInfo.CLIENT_INFO)
        val SERVER_INFO: UUID = UUID.fromString(IccoaBleInfo.SERVER_INFO)

        fun short(token: String?) = token?.let { it.take(4) + "…" }
    }
}
