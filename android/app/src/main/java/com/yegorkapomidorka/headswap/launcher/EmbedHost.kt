package com.yegorkapomidorka.headswap.launcher

import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.embed.EmbeddedSessionRegistry

internal enum class EmbedStatus { IDLE, CONNECTING, STARTING, READY, FAILED }

/** Один дочерний экран: навигатор или «текущее приложение». Лимит реестра - 2 окна, значит слотов ровно два. */
internal class EmbedSlot(val name: String) {
    /** Состояние для UI; пишется с рабочего потока. */
    var status by mutableStateOf(EmbedStatus.IDLE)

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

    val touchQueue = ArrayDeque<TouchPacket>()

    val pkg: String? get() = wantKey?.substringBefore('/')
}

internal class TouchPacket(val action: Int, val index: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray) {
    fun cancel() = TouchPacket(3, 0, ids, xs, ys)
}

/**
 * Дочерние окна лаунчера. Все вызовы реестра блокирующие (до 30 с на Binder родителя), поэтому живут на одном
 * рабочем потоке и не вызываются с главного. Касания приходят с главного потока в очередь, MOVE схлопываются.
 */
internal object EmbedHost {
    private const val TAG = "HeadSwapLauncher"
    private const val RETRY_MS = 500L
    private const val MAX_ATTEMPTS = 40
    private const val CREATE_BUDGET_MS = 20_000L

    val nav = EmbedSlot("nav")
    val app = EmbedSlot("app")
    private val slots = listOf(nav, app)

    private val thread = HandlerThread("headswap-launcher-embed").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val uid = Process.myUid()

    /** Плотность родительского дисплея: дочерние окна живут с ней же. */
    @Volatile var densityDpi = 160

    // --- вызовы с главного потока ---

    /** Нужное содержимое и размер слота. Тот же ключ и размер - ничего не происходит. */
    fun want(slot: EmbedSlot, key: String?, w: Int, h: Int) {
        // другое приложение в то же окно: сразу «запускаем», не показывая на миг прежнее
        if (key != slot.wantKey) slot.status = if (key == null) EmbedStatus.IDLE else EmbedStatus.STARTING
        slot.wantKey = key
        if (w > 0 && h > 0) { slot.wantW = w; slot.wantH = h }
        schedule(slot, 0)
    }

    /** Окно снова показали: если задачи приложения уже нет (Back из корня, процесс убит), запустить заново. */
    fun refresh(slot: EmbedSlot) {
        worker.post { slot.recheck = true; reconcile(slot) }
    }

    fun retry(slot: EmbedSlot) {
        worker.post { slot.attempts = 0; slot.createdAt = SystemClock.uptimeMillis(); reconcile(slot) }
    }

    fun textureAvailable(slot: EmbedSlot, texture: SurfaceTexture) {
        worker.post {
            synchronized(slot.lock) {
                if (slot.texture !== texture) {
                    dropSurface(slot)
                    slot.texture = texture
                    slot.textureDisplay = DexState.displayId
                }
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

    /** Back в окно; если приложение ушло из корня (его задачи больше нет сверху), onRoot вызовется на главном потоке. */
    fun back(slot: EmbedSlot, onClosed: () -> Unit) {
        worker.post {
            if (slot.handle <= 0) { main.post(onClosed); return@post }
            try {
                cancelTouch(slot)
                EmbeddedSessionRegistry.back(uid, slot.handle)
            } catch (e: Exception) { Log.w(TAG, "back rejected", e) }
            worker.postDelayed({
                val pkg = slot.pkg
                val state = if (pkg == null || slot.handle <= 0) 0 else runCatching {
                    EmbeddedSessionRegistry.taskState(uid, slot.handle, pkg)
                }.getOrDefault(-1)
                if (state == 0 || state == 1) { slot.launchedKey = null; main.post(onClosed) }
            }, 450)
        }
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
            Log.w(TAG, "${slot.name}: reconcile failed: ${e.javaClass.simpleName}: ${e.message}")
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
            slot.handle = EmbeddedSessionRegistry.create(uid, surface, token, display, w, h, densityDpi)
            slot.token = token
            slot.leaseDisplay = display
            slot.geomW = w
            slot.geomH = h
            slot.launchedKey = null
        } else if (slot.geomW != w || slot.geomH != h) {
            cancelTouch(slot)
            EmbeddedSessionRegistry.resize(uid, slot.handle, w, h, densityDpi)
            texture.setDefaultBufferSize(w, h)
            slot.geomW = w
            slot.geomH = h
        }
        var launch = slot.launchedKey != key
        if (!launch && slot.recheck) {
            val pkg = key.substringBefore('/')
            val state = runCatching { EmbeddedSessionRegistry.taskState(uid, slot.handle, pkg) }.getOrDefault(-1)
            launch = state == 0 || state == 1
        }
        slot.recheck = false
        if (launch) {
            slot.status = EmbedStatus.STARTING
            val pkg = key.substringBefore('/')
            val cls = key.substringAfter('/')
            EmbeddedSessionRegistry.launch(uid, slot.handle, pkg, cls)
            slot.launchedKey = key
            slot.launchedAt = SystemClock.uptimeMillis()
        }
        slot.attempts = 0
        slot.createdAt = 0L
        if (slot.status == EmbedStatus.STARTING) pollStarted(slot)
        else if (slot.status != EmbedStatus.READY) slot.status = EmbedStatus.READY
    }

    /** Холодный старт: ждём, пока приложение станет верхним на дочернем дисплее, но не дольше 8 с. */
    private fun pollStarted(slot: EmbedSlot) {
        val key = slot.launchedKey ?: return
        val elapsed = SystemClock.uptimeMillis() - slot.launchedAt
        val state = runCatching { EmbeddedSessionRegistry.taskState(uid, slot.handle, key.substringBefore('/')) }.getOrDefault(-1)
        if (state == 2 || elapsed >= PressLogic.COLD_START_MS || (state < 0 && elapsed >= 1500)) {
            slot.status = EmbedStatus.READY
            return
        }
        worker.postAtTime({ if (slot.status == EmbedStatus.STARTING) reconcile(slot) }, slot, SystemClock.uptimeMillis() + 400)
    }

    private fun drainTouch(slot: EmbedSlot) {
        while (true) {
            val p = synchronized(slot.touchQueue) { slot.touchQueue.removeFirstOrNull() } ?: return
            if (slot.handle <= 0 || !slot.interactive) continue
            if (p.action == 0) slot.touchDown = true
            if (!slot.touchDown) continue
            slot.lastPacket = p
            try {
                EmbeddedSessionRegistry.touchPoints(uid, slot.handle, p.action, p.index, p.ids, p.xs, p.ys)
            } catch (e: Exception) { Log.w(TAG, "touch rejected: ${e.javaClass.simpleName}") }
            if (p.action == 1 || p.action == 3) { slot.touchDown = false; slot.lastPacket = null }
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
            runCatching { EmbeddedSessionRegistry.release(uid, handle) }
                .onFailure { Log.w(TAG, "release failed: ${it.javaClass.simpleName}") }
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
