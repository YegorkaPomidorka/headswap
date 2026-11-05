package com.yegorkapomidorka.headswap.dex

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog

/**
 * Кнопка голоса на руле в режиме рабочего стола: открывает голосовой ввод помощника, назначенного в системе
 * (Gemini/Google, Алиса, если её выбрали цифровым помощником).
 *
 * Прямой вызов как по долгому нажатию питания (`cmd voiceinteraction show`) закрыт: и приложению, и shell нужен
 * ACCESS_VOICE_INTERACTION_SERVICE. Поэтому intent: VOICE_COMMAND (у Google это HandsFreeActivity), запасной ASSIST.
 *
 * Окно открываем явно на дисплее рабочего стола: без launchDisplayId система кладёт его на дисплей с фокусом,
 * а при заблокированном телефоне это экран телефона за блокировкой (поездка 05.11: помощник слушал, на ГУ пусто).
 * Дисплей не наш или запуск на нём запрещён - открываем как раньше, на телефоне.
 */
object PhoneAssistant {
    private val ACTIONS = listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)

    fun launch(ctx: Context, displayId: Int) {
        for (action in ACTIONS) {
            // без resolveActivity: на Android 11+ он не видит чужие activity без <queries> в манифесте
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (displayId > 0 && start(ctx, intent, action, displayId)) return
            if (start(ctx, intent, action, null)) return
        }
        AppLog.i("ASSIST помощник не запущен: на телефоне нет голосового помощника")
    }

    /** true - запущен; false - этот способ не сработал, пробуем следующий. */
    private fun start(ctx: Context, intent: Intent, action: String, displayId: Int?): Boolean {
        val where = if (displayId != null) "дисплей $displayId" else "экран по умолчанию"
        return try {
            if (displayId != null) ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            else ctx.startActivity(intent)
            AppLog.i("ASSIST $action: запущен ($where)")
            true
        } catch (e: ActivityNotFoundException) {
            AppLog.i("ASSIST $action ($where): ${e.message}")
            false
        } catch (e: SecurityException) {
            AppLog.i("ASSIST $action ($where): запуск запрещён: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            AppLog.i("ASSIST $action ($where): ${e.message}")
            false
        }
    }
}
