package com.yegorkapomidorka.headswap

import android.app.Application
import com.yegorkapomidorka.headswap.dex.ActivationToken

/**
 * Старт процесса: токен активации с ПК должен существовать до первой команды ManualAccessLauncher, экрана для него больше нет.
 * В сборке -PcarlifePackage здесь же поднимаются порты ADB-режима, как в настоящем CarLife
 * (ConnectManager.startAcceptThread из Application.onCreate): ГУ запускает activity по `am start`,
 * делает `adb forward` и сразу подключается, ждать UI нельзя.
 */
class HeadswapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.CARLIFE_ADB) {
            AppLog.init(this)       // повторный init безопасен: BridgeService и MainActivity зовут его же
            AdbLink.start(this)
            AdbDiag.start(this)
        }
        // current() создаёт токен через синхронный commit(): не на главном потоке
        Thread({
            try {
                ActivationToken(this).current()
            } catch (e: Exception) {
                AppLog.e("токен активации не создан", e)
            }
        }, "activation-token").start()
    }
}
