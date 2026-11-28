package com.yegorkapomidorka.headswap.aa

/**
 * Honda 20772122 (h1235): ГУ сбрасывает USB после IDR ≥ ~605 КиБ, битрейт размер IDR не держит. IDR больше [limit]
 * в ГУ не уходит: запрашиваем новый, P-кадры без опорного IDR выбрасываем, пока не придёт IDR, который влезает.
 */
class IdrSizeGuard(private val limit: Int) {
    enum class Verdict { SEND, DROP_REQUEST_IDR, DROP }

    private var waiting = false

    /** Сколько P-кадров выброшено за последний режим ожидания; читать сразу после выхода из него. */
    var lastDroppedP = 0
        private set
    private var droppedP = 0

    /** true, если предыдущее решение вывело из режима ожидания (пришёл IDR, который влез). */
    var justRecovered = false
        private set

    fun decide(size: Int, idr: Boolean): Verdict {
        justRecovered = false
        if (idr) {
            if (size > limit) {
                waiting = true
                return Verdict.DROP_REQUEST_IDR
            }
            if (waiting) {
                waiting = false
                justRecovered = true
                lastDroppedP = droppedP
                droppedP = 0
            }
            return Verdict.SEND
        }
        if (!waiting) return Verdict.SEND
        droppedP++
        return Verdict.DROP
    }

    fun reset() {
        waiting = false
        droppedP = 0
        justRecovered = false
    }
}
