// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.aa.BridgeHost

/** Main-process memory survives USB service grace. No track names, URIs or PCM are logged/saved. */
internal object DesktopMusicPlayback {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private val link = DesktopMusicUsbLink(BridgeHost.GRACE_MS)
    private var ready: (() -> Boolean)? = null
    private var audioReady: (() -> Boolean)? = null
    private var watched: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var mediaManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var selectedListener: MediaSessionManager.OnMediaKeyEventSessionChangedListener? = null
    private val policy = DesktopMusicResumePolicy { reason ->
        AppLog.i("DESKTOP MUSIC: resume cancelled ($reason)", false)
    }
    private val tick = object : Runnable {
        override fun run() {
            val app = context ?: return
            if (!allowed(app)) { clear("access ended"); return }
            val systemReady = receivingVideo()
            if (systemReady) link.markReady()
            if (policy.hasPending) checkPending(systemReady && runCatching { audioReady?.invoke() == true }.getOrDefault(false))
            scheduleNext()
        }
    }

    /** [captureLink]: links where desktop audio may go through our capture (USB-like and tcp6 Wi-Fi). */
    fun attach(context: Context, serviceOwner: Any, owner: Any, captureLink: Boolean,
        ready: () -> Boolean, audioReady: () -> Boolean) = onMain {
        if (!captureLink || !allowed(context)) { clear("unsupported link"); return@onMain }
        this.context = context.applicationContext
        link.attach(serviceOwner, owner, SystemClock.elapsedRealtime())
        this.ready = ready
        this.audioReady = audioReady
        main.removeCallbacks(tick)
        tick.run()
    }

    /**
     * An old session cannot retire a replacement session. Physical detach may be reported later.
     * Call before the capture is detached: [capturing] is whether our capture carried the music to the HU.
     * On physical USB a pause here needs a resumable item: a Samsung read error with the cable still in
     * reconnects in 0.5 s, and an unresumable pause would stay; physical detach still decides that case.
     */
    fun linkClosed(owner: Any, capturing: Boolean, stopRequested: Boolean, physicalUsb: Boolean) = onMain {
        val app = context
        if (link.owner === owner && receivingVideo()) link.markReady() // The first frame may arrive between polls.
        val pause = app != null && link.pauseOnClose(owner, capturing, stopRequested) &&
            allowed(app) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (!link.close(owner, SystemClock.elapsedRealtime())) return@onMain
        ready = null
        audioReady = null
        if (pause && app != null) pauseForLinkLoss(app, "desktop link close", requireResume = physicalUsb)
        scheduleNext()
    }

    fun physicalDetach(serviceOwner: Any) = onMain {
        val app = context ?: return@onMain
        // A failed retry may replace the working session before USB reports physical detach.
        // Consume the service's proof before any Binder call so the old link cannot resume or pause twice.
        if (link.serviceOwner !== serviceOwner || !allowed(app)) return@onMain
        // The selected media-key session API is Android 13+, like the whole delegated access.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@onMain
        if (receivingVideo()) link.markReady() // The first frame may arrive between readiness polls.
        if (!link.consumePhysicalDetach(serviceOwner, SystemClock.elapsedRealtime())) {
            AppLog.i("DESKTOP MUSIC: no pause on physical USB detach (desktop was not ready)", false); return@onMain
        }
        pauseForLinkLoss(app, "physical USB detach", requireResume = false)
    }

