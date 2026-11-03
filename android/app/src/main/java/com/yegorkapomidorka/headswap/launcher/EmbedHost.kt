package com.yegorkapomidorka.headswap.launcher

import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.embed.EmbeddedSessionRegistry

internal enum class EmbedStatus { IDLE, CONNECTING, STARTING, READY, FAILED }

/** Один дочерний экран: навигатор или «текущее приложение». Лимит реестра - 2 окна, значит слотов ровно два. */
internal class EmbedSlot(val name: String) {
    private var statusValue by mutableStateOf(EmbedStatus.IDLE)

    /** Состояние для UI; пишется с рабочего потока. Смена попадает в лог: по ней видно, что показывал слой. */
    var status: EmbedStatus
        get() = statusValue
        set(value) {
            if (value == statusValue) return
            AppLog.i("EMBED $name: статус $statusValue -> $value", false)
            statusValue = value
        }

    /** Желаемое содержимое «пакет/класс» и размер дисплея в px; null - окно не нужно. */
    @Volatile var wantKey: String? = null
    @Volatile var wantW = 0
    @Volatile var wantH = 0
    /** Касания в окно пересылаются (в карточке навигатора - нет: тап = развернуть). */
    @Volatile var interactive = false

    // Поверхность принадлежит слоту, а не TextureView: при пересоздании Activity текстура остаётся
    // живой под работающим продюсером (onSurfaceTextureDestroyed возвращает false).
    val lock = Any()
    var texture: SurfaceTexture? = null
    var textureDisplay = -1

    // Только рабочий поток.
    var surface: Surface? = null
    var handle = -1
    var token: Binder? = null
    var leaseDisplay = -1
    @Volatile var geomW = 0
    @Volatile var geomH = 0
    var launchedKey: String? = null
    var launchedAt = 0L
    var createdAt = 0L
    var attempts = 0
    var recheck = false
    var touchDown = false
    var lastPacket: TouchPacket? = null
    var scheduled = false

    /** Последний ответ taskState: 2 - сверху, 1 - в фоне, 0 - задачи нет, -1 - не удалось узнать; -2 - ещё не спрашивали. */
    var lastState = -2
    /** С запуска хоть раз видели приложение сверху: только тогда «задачи нет» значит «закрыто», а не «ещё не появилась». */
    var seenTop = false
    /** Касания не пересылаются после «задача закрыта». Снимается запуском или state 2. */
    @Volatile var touchBlocked = false
    /** uptime последнего Back: первую секунду после него касания не идут (TaskCheck.BACK_BLOCK_MS), потом снова при любом state. */
    @Volatile var backAt = 0L
    /** После Back состояние осталось неизвестным (-1): при следующем показе приложение запускается заново. */
    var stale = false
    var dropped = 0
    val watchToken = Any()

    val touchQueue = ArrayDeque<TouchPacket>()

    val pkg: String? get() = wantKey?.substringBefore('/')
}

internal class TouchPacket(
    val action: Int, val index: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray,
    val at: Long = SystemClock.uptimeMillis(),
) {
    fun cancel() = TouchPacket(3, 0, ids, xs, ys, at)
}

/**
 * Дочерние окна лаунчера. Все вызовы реестра блокирующие (до 30 с на Binder родителя), поэтому живут на одном
 * рабочем потоке и не вызываются с главного. Касания приходят с главного потока в очередь, MOVE схлопываются.
 */
internal object EmbedHost {
    private const val RETRY_MS = 500L
    private const val MAX_ATTEMPTS = 40
    private const val CREATE_BUDGET_MS = 20_000L
    /** Вызов реестра дольше этого попадает в лог: так видно, что рабочий поток завис на Binder. */
    private const val SLOW_MS = 300L
    private const val WATCH_MS = 2000L
    /** Касание, простоявшее в очереди дольше (рабочий поток ждал Binder), уже не нужно. */
    private const val STALE_TOUCH_MS = 1000L

