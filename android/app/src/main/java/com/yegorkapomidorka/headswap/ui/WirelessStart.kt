package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.link.diag.NetProbe

/**
 * Кнопка «Ждать машину по Wi-Fi» с настроек и с главного экрана (h185): запрос разрешений и старт ожидания.
 * Результат запроса приходит в MainActivity.onRequestPermissionsResult -> [onResult], с какого бы экрана ни нажали.
 */
object WirelessStart {
    /** Тот же код, что MainActivity.REQ_LOCAL_NETWORK: результат попадает в её лог. */
    const val REQUEST_CODE = 37

    @Volatile private var awaiting = false

    /** Последний запрос закончился без ACCESS_LOCAL_NETWORK (Android 17+): ожидание не стартовало, настройки покажут предупреждение. */
    @Volatile var lanDenied = false
        private set

    private fun btGranted(ctx: Context) =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun lanGranted(ctx: Context) = Build.VERSION.SDK_INT < 37 || NetProbe.localNetworkGranted(ctx) == "granted"

    /**
     * На Android 17+ без ACCESS_LOCAL_NETWORK LAN режется молча: сначала спрашиваем, старт - в [onResult].
     * BLUETOOTH_CONNECT (Android 12+) и «Устройства поблизости» (Wi-Fi Direct) - для ветки A, без них беспровод всё равно стартует.
     */
    fun request(host: MainActivity) {
        lanDenied = false
        val ask = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 37 && NetProbe.localNetworkGranted(host) != "granted") ask += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (!btGranted(host)) ask += Manifest.permission.BLUETOOTH_CONNECT
        // ветка A: вход в Wi-Fi Direct-группу ГУ; без него беспровод стартует, но только веткой B
        val p2p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (host.checkSelfPermission(p2p) != PackageManager.PERMISSION_GRANTED) ask += p2p
        if (ask.isNotEmpty()) {
            awaiting = true
            host.requestPermissions(ask.toTypedArray(), REQUEST_CODE)
            return
        }
        start(host)
    }

    /** Ответ на запрос из [request]; чужие запросы с тем же кодом (отладка) не трогает. */
    fun onResult(ctx: Context) {
        if (!awaiting) return
        awaiting = false
        if (lanGranted(ctx)) start(ctx) else lanDenied = true
    }

    private fun start(ctx: Context) {
        AppLog.i("кнопка «Ждать машину по Wi-Fi»")
        UiPrefs(ctx).wirelessUsed = true
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B))
    }
}
