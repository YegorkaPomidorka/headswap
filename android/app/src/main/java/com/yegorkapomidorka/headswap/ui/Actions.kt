package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.provider.Settings
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.TranscodeSelfCheck
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.autosrv.Trigger

/**
 * Действия, которые раньше жили прямо в MainActivity (кнопки UI и команды intent):
 * сканирование USB, старт/стоп сервиса моста, запуск сервера ГУ, диагностика.
 */
object Actions {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    /** Сканировать: показать и залогировать все поля аксессуара; без разрешения запросить; с разрешением запустить сервис. */
    fun scan(ctx: Context, reason: String) {
        val usb = ctx.getSystemService(UsbManager::class.java)
        AppLog.i("сканирование ($reason)")
        val list = usb.accessoryList
        if (list == null || list.isEmpty()) {
            AppLog.i("аксессуар не найден (getAccessoryList=${if (list == null) "null" else "[]"})")
            // ADB-сборка: статус ведёт AdbLink (ГУ запускает activity через am start, затирать его нельзя)
            if (BuildConfig.CARLIFE_ADB) return
            CarLifeState.accessory = "не найден"
            CarLifeState.status = "ждём аксессуар"
            return
        }
        for ((i, acc) in list.withIndex()) {
            AppLog.i("аксессуар[$i]: ${MainActivity.describe(acc)} hasPermission=${usb.hasPermission(acc)}")
            val isBaidu = acc.manufacturer == "Baidu" && acc.model == "CarLife"
            AppLog.i("  строки Baidu/CarLife: ${if (isBaidu) "да" else "НЕТ (другие строки, смотри выше)"}")
        }
        val acc = list[0]
        CarLifeState.accessory = MainActivity.describe(acc)
        if (usb.hasPermission(acc)) {
            startBridge(ctx)
        } else {
            AppLog.i("прошу разрешение на аксессуар")
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(MainActivity.ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(acc, pi)
        }
    }

    fun startBridge(ctx: Context) {
        AppLog.i("запускаю сервис")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
    }

    fun stopBridge(ctx: Context) {
        AppLog.i("кнопка Стоп")
        HeadswapStatus.markUserStop()
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
    }

    /** Самопроверка сертификатов AA и TLS без сети: результат в лог и AaState.selftest. */
    fun aaSelftest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ AaCreds.selfTest(app) }, "aa-selftest").start()
    }

    fun transcodeTest(ctx: Context, opts: String) = TranscodeSelfCheck.run(ctx, opts)

    /** Настройки Android Auto (сервер ГУ): HyperOS прячет их из системных настроек. */
    fun openAaSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppLog.i("открыты настройки Android Auto")
        } catch (e: Exception) {
            AppLog.e("не открыть настройки Android Auto", e)
            Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_open_aa_failed, "${e.javaClass.simpleName}: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /** Перезапуск сервера ГУ: кнопка или команда adb; без включённой службы спецвозможностей объясняем, что делать. */
    fun restartServer(ctx: Context, fromCmd: Boolean = false) {
        if (!AutoServer.isConnected) {
            AppLog.i("AUTOSRV: служба спецвозможностей не подключена, перезапускать нечем")
            if (fromCmd || ctx !is Activity) {
                Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_service_off), Toast.LENGTH_LONG).show()
            } else {
                enableServiceDialog(ctx)
            }
            return
        }
        HeadswapStatus.markServerRequested()
        AutoServer.start(Trigger.BUTTON)
    }

    /**
     * Кнопка «Запустить сервер ГУ» на главном экране: автозапуск включён и служба подключена - aa_server_restart,
     * иначе открываем настройки AA, дальше пользователь жмёт «Запустить сервер ГУ» сам.
     */
    fun startServerFromUi(ctx: Context) {
        if (AaPrefs(ctx).autoServer && AutoServer.isConnected) {
            restartServer(ctx)
        } else {
            if (AaPrefs(ctx).autoServer) {
                Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_autostart_off), Toast.LENGTH_LONG).show()
            }
            HeadswapStatus.markServerRequested()
            openAaSettings(ctx)
        }
    }

    fun openAccessibilitySettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть настройки спецвозможностей", e)
        }
    }

    fun enableServiceDialog(ctx: Context) {
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(ctx.getString(R.string.act_enable_title))
            .setMessage(ctx.getString(R.string.act_enable_message, ctx.getString(R.string.autosrv_label)))
            .setPositiveButton(ctx.getString(R.string.act_open)) { _, _ -> openAccessibilitySettings(ctx) }
            .setNegativeButton(ctx.getString(R.string.act_cancel), null)
            .show()
    }

    /** Проверка микрофона телефона без машины: 2 с AudioRecord, в лог сколько байт (ждём около 64000) и пик. */
    fun micTest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            val m = PhoneMicCapture(app)
            var bytes = 0L
            val ok = m.start { bytes += it.size }
            AppLog.i("mic_test: старт=$ok (RECORD_AUDIO ${if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "есть" else "НЕТ"})")
            if (ok) {
                Thread.sleep(2000)
                m.stop()
                Thread.sleep(300)
                AppLog.i("mic_test: за 2 с получено $bytes Б (ждём около 64000)")
            }
        }, "mic-test").start()
    }
}