    val nav = EmbedSlot("nav")
    val app = EmbedSlot("app")
    private val slots = listOf(nav, app)

    private val thread = HandlerThread("headswap-launcher-embed").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val uid = Process.myUid()

    /** Плотность родительского дисплея: дочерние окна живут с ней же. */
    @Volatile var densityDpi = 160

    /** Задача приложения на дочернем дисплее исчезла (Back из корня, процесс убит). Вызывается на главном потоке. */
    @Volatile var onGone: ((EmbedSlot) -> Unit)? = null

    // --- вызовы с главного потока ---

    /** Нужное содержимое и размер слота. Тот же ключ и размер - ничего не происходит. */
    fun want(slot: EmbedSlot, key: String?, w: Int, h: Int) {
        // другое приложение в то же окно: сразу «запускаем», не показывая на миг прежнее
        if (key != slot.wantKey) slot.status = if (key == null) EmbedStatus.IDLE else EmbedStatus.STARTING
        slot.wantKey = key
        if (w > 0 && h > 0) { slot.wantW = w; slot.wantH = h }
        schedule(slot, 0)
    }

    /**
     * Окно снова показали или тапнули по уже открытому: если задачи приложения нет (Back из корня, процесс убит)
     * либо состояние после Back осталось неизвестным, запустить заново.
     */
    fun refresh(slot: EmbedSlot) {
        worker.post { slot.recheck = true; reconcile(slot) }
    }

    /** Пакеты задач дочернего дисплея слота (свежие первыми) для «Недавних»; [onResult] на главном потоке, null - не удалось узнать. */
    fun taskPackages(slot: EmbedSlot, onResult: (List<String>?) -> Unit) {
        worker.post {
            val result = if (slot.handle <= 0) null else try {
                timed(slot, "taskPackages") { EmbeddedSessionRegistry.taskPackages(uid, slot.handle) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: taskPackages не удался: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) lostLease(slot)
                null
            }
            main.post { onResult(result) }
        }
    }

    fun retry(slot: EmbedSlot) {
        worker.post { slot.attempts = 0; slot.createdAt = SystemClock.uptimeMillis(); reconcile(slot) }
    }

    fun textureAvailable(slot: EmbedSlot, texture: SurfaceTexture) {
        worker.post {
            val old = synchronized(slot.lock) {
                val previous = slot.texture
                if (previous !== texture) {
                    slot.texture = texture
                    slot.textureDisplay = DexState.displayId
                }
                previous.takeIf { it !== texture }
            }
            if (old != null) {
                // окно жило на прежней поверхности: освобождаем его по-настоящему, reconcile создаст новое на этой
                if (slot.handle > 0) releaseLease(slot) else dropSurface(slot)
                runCatching { old.release() }
            }
            reconcile(slot)
        }
    }

    /** Текстуру, оставшуюся от прошлого рабочего стола, выбрасываем: её продюсер уже мёртв. */
    fun adoptable(slot: EmbedSlot): SurfaceTexture? = synchronized(slot.lock) {
        val t = slot.texture
        if (t != null && !t.isReleased && slot.textureDisplay == DexState.displayId) t else null
    }

    fun touch(slot: EmbedSlot, ev: MotionEvent, viewW: Int, viewH: Int) {
        val action = ev.actionMasked
        if (action !in intArrayOf(0, 1, 2, 3, 5, 6)) return
        val count = ev.pointerCount
        if (count > 2 || viewW <= 0 || viewH <= 0) return
        val gw = slot.geomW
        val gh = slot.geomH
        if (gw <= 0 || gh <= 0) return
        val sx = gw.toFloat() / viewW
        val sy = gh.toFloat() / viewH
        val ids = IntArray(count) { ev.getPointerId(it) }
        val xs = FloatArray(count) { (ev.getX(it) * sx).coerceIn(0f, gw - 1f) }
        val ys = FloatArray(count) { (ev.getY(it) * sy).coerceIn(0f, gh - 1f) }
        val packet = TouchPacket(action, if (action == 5 || action == 6) ev.actionIndex else 0, ids, xs, ys)
        synchronized(slot.touchQueue) {
            val last = slot.touchQueue.lastOrNull()
            if (action == 2 && last != null && last.action == 2 && last.ids.size == count) slot.touchQueue.removeLast()
            slot.touchQueue.addLast(packet)
        }
        worker.post { drainTouch(slot) }
    }