    /** Pauses only the one selected local media session; a pause with a known item is resumed on reconnect. */
    private fun pauseForLinkLoss(app: Context, trigger: String, requireResume: Boolean) {
        fun skip(why: String) = AppLog.i("DESKTOP MUSIC: no pause on $trigger ($why)", false)
        if (policy.hasPending) { skip("resume already pending"); return }
        try {
            if (app.checkSelfPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
                skip("no MEDIA_CONTENT_CONTROL"); return
            }
            val manager = app.getSystemService(MediaSessionManager::class.java)
            val selected = manager.mediaKeyEventSession ?: run { skip("no media-key session"); return }
            val active = manager.getActiveSessions(null)
            val playing = active.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val target = playing.singleOrNull()?.takeIf { it.sessionToken == selected } ?: run {
                skip("playing=${playing.size}: ${playing.joinToString { it.packageName }}, selected=${manager.mediaKeyEventSessionPackageName}"); return
            }
            val info = target.playbackInfo
            if (info.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL ||
                info.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) {
                skip("${target.packageName}: type=${info.playbackType} usage=${info.audioAttributes.usage}"); return
            }
            val before = snapshot(target) ?: run { skip("no playback state"); return }
            if (requireResume && before.item == null) { skip("item cannot be resumed; physical detach decides"); return }
            releaseObserver()
            if (!policy.pause(before, SystemClock.elapsedRealtime())) { skip("policy refused"); return }
            if (!policy.hasPending) {
                target.transportControls.pause()
                AppLog.i("DESKTOP MUSIC: paused current session on $trigger; item cannot be safely resumed", false)
                return
            }
            watched = target
            val listener = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (watched !== target || !policy.hasPending) return
                    checkPending(false, playbackCallback = true, event = state)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (watched === target && policy.hasPending) checkPending(false)
                }
                override fun onSessionDestroyed() {
                    if (watched === target) cancelPending("session destroyed")
                }
            }
            callback = listener
            target.registerCallback(listener, main)
            mediaManager = manager
            sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
                if (watched === target && policy.hasPending) checkPending(false)
            }.also { manager.addOnActiveSessionsChangedListener(it, null, main) }
            selectedListener = MediaSessionManager.OnMediaKeyEventSessionChangedListener { _, token ->
                if (watched === target && policy.hasPending && !runCatching {
                    policy.acceptsSelectionEvent(token) { manager.mediaKeyEventSession }
                }.getOrDefault(false)) cancelPending("selected player changed")
            }.also { manager.addOnMediaKeyEventSessionChangedListener(app.mainExecutor, it) }
            target.transportControls.pause()
            AppLog.i("DESKTOP MUSIC: paused current session on $trigger", false)
            main.removeCallbacks(tick)
            tick.run()
        } catch (_: Exception) { cancelPending("pause unavailable") }
    }

    fun serviceDestroyed(serviceOwner: Any, physicallyDetached: Boolean) = onMain {
        if (link.serviceOwner !== serviceOwner) return@onMain
        // Service teardown is not a link loss: never a player decision here.
        link.owner?.let { linkClosed(it, capturing = false, stopRequested = true, physicalUsb = false) }
        // Only a link-loss pause (link close or physical detach) creates this intention. A failed reconnect may end its own
        // service before any new frames arrive; that lifecycle event is not a player decision.
        if (policy.hasPending) {
            link.clear()
            AppLog.i("DESKTOP MUSIC: retaining paused session after USB service ended", false)
            scheduleNext()
        } else if (physicallyDetached) link.clear() else clear("USB service ended")
    }

    /** Also called when Stop is pressed after the foreground service has already left its grace. */
    fun cancel(reason: String) = onMain { clear(reason) }

    private fun checkPending(systemReady: Boolean, playbackCallback: Boolean = false, event: PlaybackState? = null) {
        val app = context ?: return
        val target = watched ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            if (app.checkSelfPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
                cancelPending("media control permission lost"); return
            }
            val manager = app.getSystemService(MediaSessionManager::class.java)
            val selected = manager.mediaKeyEventSession
            val active = manager.getActiveSessions(null)
            val current = active.firstOrNull { it.sessionToken == target.sessionToken }
            val otherPlaying = active.any { it.sessionToken != target.sessionToken && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val value = current?.let { snapshot(it, if (playbackCallback) event else it.playbackState) }
            val hadAcknowledgement = policy.acknowledged
            val resume = policy.takeResume(systemReady, value, selected, otherPlaying, SystemClock.elapsedRealtime())
            if (!hadAcknowledgement && policy.acknowledged)
                AppLog.i("DESKTOP MUSIC: player acknowledged PAUSE; waiting for USB desktop and audio", false)
            if (resume == target.sessionToken) {
                releaseObserver()
                runCatching { target.transportControls.play() }
                    .onSuccess { AppLog.i("DESKTOP MUSIC: sent PLAY to the paused session after USB desktop became ready", false) }
                    .onFailure { AppLog.i("DESKTOP MUSIC: player rejected PLAY (${it.javaClass.simpleName})", false) }
            } else if (!policy.hasPending) releaseObserver()
            scheduleNext()
        } catch (_: Exception) { cancelPending("media session changed or access lost") }
    }

    private fun scheduleNext() {
        main.removeCallbacks(tick)
        val delay = if (link.owner != null && !link.physicallyDetached && (!link.sawReady || policy.hasPending)) 250L
            else policy.nextDeadlineDelay(SystemClock.elapsedRealtime())
        if (delay != null) main.postDelayed(tick, delay)
    }

    private fun receivingVideo(): Boolean = !link.physicallyDetached && link.owner != null &&
        runCatching { ready?.invoke() == true }.getOrDefault(false)

    private fun snapshot(controller: MediaController, state: PlaybackState? = controller.playbackState): DesktopMusicResumePolicy.Playback? {
        state ?: return null
        val metadata = controller.metadata
        val item = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotEmpty() }?.let {
                listOf(it, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()).joinToString("\u0000")
            } ?: state.activeQueueItemId.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }?.let { "queue:$it" }
        return DesktopMusicResumePolicy.Playback(controller.sessionToken, item, when (state.state) {
            PlaybackState.STATE_PLAYING -> DesktopMusicResumePolicy.State.PLAYING
            PlaybackState.STATE_PAUSED -> DesktopMusicResumePolicy.State.PAUSED
            else -> DesktopMusicResumePolicy.State.OTHER
        }, state.position)
    }

    private fun allowed(context: Context): Boolean = DelegatedDesktopAccess.isReady(context)
    private fun releaseObserver() {
        val old = watched; val listener = callback
        watched = null; callback = null
        if (old != null && listener != null) runCatching { old.unregisterCallback(listener) }
        val manager = mediaManager
        sessionsListener?.let { runCatching { manager?.removeOnActiveSessionsChangedListener(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            selectedListener?.let { runCatching { manager?.removeOnMediaKeyEventSessionChangedListener(it) } }
        }
        mediaManager = null; sessionsListener = null; selectedListener = null
    }
    private fun cancelPending(reason: String) {
        if (policy.hasPending) AppLog.i("DESKTOP MUSIC: resume cancelled ($reason)", false)
        policy.cancel()
        releaseObserver()
        scheduleNext()
    }
    private fun clear(reason: String) {
        main.removeCallbacks(tick)
        cancelPending(reason)
        link.clear(); ready = null; audioReady = null; context = null
        main.removeCallbacks(tick)
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}
