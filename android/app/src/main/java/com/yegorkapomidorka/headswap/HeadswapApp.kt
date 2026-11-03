package com.yegorkapomidorka.headswap

import android.app.Application
import com.yegorkapomidorka.headswap.dex.ActivationToken

/** Старт процесса: токен активации с ПК должен существовать до первой команды ManualAccessLauncher, экрана для него больше нет. */
class HeadswapApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