    /**
     * Back в окно; если приложение ушло из корня (его задачи больше нет сверху), onClosed вызовется на главном потоке.
     * Задачу проверяем несколько раз: у закрывающейся активности top ещё держится, а ответ -1 (список задач
     * недоступен) раньше навсегда оставлял слой поверх пустого дисплея.
     */
    fun back(slot: EmbedSlot, onClosed: () -> Unit) {
        worker.post {
            AppLog.i("EMBED ${slot.name}: Back handle=${slot.handle} pkg=${slot.pkg} статус=${slot.status} " +
                "state=${slot.lastState} касаний отброшено=${slot.dropped}", false)
            if (slot.handle <= 0) {
                AppLog.i("EMBED ${slot.name}: окна нет, Back = на главный", false)
                main.post(onClosed)
                return@post
            }
            slot.backAt = SystemClock.uptimeMillis()
            try {
                cancelTouch(slot)
                timed(slot, "back") { EmbeddedSessionRegistry.back(uid, slot.handle) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: Back отклонён: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) {
                    lostLease(slot)
                    AppLog.i("EMBED ${slot.name}: окно закрыто реестром, Back = на главный", false)
                    main.post(onClosed)
                    return@post
                }
            }
            worker.postDelayed({ checkAfterBack(slot, 0, onClosed) }, TaskCheck.BACK_CHECKS[0])
        }
    }

    private fun checkAfterBack(slot: EmbedSlot, attempt: Int, onClosed: () -> Unit) {
        val state = queryState(slot, "back#$attempt", strict = true)
        val last = attempt >= TaskCheck.BACK_CHECKS.size - 1
        when (TaskCheck.afterBack(state, last)) {
            TaskCheck.AfterBack.CLOSED -> {
                AppLog.i("EMBED ${slot.name}: после Back задачи на экране нет (state=$state), возврат на главный", false)
                slot.launchedKey = null
                slot.seenTop = false
                slot.backAt = 0L
                main.post(onClosed)
            }
            TaskCheck.AfterBack.STAYED_MORE -> {
                slot.backAt = 0L // приложение приняло Back внутри себя, касания снова идут
                worker.postDelayed({ checkAfterBack(slot, attempt + 1, onClosed) }, TaskCheck.BACK_CHECKS[attempt + 1])
            }
            TaskCheck.AfterBack.STAYED -> {
                slot.backAt = 0L
                AppLog.i("EMBED ${slot.name}: после Back приложение осталось сверху", false)
            }
            TaskCheck.AfterBack.UNKNOWN_MORE ->
                worker.postDelayed({ checkAfterBack(slot, attempt + 1, onClosed) }, TaskCheck.BACK_CHECKS[attempt + 1])
            TaskCheck.AfterBack.UNKNOWN -> {
                // узнать не удалось за 1,5 с: считаем, что приложение ушло из корня; следующий показ запустит его заново
                AppLog.i("EMBED ${slot.name}: после Back состояние неизвестно (state=$state), считаю закрытым, возврат на главный", false)
                slot.stale = true
                slot.launchedKey = null
                slot.seenTop = false
                slot.backAt = 0L
                main.post(onClosed)
            }
        }
    }

