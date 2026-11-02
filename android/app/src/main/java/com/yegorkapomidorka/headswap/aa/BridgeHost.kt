package com.yegorkapomidorka.headswap.aa

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Жизненный цикл AA-стороны моста (реализует AaConnector). */
interface AaLifecycle {
    fun start()

    /** Корректно закрыть AA (ByeBye, flush, EOF, close); onDone зовётся из отдельной нити, когда всё закрыто. */
    fun stop(onDone: () -> Unit)
}

/** A one-shot source may fail before its host receives the asynchronous failure callback. */
interface TerminalSource {
    val isTerminated: Boolean
}

/** Optional source capability. A true result accepts an asynchronous request, not a hardware ACK. */
interface DesktopPowerControl {
    fun setPhoneScreenOff(off: Boolean): Boolean
}

/** Navigation belongs only to a currently attached desktop session. */
interface DesktopNavigation {
    fun showDesktop(): Boolean
}

/**
 * AA-сторона моста на время жизни СЕРВИСА, а не одной USB-сессии CarLife. Физический обрыв USB (EIO)
 * закрывает CarLife-сессию, но AA-сессия остаётся «тёплой» graceMs: если USB вернулся, новая
 * CarLife-сессия подхватывает живую AA (core.attach + START -> keyframe), если нет или нажат Стоп -
 * AA закрывается корректно. Одноразовый сервер 5277 не должен получать новое соединение, пока
 * предыдущее живо: один коннектор на хост, а новый хост ждёт, пока предыдущий закроется (previous).
 */
class BridgeHost(
    val core: AaBridgeCore,
    private val connector: AaLifecycle,
    private val scheduler: ScheduledExecutorService,
    private val log: (String, Boolean) -> Unit,
    private val graceMs: Long = GRACE_MS,
    private val previous: BridgeHost? = null,
    private val onTornDown: () -> Unit = {},
    private val previousStopTimeoutMs: Long = 15_000,
) {
    private var started = false
    private var tearingDown = false
    private var grace: ScheduledFuture<*>? = null
    private val tornDownLatch = CountDownLatch(1)

    /** AA закрыта полностью (ByeBye, EOF, close). */
    val isTornDown: Boolean get() = tornDownLatch.count == 0L

    /** Хост закрывается или закрыт: новую CarLife-сессию надо вешать на новый хост. */
    @get:Synchronized
    val isDead: Boolean get() = tearingDown || (connector as? TerminalSource)?.isTerminated == true

    @get:Synchronized
    val isInGrace: Boolean get() = grace != null

    fun awaitTornDown(timeoutMs: Long): Boolean = tornDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Never create a source or apply a screen command to a retired / detached USB session. */
    @Synchronized
    fun setPhoneScreenOff(off: Boolean): Boolean {
        if (!started || isDead || grace != null) return false
        return (connector as? DesktopPowerControl)?.setPhoneScreenOff(off) ?: false
    }

    @Synchronized
    fun showDesktop(): Boolean {
        if (!started || isDead || grace != null) return false
        return (connector as? DesktopNavigation)?.showDesktop() ?: false
    }

    /** Новая CarLife-сессия: отменить grace, при первой - запустить AA. false = хост уже закрывается. */
    @Synchronized
    fun onUsbSessionStarted(): Boolean {
        if (isDead) return false
        grace?.let {
            it.cancel(false)
            grace = null
            log("BRIDGE USB вернулся в grace-окно: AA-сессия жива, переиспользую", true)
        }
        if (!started) {
            started = true
            val prev = previous
            if (prev != null && !prev.isTornDown) {
                // сервер 5277 одноразовый: новое соединение только после полного закрытия предыдущего
                log("BRIDGE жду полного закрытия предыдущей AA-сессии перед новым подключением", true)
                Thread({
                    val closed = prev.awaitTornDown(previousStopTimeoutMs)
                    synchronized(this) {
                        if (!tearingDown) {
                            if (closed && !isDead) connector.start()
                            else tearDown("предыдущий источник не подтвердил закрытие; новый запуск отменён")
                        }
                    }
                }, "aa-host-start").start()
            } else {
                connector.start()
            }
        }
        return true
    }

    /** CarLife-сессия закрыта. immediate (кнопка Стоп/выкл. моста) - сразу закрыть AA, иначе держать graceMs. */
    @Synchronized
    fun onUsbSessionClosed(immediate: Boolean) {
        if (tearingDown) return
        if (immediate) {
            tearDown("остановлено пользователем")
            return
        }
        grace?.cancel(false)
        log("BRIDGE USB закрыт: держу AA тёплой ${graceMs} мс", true)
        AaState.status = "мост: USB потерян, AA жива ${graceMs / 1000} с"
        grace = scheduler.schedule({ onGraceExpired() }, graceMs, TimeUnit.MILLISECONDS)
    }

    private fun onGraceExpired() {
        synchronized(this) {
            if (tearingDown || grace == null) return
            grace = null
            tearDown("USB не вернулся за ${graceMs} мс")
        }
    }

    /** Закрыть AA сейчас (Стоп, сервис уничтожен, смена режима). */
    @Synchronized
    fun shutdown(why: String) {
        if (tearingDown) return
        tearDown(why)
    }

    private fun tearDown(why: String) {
        tearingDown = true
        grace?.cancel(false)
        grace = null
        log("BRIDGE закрываю AA ($why): ByeBye, flush, EOF, close", true)
        if (!started) {
            tornDownLatch.countDown()
            onTornDown()
            return
        }
        connector.stop {
            tornDownLatch.countDown()
            onTornDown()
        }
    }

    companion object {
        /** Сколько держать AA-сессию живой после потери USB. */
        const val GRACE_MS = 8_000L
    }
}
