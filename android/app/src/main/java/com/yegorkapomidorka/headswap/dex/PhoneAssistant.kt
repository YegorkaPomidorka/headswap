package com.yegorkapomidorka.headswap.dex

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog

/**
 * Кнопка голоса на руле в режиме рабочего стола: Алиса на дисплее рабочего стола.
 *
 * Яндекс отдаёт Алису обычной activity по ASSIST (AssistantActivityAlias), её можно сразу открыть на нашем
 * дисплее (setLaunchDisplayId) - стенд 05.11: «Привет, я Алиса» на ГУ, без переноса задач.
 * Gemini так не открыть: окно только на экране телефона и закрывается, когда тот гаснет (ADR 0025).
 */
object PhoneAssistant {
    /** «Яндекс — с Алисой», потом «Яндекс Старт» (старое имя пакета). */
    private val ALICE_PACKAGES = listOf("com.yandex.searchapp", "ru.yandex.searchplugin")

    const val MANAGE_ACTIVITY_TASKS = "android.permission.MANAGE_ACTIVITY_TASKS"
    private const val PULL_MS = 4000L
    @Volatile private var puller: Thread? = null

    /**
     * Яндекс, выбранный помощником по умолчанию, открывает себя цепочкой AssistantActivityAlias -> MainActivity ->
     * YandexBrowserMainActivity, и последняя уходит на экран телефона (стенд 05.11). [PULL_MS] ловим задачу [pkg]
     * на телефоне и переносим на [displayId] (панель рабочего стола). Главный процесс: MANAGE_ACTIVITY_TASKS из
     * активации, скрытый API (--no-hidden-api-checks).
     */
    fun pullToDisplay(ctx: Context, displayId: Int, pkg: String) {
        if (displayId <= 0) { AppLog.i("ASSIST дочерний дисплей Алисы неизвестен"); return }
        if (ctx.checkSelfPermission(MANAGE_ACTIVITY_TASKS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLog.i("ASSIST нет MANAGE_ACTIVITY_TASKS: если Яндекс уйдёт на телефон, в панель не вернётся (повторите активацию)")
            return
        }
        if (puller?.isAlive == true) return
        puller = Thread({
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val start = android.os.SystemClock.elapsedRealtime()
            val moved = HashMap<Int, Int>()
            while (android.os.SystemClock.elapsedRealtime() - start < PULL_MS) {
                @Suppress("DEPRECATION")
                val found = runCatching { am.getRunningTasks(20) }.getOrDefault(emptyList()).filter { t ->
                    t.topActivity?.packageName == pkg && (moved[t.taskId] ?: 0) < 3 &&
                        FrameworkTaskIdentityReader.read(t)?.displayId == 0
                }
                for (t in found) {
                    moved[t.taskId] = (moved[t.taskId] ?: 0) + 1
                    val ms = android.os.SystemClock.elapsedRealtime() - start
                    runCatching { moveTask(t.taskId, displayId) }
                        .onSuccess { AppLog.i("ASSIST ${t.topActivity?.shortClassName} задача ${t.taskId} с телефона -> дисплей $displayId через $ms мс") }
                        .onFailure { AppLog.i("ASSIST перенос задачи ${t.taskId}: ${it.cause ?: it}") }
                }
                android.os.SystemClock.sleep(40)
            }
        }, "assist-pull").apply { isDaemon = true; start() }
    }

    private fun moveTask(taskId: Int, displayId: Int) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as android.os.IBinder
        val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        val type = Class.forName("android.app.IActivityTaskManager")
        val m = runCatching { type.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
            .getOrElse { type.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
        m.invoke(atm, taskId, displayId)
    }

    fun launch(ctx: Context, displayId: Int) {
        if (displayId <= 0) { AppLog.i("ASSIST дисплей рабочего стола не готов"); return }
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        for (pkg in ALICE_PACKAGES) {
            // без resolveActivity: на Android 11+ он не видит чужие activity без <queries> в манифесте
            val intent = Intent(Intent.ACTION_ASSIST).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent, opts)
                AppLog.i("ASSIST Алиса ($pkg) -> дисплей $displayId")
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.i("ASSIST $pkg не установлен")
            } catch (e: SecurityException) {
                AppLog.i("ASSIST $pkg: запуск на дисплее $displayId запрещён: ${e.message}")
            }
        }
        AppLog.i("ASSIST Алиса не запущена: нужно приложение «Яндекс — с Алисой»")
    }
}
