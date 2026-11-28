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
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * Триггер автостарта: ACL_CONNECTED/DISCONNECTED выбранной машины ([CarProfile]) запускают и гасят ожидание по Wi-Fi,
 * в режиме [WlMode.BOX] ACL_CONNECTED запускает подключение к боксу (если не заглушено «Стопом»), ACL_DISCONNECTED ничего не делает.
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
        // режим BOX: запомненное устройство - бокс, ACL поднимает его подключение, а не ожидание машины; отбоя по ACL нет
        val prefs = WlModePrefs(context)
        val cmd = WlPolicy.aclCmd(prefs.mode, action == BluetoothDevice.ACTION_ACL_CONNECTED)
        val now = System.currentTimeMillis()
        val prevSeen = prefs.boxSeenMs
        if (prefs.mode == WlMode.BOX) prefs.boxSeenMs = now
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            WLog.ev(WLog.TRIGGER, "acl-connected", "dev" to WLog.maskMac(addr), "cmd" to cmd?.name)
            if (cmd == AclCmd.BOX_AUTO) {
                if (!WlPolicy.boxAutoStart(prefs.boxMuted, prevSeen, now)) {
                    WLog.ev(WLog.TRIGGER, "box-muted", "gapMs" to now - prevSeen)
                    return
                }
                prefs.boxMuted = false
            }
            val svc = if (cmd == AclCmd.BOX_AUTO) BridgeService.ACTION_BOX_AUTO else BridgeService.ACTION_WIFI_B_AUTO
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java).setAction(svc))
            } catch (e: Exception) {      // ForegroundServiceStartNotAllowedException и пр.: из фона без исключений запуск запрещён
                AppLog.e("ACL: старт сервиса", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName)
            }
        } else {
            WLog.ev(WLog.TRIGGER, "acl-disconnected", "dev" to WLog.maskMac(addr), "cmd" to cmd?.name)
            if (cmd == null) return      // бокс: отбоя по ACL нет
            // сервиса нет - гасить нечего; startService на пустой сервис из фона не нужен
            if (!BridgeService.alive) return
            val svc = BridgeService.ACTION_WIFI_B_AUTO_OFF
            try {
                context.startService(Intent(context, BridgeService::class.java).setAction(svc))
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
        /** Чужих устройств у телефона много (наушники, часы): в лог не чаще раза в минуту. */
        val ignoreLog = MinInterval(60_000L) { SystemClock.elapsedRealtime() }
    }
}
