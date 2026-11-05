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
    const val REMOVE_TASKS = "android.permission.REMOVE_TASKS"
    private const val PULL_MS = 4000L
    private const val WATCH_MS = 10 * 60_000L
    @Volatile private var watcher: Thread? = null
    @Volatile private var watchGen = 0

    /**
     * Пока панель Алисы открыта ([stopWatch] или [WATCH_MS]):
     * - первые [PULL_MS]: Яндекс, выбранный помощником по умолчанию, открывает себя цепочкой AssistantActivityAlias ->
     *   MainActivity -> YandexBrowserMainActivity, и последняя уходит на экран телефона (стенд 05.11). Задачу [pkg]
     *   на телефоне переносим на [displayId] (панель рабочего стола);
     * - всё время: задача другого приложения на [displayId] (Алиса открыла Навигатор с маршрутом) - в [onForeign],
     *   один раз на задачу, с поточного потока.
     * Главный процесс: MANAGE_ACTIVITY_TASKS из активации, скрытый API (--no-hidden-api-checks).
     */
    fun watch(ctx: Context, displayId: Int, pkg: String, onForeign: (android.app.ActivityManager.RunningTaskInfo) -> Unit) {
        if (displayId <= 0) { AppLog.i("ASSIST дочерний дисплей Алисы неизвестен"); return }
        if (ctx.checkSelfPermission(MANAGE_ACTIVITY_TASKS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLog.i("ASSIST нет MANAGE_ACTIVITY_TASKS: если Яндекс уйдёт на телефон, в панель не вернётся (повторите активацию)")
        }
        val gen = ++watchGen
        watcher = Thread({
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val start = android.os.SystemClock.elapsedRealtime()
            val moved = HashMap<Int, Int>()
            val reported = HashSet<Int>()
            while (gen == watchGen && android.os.SystemClock.elapsedRealtime() - start < WATCH_MS) {
                val pulling = android.os.SystemClock.elapsedRealtime() - start < PULL_MS
                @Suppress("DEPRECATION")
                val tasks = runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
                for (t in tasks) {
                    val top = t.topActivity?.packageName ?: continue
                    val display = FrameworkTaskIdentityReader.read(t)?.displayId
                    if (display == displayId && top != pkg && reported.add(t.taskId)) {
                        AppLog.i("ASSIST на панели Алисы открылось $top (задача ${t.taskId}): ${t.baseIntent.action} ${t.baseIntent.dataString}")
                        runCatching { onForeign(t) }.onFailure { AppLog.i("ASSIST разбор $top: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                }
                val found = if (!pulling) emptyList() else tasks.filter { t ->
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
                android.os.SystemClock.sleep(if (pulling) 40 else 250)
            }
        }, "assist-watch").apply { isDaemon = true; start() }
    }

    /** Панель Алисы закрыта: слежение [watch] кончается на следующем шаге. */
    fun stopWatch() { watchGen++ }

    private fun atm(): Pair<Any, Class<*>> {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as android.os.IBinder
        val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        return atm to Class.forName("android.app.IActivityTaskManager")
    }

    fun moveTask(taskId: Int, displayId: Int) {
        val (atm, type) = atm()
        val m = runCatching { type.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
            .getOrElse { type.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
        m.invoke(atm, taskId, displayId)
    }

    /** Закрыть задачу (Навигатор, которого Алиса открыла в панели: маршрут уже ушёл в плитку). Не вышло - на экран телефона. */
    fun removeTask(taskId: Int) {
        val ok = runCatching {
            val (atm, type) = atm()
            type.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId) as? Boolean ?: true
        }.onFailure { AppLog.i("ASSIST removeTask $taskId: ${it.cause ?: it}") }.getOrDefault(false)
        if (ok) { AppLog.i("ASSIST задача $taskId закрыта"); return }
        runCatching { moveTask(taskId, 0) }.onSuccess { AppLog.i("ASSIST задача $taskId -> экран телефона") }
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
