package com.yegorkapomidorka.headswap

import android.app.Application

/**
 * Процесс приложения. В сборке -PcarlifePackage порты ADB-режима поднимаются здесь, как в настоящем CarLife
 * (ConnectManager.startAcceptThread из Application.onCreate): ГУ запускает activity по `am start`,
 * делает `adb forward` и сразу подключается, ждать UI нельзя.
 */
class HeadswapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)       // повторный init безопасен: BridgeService и MainActivity зовут его же
        if (BuildConfig.CARLIFE_ADB) {
            AdbLink.start(this)
            AdbDiag.start(this)
        }
    }
}
