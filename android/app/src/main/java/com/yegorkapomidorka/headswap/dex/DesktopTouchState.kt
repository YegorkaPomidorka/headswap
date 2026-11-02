// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Two-contact CarLife gestures. Invalid packets cannot invent contacts or mutate a live gesture. */
internal class DesktopTouchState {
    data class Point(val id: Int, val x: Float, val y: Float)
    data class Event(val action: Int, val actionIndex: Int, val points: List<Point>)
    private var active = emptyList<Point>()

    fun events(action: Int, index: Int, ids: IntArray, xs: FloatArray, ys: FloatArray,
        width: Int, height: Int): List<Event> {
        validate(action, index, ids, xs, ys)
        require(width > 0 && height > 0)
        val points = ids.indices.map { Point(ids[it], xs[it].coerceIn(0f, (width - 1).toFloat()),
            ys[it].coerceIn(0f, (height - 1).toFloat())) }
        val oldIds = active.map { it.id }.toSet()
        val newIds = ids.toSet()
        if (action == 3) return listOfNotNull(cancel())
        if (action == 0) {
            val previous = cancel()
            active = points
            return listOfNotNull(previous, Event(0, 0, points))
        }
        // An interrupted stream must restart with DOWN, never manufacture a gesture from MOVE.
        if (active.isEmpty()) return emptyList()
        when (action) {
            1 -> require(active.size == 1 && oldIds == newIds) { "Unexpected final contact" }
            2 -> require(oldIds == newIds) { "Contacts changed without pointer event" }
            5 -> require(active.size == 1 && points.size == 2 && ids[index] !in oldIds &&
                newIds - ids[index] == oldIds) { "Unexpected new contact" }
            6 -> require(active.size == 2 && points.size == 2 && newIds == oldIds) { "Unexpected contact release" }
        }
        active = when (action) {
            1 -> emptyList()
            6 -> points.filterIndexed { i, _ -> i != index }
            else -> points
        }
        return listOf(Event(action, index, points))
    }

    fun cancel(): Event? = active.takeIf { it.isNotEmpty() }?.let {
        active = emptyList()
        Event(3, 0, it)
    }

    companion object {
        fun validate(action: Int, index: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
            require(action in setOf(0, 1, 2, 3, 5, 6)) { "Unsupported touch action" }
            require(ids.size in 1..2 && xs.size == ids.size && ys.size == ids.size &&
                ids.distinct().size == ids.size && ids.all { it in 0..31 } &&
                xs.all { it.isFinite() } && ys.all { it.isFinite() }) { "Invalid touch contacts" }
            require(index in ids.indices && (action in setOf(5, 6) || index == 0)) { "Invalid contact index" }
            require(action !in setOf(0, 1) || ids.size == 1) { "DOWN/UP requires one contact" }
            require(action !in setOf(5, 6) || ids.size == 2) { "Pointer action requires two contacts" }
        }
    }
}
