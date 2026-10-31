package com.yegorkapomidorka.headswap.link

/**
 * Свойства транспорта CarLife. Для USB значения ровно те, что были до появления абстракции.
 * kind идёт в лог строкой «транспорт=…» и в WL-лог.
 */
class TransportCaps(
    /** usb-aoa, tcp-debug (один поток с внешним заголовком), tcp6 (6 сокетов Baidu без внешнего заголовка), tcp-adb (те же 6 сокетов через adb forward). */
    val kind: String,
    /** Поток с внешним заголовком [chan u32][len u32] (USB AOA, debug-TCP). */
    val outerHeader: Boolean,
    /** Каждый канал свой сокет, писатель на канал. */
    val separateSockets: Boolean,
    /** Обрыв приходит системным событием (USB_ACCESSORY_DETACHED), а не только EOF. */
    val nativeDetach: Boolean,
) {
    companion object {
        val USB_AOA = TransportCaps("usb-aoa", outerHeader = true, separateSockets = false, nativeDetach = true)
        val TCP_DEBUG = TransportCaps("tcp-debug", outerHeader = true, separateSockets = false, nativeDetach = false)
        val TCP6 = TransportCaps("tcp6", outerHeader = false, separateSockets = true, nativeDetach = false)

        /** ADB-режим старых ГУ (CarLife 3.0): формат сокетов как tcp6, но поведение сессии как у кабеля (не «воздух»). */
        val TCP_ADB = TransportCaps("tcp-adb", outerHeader = false, separateSockets = true, nativeDetach = false)
    }
}

/**
 * Транспорт CarLife на уровне «канал + inner-сообщение» (inner = внутренний заголовок 8/12 байт + тело).
 * Протокол (CarLifeSession) не знает, как каналы лежат на проводе: один поток с внешним заголовком или свой сокет на канал.
 */
interface CarTransport {
    val caps: TransportCaps

    /** Для лога и экрана: описание аксессуара или адреса ГУ. */
    val label: String

    /**
     * Запуск чтения и записи. Вызовы sink сериализованы (одна нить за раз), onWritten тоже.
     * onClosed вызывается не больше одного раза и только при обрыве со стороны транспорта, не после close().
     */
    fun start(
        sink: (chan: Int, inner: ByteArray) -> Unit,
        onWritten: (chan: Int, inner: ByteArray) -> Unit,
        onClosed: (reason: String) -> Unit,
    )

    /**
     * Запись как у Baidu: 8 байт внешнего заголовка одним write, весь inner одним write (h242, Mobis CCNC).
     * Нужна только потоку с внешним заголовком; транспорты без него игнорируют.
     */
    fun setWholeWrites(on: Boolean) {}

    /** Неблокирующая постановка inner в очередь канала. */
    fun send(chan: Int, inner: ByteArray)

    /** Закрыть всё, разбудить нити. Идемпотентно, onClosed не зовёт. */
    fun close()

    /** Принято байт от ГУ (все каналы). */
    val rxBytes: Long

    /** Сообщений в очереди, где стоит канал chan (у потока одна общая очередь на все каналы). */
    fun queueSize(chan: Int): Int

    /**
     * Выбросить из очереди ещё не записанные inner канала chan, для которых [pred] истинен; сколько выброшено.
     * Видео-счётчики не трогает (звук туда не входит). По умолчанию ничего не чистит.
     */
    fun dropQueued(chan: Int, pred: (ByteArray) -> Boolean): Int = 0

    val queuedVideoBytes: Long
    val queuedVideoFrames: Int

    /** Скорость слива видео, байт/с (0 = ещё не измерена). */
    val drainBytesPerSec: Double
}
