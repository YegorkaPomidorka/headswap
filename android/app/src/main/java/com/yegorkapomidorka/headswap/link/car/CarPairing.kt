package com.yegorkapomidorka.headswap.link.car

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * Выбор машины через CompanionDeviceManager: системный диалог со списком Bluetooth-устройств. Привязка даёт приложению
 * исключения на запуск foreground-сервиса из фона, а MAC и имя - фильтр для ресивера ACL ([CarAclReceiver]).
 *
 * API 33+: результат приходит в колбэк (onAssociationCreated) и дублируется в onActivityResult (EXTRA_ASSOCIATION);
 * API 29-32: только onActivityResult (EXTRA_DEVICE), id привязки нет. Запись идемпотентна: [store] не пишет дважды.
 */
object CarPairing {
    /** Код startIntentSenderForResult диалога CDM; результат ловит MainActivity.onActivityResult. */
    const val REQ_CAR_PAIR = 38

    fun available(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
            ctx.getSystemService(CompanionDeviceManager::class.java) != null

    /** Имя, выбранное в нашем списке: CDM на 33+ отдаёт displayName, но у части прошивок это MAC. */
    @Volatile private var pickedName: String? = null

    /**
     * Сначала свой список спаренных устройств с именами (системный диалог CDM показывает всё вокруг, часто одними MAC),
     * затем CDM подтверждает одно выбранное устройство (фильтр по адресу, singleDevice).
     */
    @SuppressLint("MissingPermission")
    fun start(activity: Activity) {
        if (!available(activity)) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "unavailable")
            return
        }
        val bonded = try {
            activity.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { (try { it.name } catch (_: SecurityException) { null } ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) {
            WLog.ev(WLog.TRIGGER, "pair-list", "bonded" to 0)
            associate(activity, null, null)      // нет разрешения или спаренных: системный диалог как есть
            return
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(
                if (WlModePrefs(activity).mode == WlMode.BOX) com.yegorkapomidorka.headswap.R.string.wl_box_pick
                else com.yegorkapomidorka.headswap.R.string.wl_car_pick
            )
            .setItems(bonded.map { it.first }.toTypedArray()) { _, i -> associate(activity, bonded[i].second, bonded[i].first) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun associate(activity: Activity, mac: String?, name: String?) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return
        pickedName = name
        val filter = BluetoothDeviceFilter.Builder().apply { if (mac != null) setAddress(mac) }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(mac != null)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 33) cdm.associate(request, activity.mainExecutor, ModernCallback(activity))
            else @Suppress("DEPRECATION") cdm.associate(request, LegacyCallback(activity), Handler(Looper.getMainLooper()))
            WLog.ev(WLog.TRIGGER, "pair-start", "api" to Build.VERSION.SDK_INT)
        } catch (e: Exception) {
            AppLog.e("CDM associate", e)
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to e.javaClass.simpleName)
        }
    }

