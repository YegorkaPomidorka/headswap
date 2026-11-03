package com.yegorkapomidorka.headswap.launcher

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Рейка и строка статуса: время, сеть, Bluetooth, Wi-Fi и громкость медиа телефона. Ничего не нажимается. */
data class StatusInfo(
    val time: String = "--:--", val date: String = "", val net: String = NetLabel.NONE,
    /** Уровень сотового сигнала 0..4 для столбиков в рейке; -1 - не сотовая сеть или уровень неизвестен. */
    val signal: Int = -1,
    val bluetooth: Boolean = false, val wifi: Boolean = false, val volume: Int = 0,
)

internal object StatusReader {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM")

    fun read(ctx: Context, now: LocalDateTime = LocalDateTime.now()): StatusInfo = StatusInfo(
        time = now.format(timeFormat),
        date = weekday(now.dayOfWeek) + " " + now.format(dateFormat),
        net = network(ctx),
        signal = signal(ctx),
        bluetooth = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
            .getOrDefault(false),
        wifi = runCatching { ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false),
        volume = runCatching { ctx.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }
            .getOrDefault(0),
    )

    private fun weekday(d: DayOfWeek): String =
        d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.uppercase() }

    /** Мобильная сеть в статусе (у Wi-Fi свой значок): тип сети даже при активном Wi-Fi, как на магнитолах. */
    private fun network(ctx: Context): String = NetLabel.cellular(cellularType(ctx))

    /** Уровень сигнала мобильной сети 0..4; -1 - сети нет (нет SIM, авиарежим): тогда значок не рисуем. */
    private fun signal(ctx: Context): Int {
        val level = runCatching { ctx.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull()
        return level?.coerceIn(0, 4) ?: -1
    }

    /** Тип сети нужен READ_PHONE_STATE; без него подпись «4G» по умолчанию. */
    private fun cellularType(ctx: Context): Int? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching { ctx.getSystemService(TelephonyManager::class.java)?.dataNetworkType }.getOrNull()
    }
}
