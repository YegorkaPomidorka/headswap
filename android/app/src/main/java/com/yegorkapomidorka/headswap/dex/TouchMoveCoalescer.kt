package com.yegorkapomidorka.headswap.dex

/**
 * Не больше одного ждущего MOVE в очереди ввода рабочего стола. ГУ шлёт до ~70 MOVE/с, а слабый телефон
 * (TECNO KM5, h1316) делает injectTouchPoints медленнее: за 4 с свайпа очередь на 128 команд переполнялась
 * и сессия падала. Новый MOVE заменяет ещё не отправленный; DOWN/UP и прочие события — барьер,
 * MOVE их не обгоняет и между жестами не склеивается.
 */
internal class TouchMoveCoalescer<T : Any> {
    class Slot<T : Any> internal constructor(internal val token: Long, internal var value: T?)

    private val lock = Any()
    private var pending: Slot<T>? = null

    /** Новый слот, который надо поставить в очередь, или null: значение влито в уже ждущий MOVE. */
    fun offerMove(token: Long, value: T): Slot<T>? = synchronized(lock) {
        val slot = pending
        if (slot != null && slot.token == token && slot.value != null) { slot.value = value; null }
        else Slot(token, value).also { pending = it }
    }

    /** Событие не-MOVE или MOVE не попал в очередь: следующий MOVE пойдёт новым слотом. */
    fun barrier() = synchronized(lock) { pending = null }

    /** Забрать последнее значение слота при исполнении; второй раз — null. */
    fun take(slot: Slot<T>): T? = synchronized(lock) { slot.value.also { slot.value = null } }
}