    /** Результат диалога CDM из MainActivity.onActivityResult(REQ_CAR_PAIR). */
    fun onResult(ctx: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            WLog.ev(WLog.TRIGGER, "pair-cancel", "code" to resultCode)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) {
                saveFromInfo(ctx, info)
                return
            }
        }
        val dev = device(data)
        if (dev == null) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "no-device")
            return
        }
        // имя читается с BLUETOOTH_CONNECT (31+); без него SecurityException, имя тогда не показываем
        val name = try { dev.name } catch (_: SecurityException) { null }
        store(ctx, dev.address, name, -1)
    }

    /** Забыть машину: снять привязку CDM, очистить профиль, выключить триггер. */
    @SuppressLint("MissingPermission")      // stopObservingDevicePresence: см. observe
    fun forget(ctx: Context) {
        val profile = CarProfile(ctx)
        val mac = profile.btMac
        val id = profile.cdmAssociationId
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
        if (cdm != null) {
            @Suppress("DEPRECATION")
            try {
                if (Build.VERSION.SDK_INT >= 31 && mac != null) {
                    try { cdm.stopObservingDevicePresence(mac.uppercase()) } catch (_: Exception) {}
                }
                if (Build.VERSION.SDK_INT >= 33 && id >= 0) cdm.disassociate(id)
                else if (mac != null) cdm.disassociate(mac.uppercase())
            } catch (e: Exception) {
                WLog.ev(WLog.TRIGGER, "forget-fail", "err" to e.javaClass.simpleName)
            }
        }
        profile.clear()
        CarProfile.enableTrigger(ctx, false)
        WLog.ev(WLog.TRIGGER, "forgot", "dev" to WLog.maskMac(mac), "cdm" to id)
    }

    @TargetApi(33)
    private fun saveFromInfo(ctx: Context, info: AssociationInfo) {
        val mac = info.deviceMacAddress?.toString()
        if (mac == null) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "no-mac")
            return
        }
        store(ctx, mac, info.displayName?.toString(), info.id)
    }

    /** Записать машину и включить триггер. Повтор (колбэк и onActivityResult) молча пропускается. */
    private fun store(ctx: Context, mac: String, name: String?, id: Int) {
        val profile = CarProfile(ctx)
        val sameMac = CarMac.same(profile.btMac, mac)
        // тот же MAC и либо тот же id, либо новый без id (путь onActivityResult после колбэка): уже записано
        if (sameMac && (id == -1 || id == profile.cdmAssociationId)) return
        val keepId = if (sameMac && id == -1) profile.cdmAssociationId else id
        // выбрали другую машину: старую привязку снимаем, иначе на 33+ копятся осиротевшие association
        val oldId = profile.cdmAssociationId
        if (Build.VERSION.SDK_INT >= 33 && oldId >= 0 && oldId != keepId) {
            try {
                ctx.getSystemService(CompanionDeviceManager::class.java)?.disassociate(oldId)
            } catch (e: Exception) {
                WLog.ev(WLog.TRIGGER, "forget-fail", "err" to e.javaClass.simpleName)
            }
        }
        // имя из нашего списка надёжнее displayName CDM (у части прошивок там MAC)
        profile.save(mac, pickedName ?: name ?: if (sameMac) profile.btName else null, keepId)
        CarProfile.enableTrigger(ctx, true)
        WLog.ev(WLog.TRIGGER, "paired", "dev" to WLog.maskMac(mac), "cdm" to id)
        observe(ctx, mac)
    }

    /** Присутствие устройства (31+): запасной путь, основной триггер - ACL. Нужного разрешения может не быть: только лог. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")      // REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE не объявлено намеренно: без него SecurityException, только лог
    private fun observe(ctx: Context, mac: String) {
        if (Build.VERSION.SDK_INT < 31) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            cdm.startObservingDevicePresence(mac.uppercase())
            WLog.ev(WLog.TRIGGER, "presence", "ok" to true)
        } catch (e: Exception) {
            WLog.ev(WLog.TRIGGER, "presence", "ok" to false, "err" to e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun device(data: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        else data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE) as? BluetoothDevice

    private fun launch(activity: Activity, sender: IntentSender) {
        try {
            activity.startIntentSenderForResult(sender, REQ_CAR_PAIR, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to e.javaClass.simpleName)
        }
    }

    /** API 33+: диалог приходит через onAssociationPending, готовая привязка - через onAssociationCreated. */
    @TargetApi(33)
    @SuppressLint("NewApi")
    private class ModernCallback(private val activity: Activity) : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) = launch(activity, intentSender)

        override fun onAssociationCreated(associationInfo: AssociationInfo) = saveFromInfo(activity, associationInfo)

        override fun onFailure(error: CharSequence?) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "callback")
        }
    }

    /** API 29-32: диалог приходит через onDeviceFound, выбранное устройство - в onActivityResult. */
    private class LegacyCallback(private val activity: Activity) : CompanionDeviceManager.Callback() {
        @Deprecated("Deprecated in Java")
        override fun onDeviceFound(chooserLauncher: IntentSender) = launch(activity, chooserLauncher)

        override fun onFailure(error: CharSequence?) {
            WLog.ev(WLog.TRIGGER, "pair-fail", "err" to "callback")
        }
    }
}
