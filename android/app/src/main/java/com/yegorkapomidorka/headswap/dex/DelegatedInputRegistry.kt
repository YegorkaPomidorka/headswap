// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Used under the gateway monitor; a producer cannot carry display IDs across generations. */
internal class DelegatedInputRegistry {
    data class Entry(val id: Int, val token: Any, val parent: Int?, var width: Int, var height: Int)
    private var expectedParentToken: Any? = null
    private var generation = 0L
    private val displays = mutableMapOf<Int, Entry>()

    fun begin(generation: Long, parentToken: Any) {
        require(generation > 0)
        check(this.generation == 0L) { "Предыдущий экран ещё активен" }
        this.generation = generation
        expectedParentToken = parentToken
        displays.clear()
    }

    fun deactivate() { generation = 0; expectedParentToken = null; displays.clear() }

    fun registeredDisplayIds(): Set<Int> = displays.keys.toSet()

    fun registerParent(id: Int, token: Any, width: Int, height: Int) {
        requireParentGeometry(id, width, height)
        check(generation > 0 && token == expectedParentToken) { "Сеанс экрана уже завершён" }
        check(displays.isEmpty()) { "Основной экран уже зарегистрирован" }
        displays[id] = Entry(id, token, null, width, height)
    }

    fun registerChild(parent: Int, id: Int, token: Any, width: Int, height: Int) {
        require(id > 0) { "Основной экран телефона запрещён" }
        EmbeddedDisplayPolicy.requireDimensions(width, height)
        check(generation > 0 && displays[parent]?.parent == null && displays.containsKey(parent)) { "Родительский экран недоступен" }
        check(id != parent && !displays.containsKey(id) && displays.size < 4 && token != expectedParentToken &&
            displays.values.none { it.token == token }) { "Недопустимый экран виджета" }
        requireChildBudget(id, width, height)
        displays[id] = Entry(id, token, parent, width, height)
    }

    fun requireDisplay(id: Int, token: Any): Entry {
        require(id > 0) { "Основной экран телефона запрещён" }
        check(generation > 0) { "Сеанс экрана завершён" }
        return checkNotNull(displays[id]?.takeIf { it.token == token }) { "Экран не принадлежит этому сеансу" }
    }

    fun resize(id: Int, token: Any, width: Int, height: Int) {
        val entry = requireDisplay(id, token)
        if (entry.parent == null) requireParentGeometry(id, width, height)
        else requireChildBudget(id, width, height)
        entry.width = width
        entry.height = height
    }

    fun unregister(id: Int, token: Any) {
        val entry = displays[id] ?: return
        check(entry.token == token) { "Неверный владелец экрана" }
        if (entry.parent == null) displays.clear() else displays.remove(id)
    }

    private fun requireChildBudget(id: Int, width: Int, height: Int) {
        EmbeddedDisplayPolicy.requirePixelBudget(displays.values.filter { it.parent != null && it.id != id }
            .sumOf { it.width.toLong() * it.height }, width, height)
    }

    private fun requireParentGeometry(id: Int, width: Int, height: Int) {
        require(id > 0 && com.yegorkapomidorka.headswap.aa.DesktopGeometryPolicy.accepts(width, height)) { "Недопустимые размеры отдельного экрана" }
    }
}
