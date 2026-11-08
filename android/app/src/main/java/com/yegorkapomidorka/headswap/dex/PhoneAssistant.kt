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
 * Gemini так не открыть: окно только на экране телефона и закрывается, когда тот гаснет (ADR 0026).
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
        // Gemini не переносим: при погашенном экране телефона он ищет встроенный второй экран, наш не считает и
        // закрывает сессию без ответа (лог Google «Main display is OFF», стенд 07.11). Его плашка остаётся на телефоне.
        val gemini = pkg == EmbeddedDisplayPolicy.GEMINI_PACKAGE
        val pullMs = if (gemini) 0L else PULL_MS
        val maxMoves = 3
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
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                val pulling = elapsed < pullMs
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
                // не только с телефона: старая задача Яндекса после закрытия панели жила под лаунчером на дисплее
                // рабочего стола, и Алиса уходила в неё (поездка 05.11, wm_task_moved на 491)
                val found = if (!pulling) emptyList() else tasks.filter { t ->
                    val d = FrameworkTaskIdentityReader.read(t)?.displayId
                    t.topActivity?.packageName == pkg && (moved[t.taskId] ?: 0) < maxMoves && d != null && d != displayId
                }
                for (t in found) {
                    moved[t.taskId] = (moved[t.taskId] ?: 0) + 1
                    val ms = android.os.SystemClock.elapsedRealtime() - start
                    val from = FrameworkTaskIdentityReader.read(t)?.displayId
                    runCatching { moveTask(t.taskId, displayId) }
                        .onSuccess { AppLog.i("ASSIST ${t.topActivity?.shortClassName} задача ${t.taskId} с дисплея $from -> дисплей $displayId через $ms мс") }
                        .onFailure { AppLog.i("ASSIST перенос задачи ${t.taskId}: ${it.cause ?: it}") }
                }
                android.os.SystemClock.sleep(if (pulling) 40 else 250)
            }
        }, "assist-watch").apply { isDaemon = true; start() }
    }

    /**
     * Закрыть все задачи на дисплее панели перед тем, как его отпустить: иначе Android переносит их на родительский
     * дисплей рабочего стола под лаунчер, и следующий вызов Алисы уходит в эту старую задачу (поездка 05.11).
     */
    fun clearDisplay(ctx: Context, displayId: Int) {
        if (displayId <= 0) return
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val tasks = runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
            .filter { FrameworkTaskIdentityReader.read(it)?.displayId == displayId }
        for (t in tasks) {
            val ok = runCatching {
                val (atm, type) = atm()
                type.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, t.taskId) as? Boolean ?: true
            }.getOrDefault(false)
            AppLog.i("ASSIST панель: задача ${t.taskId} ${t.topActivity?.shortClassName} ${if (ok) "закрыта" else "не закрылась"}")
        }
    }

    /**
     * Gemini: перед запуском закрыть все задачи Google app. Живое главное окно Gemini (MainActivity) Google считает
     * открытым, и ответ уходит в него текстом - без плашки, голоса и Карт (стенд 07.11).
     */
    fun clearPackage(ctx: Context, pkg: String) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val tasks = runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
            .filter { it.topActivity?.packageName == pkg || it.baseIntent.component?.packageName == pkg }
        for (t in tasks) removeTask(t.taskId)
    }

    /**
     * Gemini: закрыть [pkg] на экране телефона (дисплей 0) до вопроса. Ссылку маршрута Gemini отдаёт Картам в живую
     * задачу (START_DELIVERED_TO_TOP), а [watchPhone] видит только новые задачи (стенд 07.11).
     */
    fun closePhonePackage(ctx: Context, pkg: String) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
            .filter { FrameworkTaskIdentityReader.read(it)?.displayId == 0 && it.topActivity?.packageName == pkg }
            .forEach { removeTask(it.taskId) }
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

    /**
     * Алиса открывает Навигатор трамплином LaunchActivity, а тот поднимает MapActivity с маршрутом в старой задаче
     * Навигатора на экране телефона (стенд 05.11: маршрут висел на телефоне). [ms] закрываем задачу [pkg], если она
     * стала верхней на экране телефона: маршрут уже ушёл в плитку.
     */
    fun closePhoneTasks(ctx: Context, pkg: String, ms: Long = 3000L) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val start = android.os.SystemClock.elapsedRealtime()
        val closed = HashSet<Int>()
        while (android.os.SystemClock.elapsedRealtime() - start < ms) {
            @Suppress("DEPRECATION")
            val top = runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
                .firstOrNull { FrameworkTaskIdentityReader.read(it)?.displayId == 0 }
            if (top != null && top.topActivity?.packageName == pkg && closed.add(top.taskId)) {
                AppLog.i("ASSIST $pkg с маршрутом на экране телефона (задача ${top.taskId}, ${top.topActivity?.shortClassName})")
                removeTask(top.taskId)
            }
            android.os.SystemClock.sleep(100)
        }
    }

    /** Маршрут до [geo] (geo:lat,lon) навигатору [pkg], открытому окном на [displayId]: Google Картам - сразу ведение. */
    fun openRoute(ctx: Context, pkg: String, geo: String, displayId: Int) {
        if (displayId <= 0) { AppLog.i("ASSIST окно навигатора не готово, маршрут не отдан"); return }
        val point = geo.removePrefix("geo:")
        val uri = if (pkg == "com.google.android.apps.maps") "google.navigation:q=$point" else "geo:$point?q=$point"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) }
            .onSuccess { AppLog.i("ASSIST маршрут $uri -> $pkg, дисплей $displayId") }
            .onFailure { AppLog.i("ASSIST маршрут $uri -> $pkg: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Алиса только голосом: ASSIST на экране телефона. Яндекс, выбранный помощником, показывает оверлей
     * VoiceInteractionSession; голос (USAGE_MEDIA, CONTENT_TYPE_SPEECH) уходит в ГУ захватом звука рабочего стола (стенд 07.11).
     */
    fun launchPhone(ctx: Context) {
        for (pkg in ALICE_PACKAGES) {
            val intent = Intent(Intent.ACTION_ASSIST).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                AppLog.i("ASSIST Алиса ($pkg) -> экран телефона, только голос")
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.i("ASSIST $pkg не установлен")
            } catch (e: SecurityException) {
                AppLog.i("ASSIST $pkg на телефоне: ${e.message}")
            }
        }
        AppLog.i("ASSIST Алиса не запущена: нужно приложение «Яндекс — с Алисой»")
    }

    @Volatile private var phoneGen = 0

    /**
     * Ловец маршрутов, пока жив лаунчер рабочего стола: новая задача на экране телефона (дисплей 0), в чьём baseIntent
     * маршрут ([geoFor] не null, например yandexnavi://build_route_on_map от Алисы на телефоне) - в [onRoute], один раз
     * на задачу. Задачи, что были до старта, не трогаем. Следующий вызов заменяет прежний ловец. Поточный поток.
     */
    fun watchPhone(ctx: Context, geoFor: (String?) -> String?, onRoute: (android.app.ActivityManager.RunningTaskInfo, String) -> Unit,
        onMusic: ((android.app.ActivityManager.RunningTaskInfo) -> Unit)? = null) {
        val gen = ++phoneGen
        val known = HashSet<Int>()
        Thread({
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList()).forEach { known.add(it.taskId) }
            AppLog.i("ASSIST ловец маршрутов с телефона запущен")
            while (gen == phoneGen) {
                @Suppress("DEPRECATION")
                val tasks = runCatching { am.getRunningTasks(30) }.getOrDefault(emptyList())
                for (t in tasks) {
                    if (!known.add(t.taskId)) continue
                    if (FrameworkTaskIdentityReader.read(t)?.displayId != 0) continue
                    if (onMusic != null && youtubeMusicLink(t)) {
                        AppLog.i("ASSIST YouTube Music на телефоне: задача ${t.taskId} ${t.baseIntent.dataString}")
                        runCatching { onMusic(t) }.onFailure { AppLog.i("ASSIST музыка: ${it.javaClass.simpleName}: ${it.message}") }
                        continue
                    }
                    val geo = geoFor(t.baseIntent.dataString)
                    if (geo == null) {
                        // разбор маршрутов Gemini: что Карты получили на самом деле
                        if (t.topActivity?.packageName == "com.google.android.apps.maps") {
                            AppLog.i("ASSIST Карты на телефоне без маршрута: ${t.baseIntent.action} ${t.baseIntent.dataString}")
                        }
                        continue
                    }
                    AppLog.i("ASSIST маршрут на телефоне: ${t.topActivity?.packageName} задача ${t.taskId} ${t.baseIntent.dataString}")
                    runCatching { onRoute(t, geo) }.onFailure { AppLog.i("ASSIST маршрут: ${it.javaClass.simpleName}: ${it.message}") }
                }
                android.os.SystemClock.sleep(100)
            }
        }, "assist-phone").apply { isDaemon = true; start() }
    }

    fun stopPhoneWatch() { phoneGen++ }

    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    /** Gemini на «включи музыку» открывает YouTube Music ссылкой music.youtube.com (стенд 07.11). */
    private fun youtubeMusicLink(t: android.app.ActivityManager.RunningTaskInfo): Boolean {
        val pkg = t.baseIntent.component?.packageName ?: t.topActivity?.packageName
        return pkg == YOUTUBE_MUSIC && t.baseIntent.dataString?.startsWith("https://music.youtube.com") == true
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
