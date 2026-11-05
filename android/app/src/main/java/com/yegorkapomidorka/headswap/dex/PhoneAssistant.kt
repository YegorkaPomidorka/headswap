package com.yegorkapomidorka.headswap.dex

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog

/**
 * Кнопка голоса на руле в режиме рабочего стола: открывает голосовой ввод помощника, назначенного в системе
 * (Gemini/Google, Алиса, если её выбрали цифровым помощником).
 *
 * Честный вызов как по долгому нажатию питания (`cmd voiceinteraction show`) закрыт: и приложению, и shell нужен
 * ACCESS_VOICE_INTERACTION_SERVICE. Поэтому intent: VOICE_COMMAND (у Google это HandsFreeActivity), запасной ASSIST.
 * Окно помощника открывается на телефоне; слушает он сам (по BT HFP - микрофон машины), отвечает по BT.
 */
object PhoneAssistant {
    private val ACTIONS = listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)

    fun launch(ctx: Context) {
        for (action in ACTIONS) {
            // без resolveActivity: на Android 11+ он не видит чужие activity без <queries> в манифесте
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                AppLog.i("ASSIST $action: запущен")
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.i("ASSIST $action: ${e.message}")
            } catch (e: SecurityException) {
                AppLog.i("ASSIST $action: запуск запрещён: ${e.message}")
            }
        }
        AppLog.i("ASSIST помощник не запущен: на телефоне нет голосового помощника")
    }
}
