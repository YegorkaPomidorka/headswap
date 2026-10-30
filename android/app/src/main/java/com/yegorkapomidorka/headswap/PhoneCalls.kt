package com.yegorkapomidorka.headswap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.yegorkapomidorka.headswap.aa.CallStateSource
import java.util.concurrent.Executors

/**
 * Состояние звонка из TelephonyManager, как Baidu PhoneManager.MyPhoneStateListener. API 31+ - TelephonyCallback
 * (нужно READ_PHONE_STATE), ниже - PhoneStateListener LISTEN_CALL_STATE. Номер не читаем (это READ_CALL_LOG).
 */
class PhoneCalls(private val ctx: Context, private val enabled: Boolean) : CallStateSource {
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-calls").also { it.isDaemon = true } }
    private var callback: Any? = null

    override fun start(onState: (Int) -> Unit): Boolean {
        val granted = Build.VERSION.SDK_INT < CallStateSource.PERMISSION_SDK ||
            ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        AppLog.i("звонки: флажок ${if (enabled) "вкл" else "выкл"}, разрешение ${if (granted) "есть" else "нет"}")
        if (tm == null || !CallStateSource.shouldListen(enabled, Build.VERSION.SDK_INT, granted)) return false
        return try {
            if (Build.VERSION.SDK_INT >= CallStateSource.PERMISSION_SDK) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
                tm.registerTelephonyCallback(exec, cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener(exec) {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                callback = l
            }
            true
        } catch (e: Exception) {
            AppLog.e("звонки: подписка", e)
            false
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm?.unregisterTelephonyCallback(cb)
            else {
                @Suppress("DEPRECATION")
                tm?.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            AppLog.e("звонки: отписка", e)
        }
        exec.shutdown()
    }
}
