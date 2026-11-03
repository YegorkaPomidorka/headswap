// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** A resume belongs to one pause we sent, never to a package name or a global media key. */
internal class DesktopMusicResumePolicy(private val onRejected: (String) -> Unit = {}) {
    enum class State { PLAYING, PAUSED, OTHER }
    data class Playback(val token: Any, val item: String?, val state: State, val position: Long)
    private data class Pending(val before: Playback, val requestedAt: Long, var acknowledgement: Playback? = null,
        var selectionCallbackSeen: Boolean = false)
    private var pending: Pending? = null
    val hasPending: Boolean get() = pending != null
    val acknowledged: Boolean get() = pending?.acknowledgement != null
    /** With the cable absent, callbacks handle changes; only ACK/expiry needs a wakeup. */
    fun nextDeadlineDelay(now: Long): Long? = pending?.let {
        (it.requestedAt + (if (it.acknowledgement == null) PAUSE_ACK_MS else MAX_PENDING_MS) - now + 1).coerceAtLeast(1)
    }

    /** Call immediately before sending PAUSE. Unknown item identity permits pause, but not resume. */
    fun pause(playback: Playback, now: Long): Boolean {
        cancel()
        if (playback.state != State.PLAYING) return false
        if (playback.item != null) pending = Pending(playback, now)
        return true
    }

    /** The first listener notification is the framework's local cache, which may still be null. */
    fun acceptsSelectionEvent(delivered: Any?, readCurrent: () -> Any?): Boolean {
        val attempt = pending ?: return false
        if (attempt.selectionCallbackSeen) return delivered == attempt.before.token
        attempt.selectionCallbackSeen = true
        return readCurrent() == attempt.before.token
    }

    fun observe(playback: Playback?, selected: Any?, anotherPlaying: Boolean, now: Long) {
        val attempt = pending ?: return
        if (now < attempt.requestedAt || now - attempt.requestedAt > MAX_PENDING_MS) return reject("resume expired")
        if (playback == null) return reject("player session unavailable")
        if (playback.token != attempt.before.token) return reject("player session replaced")
        if (anotherPlaying) return reject("another session is playing")
        if (selected != playback.token) return reject("selected player changed")
        if (playback.item != attempt.before.item) return reject("item changed")
        val ack = attempt.acknowledgement
        if (ack == null) {
            if (now - attempt.requestedAt > PAUSE_ACK_MS) return reject("PAUSE acknowledgement timed out")
            when (playback.state) {
                State.PAUSED -> attempt.acknowledgement = playback
                State.PLAYING -> Unit // PAUSE is asynchronous; wait only for the bounded acknowledgement.
                State.OTHER -> reject("player stopped before PAUSE acknowledgement")
            }
        } else {
            if (playback.state != State.PAUSED) return reject("player left PAUSED state")
            // A player can republish PAUSED with fresh timestamps or a rounded playhead. Keep
            // the first known paused position as the anchor so small steps cannot hide a seek.
            if (ack.position < 0 && playback.position >= 0) attempt.acknowledgement = playback
            else if (ack.position >= 0 && playback.position >= 0 &&
                kotlin.math.abs(ack.position - playback.position) > POSITION_TOLERANCE_MS) reject("paused position changed")
        }
    }

    fun takeResume(ready: Boolean, playback: Playback?, selected: Any?, anotherPlaying: Boolean, now: Long): Any? {
        observe(playback, selected, anotherPlaying, now)
        val attempt = pending ?: return null
        if (!ready || attempt.acknowledgement == null) return null
        cancel() // Consume before calling the player; failures never cause another PLAY command.
        return attempt.before.token
    }

    fun cancel() { pending = null }
    private fun reject(reason: String) { cancel(); onRejected(reason) }
    companion object {
        const val PAUSE_ACK_MS = 3_000L
        const val MAX_PENDING_MS = 24 * 60 * 60 * 1_000L
        const val POSITION_TOLERANCE_MS = 1_000L
    }
}