    /** Спросить реестр о задаче слота. Окно, которое реестр уже закрыл, считается «задачи нет» (0). Только рабочий поток. */
    private fun queryState(slot: EmbedSlot, source: String, strict: Boolean = false): Int {
        val pkg = slot.pkg
        val raw = if (pkg == null || slot.handle <= 0) 0 else try {
            timed(slot, "taskState") { EmbeddedSessionRegistry.taskState(uid, slot.handle, pkg) }
        } catch (e: Exception) {
            AppLog.i("EMBED ${slot.name}: taskState ($source) не удался: ${e.javaClass.simpleName}: ${e.message}", false)
            if (leaseGone(e)) { lostLease(slot); 0 } else -1
        }
        // «не виден» (3) различают только Back и повторный показ; запуск и наблюдатель считают такое приложение сверху
        val state = if (raw == TaskCheck.MOVED_TO_BACK && !strict) TaskCheck.TOP else raw
        if (raw == TaskCheck.TOP || raw == TaskCheck.MOVED_TO_BACK) slot.seenTop = true
        if (state != slot.lastState || raw != state || source.startsWith("back")) {
            AppLog.i("EMBED ${slot.name}: taskState=$raw ($source) pkg=$pkg статус=${slot.status}", false)
        }
        slot.lastState = state
        return state
    }

