package com.yegorkapomidorka.headswap.link.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * Триггер автостарта: ACL_CONNECTED/DISCONNECTED выбранной машины ([CarProfile]) запускают и гасят ожидание по Wi-Fi.
 * В манифесте выключен (enabled=false), включается [CarProfile.enableTrigger], когда машина выбрана. Чужие устройства игнорируются.
 * На Android 12+ без BLUETOOTH_CONNECT система эти рассылки не доставляет.
 */
class CarAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!BuildConfig.WIRELESS_BETA) return
        val mac = CarProfile(context).btMac ?: return
        AppLog.init(context)      // рассылка может поднять процесс раньше activity и сервиса
        val addr = device(intent)?.address
        if (!CarMac.same(addr, mac)) {
            if (ignoreLog.tryAcquire()) WLog.ev(WLog.TRIGGER, "ignore", "action" to action.substringAfterLast('.'))
            return
        }
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            AutoServer.cancelHotspotOff()      // h135: машина вернулась - отложенное выключение точки не нужно
            WLog.ev(WLog.TRIGGER, "acl-connected", "dev" to WLog.maskMac(addr))
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
            } catch (e: Exception) {      // ForegroundServiceStartNotAllowedException и пр.: из фона без исключений запуск запрещён
                AppLog.e("ACL: старт сервиса", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName)
            }
        } else {
            WLog.ev(WLog.TRIGGER, "acl-disconnected", "dev" to WLog.maskMac(addr))
            // h135: точку гасим сами, без BridgeService (его может не быть); таймер перезапускается на каждый отбой
            if (AaPrefs(context).autoHotspot) AutoServer.scheduleHotspotOff(context, HOTSPOT_OFF_DELAY_MS, "bt off")
            // сервиса нет - гасить нечего; startService на пустой сервис из фона не нужен
            if (!BridgeService.alive) return
            try {
                context.startService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
            } catch (e: Exception) {
                AppLog.e("ACL: отбой сервису", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName, "action" to "off")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        /** Через сколько после отбоя ACL выключать точку: всплески ACL у ГУ. */
        const val HOTSPOT_OFF_DELAY_MS = 10_000L

        /** Чужих устройств у телефона много (наушники, часы): в лог не чаще раза в минуту. */
        val ignoreLog = MinInterval(60_000L) { SystemClock.elapsedRealtime() }
    }
}
