package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog

/** Адрес Bluetooth: разбор ввода, проверка и маска для лога. Чистая логика (без android.*), чтобы проверять юнит-тестом. */
object BtAddr {
    private val RE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    /** trim, заглавные, «-» -> «:». Пустая строка остаётся пустой. */
    fun normalize(raw: String?): String = raw.orEmpty().trim().uppercase().replace('-', ':')

    /** Как BluetoothAdapter.checkBluetoothAddress: шесть пар hex-цифр через «:», заглавные. */
    fun isValid(addr: String?): Boolean = addr != null && RE.matches(addr)

    /** Для лога: первые четыре октета скрыты, XX:XX:XX:XX:A1:B2. */
    fun mask(addr: String?): String =
        if (isValid(addr)) "XX:XX:XX:XX:" + addr!!.takeLast(5) else "нет"

    /** Режим IDENTIFY из настроек -> status в BT_IDENTIFY_RESULT_IND: 1 -> 1, 2 -> 0, иначе (выкл) null. */
    fun identifyStatus(mode: Int): Int? = when (mode) {
        1 -> 1
        2 -> 0
        else -> null
    }
}

/** Как узнать адрес Bluetooth ГУ с телефонной стороны (ответ [done] может прийти с любой нити, ровно один раз). */
fun interface HuBtResolver {
    fun resolve(done: (addr: String?, source: String) -> Unit)

    companion object {
        val NONE = HuBtResolver { done -> done(null, "-") }
    }
}

/**
 * Адрес ГУ по Bluetooth: последний RFCOMM `carlife` ([rfcomm], пишет [CarlifeBtProbe]), затем подключённое устройство
 * A2DP/HEADSET (профильный прокси), затем аудио-выход A2DP (AudioDeviceInfo.address, API 28+).
 */
object HuBtAddress {
    private const val PROXY_TIMEOUT_MS = 2_000L

    /** remoteDevice.address последнего подключения ГУ к RFCOMM `carlife`. */
    @Volatile var rfcomm: String? = null

    fun resolver(ctx: Context) = HuBtResolver { done -> resolve(ctx.applicationContext, done) }

    @SuppressLint("MissingPermission")
    fun resolve(ctx: Context, done: (String?, String) -> Unit) {
        rfcomm?.takeIf { BtAddr.isValid(BtAddr.normalize(it)) }?.let { return done(BtAddr.normalize(it), "rfcomm") }
        val granted = Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !granted || !adapter.isEnabled) return done(audioA2dp(ctx), "a2dp-audio")

        val main = Handler(Looper.getMainLooper())
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val names = mapOf(BluetoothProfile.A2DP to "a2dp", BluetoothProfile.HEADSET to "hfp")
        // колбэки прокси и таймаут идут через main: found/left/finished трогает только главная нить
        var left = profiles.size
        var finished = false
        var found: Pair<String, String>? = null
        fun finish() {
            if (finished) return
            finished = true
            val f = found
            if (f != null) done(f.first, f.second) else done(audioA2dp(ctx), "a2dp-audio")
        }
        main.postDelayed({ finish() }, PROXY_TIMEOUT_MS)
        for (profile in profiles) {
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val devs: List<BluetoothDevice> = try {
                        proxy.connectedDevices
                    } catch (_: SecurityException) {
                        emptyList()
                    }
                    val line = devs.joinToString { "${BtAddr.mask(BtAddr.normalize(it.address))} '${try { it.name } catch (_: SecurityException) { "?" }}'" }
                    AppLog.i("BT IDENTIFY: ${names[p]} подключено: ${line.ifEmpty { "нет" }}", ui = false)
                    // несколько подключённых (часы, наушники): сперва «Honda»/HFT, иначе первое
                    val pick = devs.firstOrNull { d -> (try { d.name } catch (_: SecurityException) { null })?.let { it.contains("Honda", true) || it.contains("HFT", true) } == true }
                        ?: devs.firstOrNull()
                    try {
                        adapter.closeProfileProxy(p, proxy)
                    } catch (_: Exception) {
                    }
                    main.post {
                        if (found == null && pick != null) found = BtAddr.normalize(pick.address) to names.getValue(p)
                        if (--left == 0) finish()
                    }
                }

                override fun onServiceDisconnected(p: Int) {}
            }
            val asked = try {
                adapter.getProfileProxy(ctx, listener, profile)
            } catch (_: SecurityException) {
                false
            }
            if (!asked) main.post { if (--left == 0) finish() }
        }
    }

    /** Аудио-выход A2DP: AudioDeviceInfo.address = адрес устройства (API 28+, minSdk 29). */
    private fun audioA2dp(ctx: Context): String? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && BtAddr.isValid(BtAddr.normalize(it.address)) }
            ?.let { BtAddr.normalize(it.address) }
    }
}