    /** Вызов реестра; если он занял больше [SLOW_MS], строка в логе. */
    private inline fun <T> timed(slot: EmbedSlot, what: String, block: () -> T): T {
        val t0 = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            val dt = SystemClock.uptimeMillis() - t0
            if (dt >= SLOW_MS) AppLog.i("EMBED ${slot.name}: $what занял $dt мс", false)
        }
    }

    /** Приложение пропало с дочернего дисплея: слой надо убрать с экрана, касания в пустоту не слать. */
    private fun gone(slot: EmbedSlot, why: String) {
        AppLog.i("EMBED ${slot.name}: задача приложения исчезла ($why), pkg=${slot.pkg}", false)
        slot.launchedKey = null
        slot.seenTop = false
        slot.touchBlocked = true
        main.post { onGone?.invoke(slot) }
    }

    /** Пока слой на экране и принимает касания, раз в 2 с проверяем, что задача на месте (процесс убит, Back из корня). */
    private fun watchTick(slot: EmbedSlot) {
        if (slot.wantKey == null || slot.handle <= 0 || slot.status != EmbedStatus.READY) return
        if (slot.interactive && !slot.touchBlocked && slot.backAt == 0L) { // проверки после Back идут своим ходом
            val state = queryState(slot, "watch")
            if (TaskCheck.vanished(state, slot.seenTop)) { gone(slot, "watch"); return }
        }
        worker.postAtTime({ watchTick(slot) }, slot.watchToken, SystemClock.uptimeMillis() + WATCH_MS)
    }

    private fun startWatch(slot: EmbedSlot) {
        worker.removeCallbacksAndMessages(slot.watchToken)
        worker.postAtTime({ watchTick(slot) }, slot.watchToken, SystemClock.uptimeMillis() + WATCH_MS)
    }

    // --- рабочий поток ---

    private fun schedule(slot: EmbedSlot, delayMs: Long) {
        worker.removeCallbacksAndMessages(slot)
        worker.postAtTime({ reconcile(slot) }, slot, SystemClock.uptimeMillis() + delayMs)
    }

    private fun reconcile(slot: EmbedSlot) {
        try {
            reconcileInternal(slot)
        } catch (e: Exception) {
            AppLog.i("EMBED ${slot.name}: reconcile не удался: ${e.javaClass.simpleName}: ${e.message}", false)
            if (slot.handle > 0 && leaseGone(e)) lostLease(slot)
            retryLater(slot)
        }
    }

    /** Реестр говорит, что окна больше нет (закрыто, сеанс рабочего стола завершился). */
    private fun leaseGone(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return e is android.os.DeadObjectException || m.contains("закрыто") || m.contains("завершился")
    }

    /** Реестр отвечает «не готов» первые секунды сеанса: повторяем до 40 раз и 20 с, как клиент-образец. */
    private fun retryLater(slot: EmbedSlot) {
        if (slot.wantKey == null) return
        val now = SystemClock.uptimeMillis()
        if (slot.createdAt == 0L) slot.createdAt = now
        if (slot.attempts++ >= MAX_ATTEMPTS || now - slot.createdAt > CREATE_BUDGET_MS) {
            AppLog.i("EMBED ${slot.name}: попытки исчерпаны (${slot.attempts}), окно не открылось", false)
            slot.status = EmbedStatus.FAILED
            return
        }
        schedule(slot, RETRY_MS)
    }

    private fun reconcileInternal(slot: EmbedSlot) {
        val key = slot.wantKey
        if (key == null) { releaseLease(slot); slot.status = EmbedStatus.IDLE; return }
        val display = DexState.displayId
        if (display <= 0) { slot.status = EmbedStatus.CONNECTING; schedule(slot, 1000); return }
        if (slot.handle > 0 && slot.leaseDisplay != display) lostLease(slot) // новый сеанс рабочего стола
        val texture = synchronized(slot.lock) { slot.texture }
        if (texture == null || texture.isReleased) { slot.status = EmbedStatus.CONNECTING; return }
        val w = slot.wantW
        val h = slot.wantH
        if (w < 128 || h < 128) return
        if (slot.handle <= 0) {
            slot.status = EmbedStatus.CONNECTING
            val surface = slot.surface ?: Surface(texture).also { slot.surface = it }
            texture.setDefaultBufferSize(w, h)
            val token = Binder()
            slot.handle = timed(slot, "create") { EmbeddedSessionRegistry.create(uid, surface, token, display, w, h, densityDpi) }
            AppLog.i("EMBED ${slot.name}: окно создано handle=${slot.handle} display=$display ${w}x$h dpi=$densityDpi", false)
            slot.token = token
            slot.leaseDisplay = display
            slot.geomW = w
            slot.geomH = h
            slot.launchedKey = null
        } else if (slot.geomW != w || slot.geomH != h) {
            cancelTouch(slot)
            AppLog.i("EMBED ${slot.name}: размер ${slot.geomW}x${slot.geomH} -> ${w}x$h", false)
            timed(slot, "resize") { EmbeddedSessionRegistry.resize(uid, slot.handle, w, h, densityDpi) }
            texture.setDefaultBufferSize(w, h)
            slot.geomW = w
            slot.geomH = h
        }
        var launch = slot.launchedKey != key
        if (!launch && slot.recheck) {
            // -1 (список задач недоступен) перезапуск вызывает только после Back с неизвестным итогом
            val state = queryState(slot, "refresh", strict = true)
            launch = TaskCheck.needsRelaunch(state, slot.stale)
            if (state == 2) slot.touchBlocked = false
        }
        slot.recheck = false
        if (launch) {
            slot.status = EmbedStatus.STARTING
            val pkg = key.substringBefore('/')
            val cls = key.substringAfter('/')
            AppLog.i("EMBED ${slot.name}: запуск $key handle=${slot.handle} (state=${slot.lastState} stale=${slot.stale})", false)
            timed(slot, "launch") { EmbeddedSessionRegistry.launch(uid, slot.handle, pkg, cls) }
            slot.launchedKey = key
            slot.launchedAt = SystemClock.uptimeMillis()
            slot.stale = false
            slot.seenTop = false
            slot.touchBlocked = false
            slot.backAt = 0L
        }
        slot.attempts = 0
        slot.createdAt = 0L
        if (slot.status == EmbedStatus.STARTING) pollStarted(slot)
        else {
            if (slot.status != EmbedStatus.READY) slot.status = EmbedStatus.READY
            startWatch(slot)
        }
    }

    /** Холодный старт: ждём, пока приложение станет верхним на дочернем дисплее, но не дольше 8 с. */
    private fun pollStarted(slot: EmbedSlot) {
        val key = slot.launchedKey ?: return
        val elapsed = SystemClock.uptimeMillis() - slot.launchedAt
        val state = queryState(slot, "start")
        if (state == 2 || elapsed >= PressLogic.COLD_START_MS || (state < 0 && elapsed >= 1500)) {
            if (state != 2) AppLog.i("EMBED ${slot.name}: готовым считаю без подтверждения (state=$state, ${elapsed} мс после запуска)", false)
            slot.status = EmbedStatus.READY
            startWatch(slot)
            return
        }
        worker.postAtTime({ if (slot.status == EmbedStatus.STARTING) reconcile(slot) }, slot, SystemClock.uptimeMillis() + 400)
    }

    private fun drainTouch(slot: EmbedSlot) {
        while (true) {
            val p = synchronized(slot.touchQueue) { slot.touchQueue.removeFirstOrNull() } ?: return
            if (slot.handle <= 0 || !slot.interactive) continue
            // в дисплей без живой задачи касания не идут: после Back до подтверждения, после «задача закрыта», до готовности
            val reason = when {
                slot.touchBlocked -> "заблокированы (задача закрыта)"
                TaskCheck.blockedAfterBack(SystemClock.uptimeMillis(), slot.backAt) -> "заблокированы (сразу после Back)"
                slot.status != EmbedStatus.READY -> "окно ещё не готово (${slot.status})"
                p.action == 0 && SystemClock.uptimeMillis() - p.at > STALE_TOUCH_MS -> "устарели в очереди"
                else -> null
            }
            if (reason != null) {
                if (p.action == 0) noteDropped(slot, reason)
                continue
            }
            if (p.action == 0) slot.touchDown = true
            if (!slot.touchDown) continue
            slot.lastPacket = p
            try {
                timed(slot, "touch") { EmbeddedSessionRegistry.touchPoints(uid, slot.handle, p.action, p.index, p.ids, p.xs, p.ys) }
            } catch (e: Exception) {
                AppLog.i("EMBED ${slot.name}: касание отклонено: ${e.javaClass.simpleName}: ${e.message}", false)
                if (leaseGone(e)) { lostLease(slot); gone(slot, "касание") }
            }
            if (p.action == 1 || p.action == 3) { slot.touchDown = false; slot.lastPacket = null }
        }
    }

    /** Отброшенные касания: строка на первое и дальше на каждое 20-е, чтобы лог не забивался. */
    private fun noteDropped(slot: EmbedSlot, reason: String) {
        slot.dropped++
        if (slot.dropped == 1 || slot.dropped % 20 == 0) {
            AppLog.i("EMBED ${slot.name}: касание отброшено, $reason (всего отброшено ${slot.dropped})", false)
        }
    }

    private fun cancelTouch(slot: EmbedSlot) {
        synchronized(slot.touchQueue) { slot.touchQueue.clear() }
        val last = slot.lastPacket
        if (!slot.touchDown || last == null) return
        slot.touchDown = false
        slot.lastPacket = null
        val c = last.cancel()
        runCatching { EmbeddedSessionRegistry.touchPoints(uid, slot.handle, c.action, c.index, c.ids, c.xs, c.ys) }
    }

    /** Реестр уже потерял окно (сеанс рабочего стола кончился): забываем его без вызова release. */
    private fun lostLease(slot: EmbedSlot) {
        AppLog.i("EMBED ${slot.name}: окно потеряно (handle=${slot.handle})", false)
        slot.handle = -1
        slot.token = null
        slot.launchedKey = null
        slot.touchDown = false
        slot.lastPacket = null
        slot.geomW = 0
        slot.geomH = 0
    }

    private fun releaseLease(slot: EmbedSlot) {
        val handle = slot.handle
        if (handle > 0) {
            cancelTouch(slot)
            AppLog.i("EMBED ${slot.name}: release handle=$handle", false)
            runCatching { timed(slot, "release") { EmbeddedSessionRegistry.release(uid, handle) } }
                .onFailure { AppLog.i("EMBED ${slot.name}: release не удался: ${it.javaClass.simpleName}", false) }
        }
        lostLease(slot)
        // Поверхность освобождается только после подтверждённого release: продюсер мог остаться жив.
        dropSurface(slot)
    }

    private fun dropSurface(slot: EmbedSlot) {
        slot.surface?.let { runCatching { it.release() } }
        slot.surface = null
    }
}
