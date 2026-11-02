package com.yegorkapomidorka.headswap.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class LikeInfo(val action: MusicLogic.Action, val liked: Boolean)

data class MusicState(
    /** Есть ли у процесса право читать сессии (MEDIA_CONTENT_CONTROL после активации с ПК). */
    val access: Boolean = true,
    val hasSession: Boolean = false,
    val pkg: String? = null,
    val title: String = "",
    val artist: String = "",
    val art: ImageBitmap? = null,
    val playing: Boolean = false,
    val like: LikeInfo? = null,
)

/**
 * Музыка телефона через MediaSession. Читает активные сессии раз в секунду (пока карточка на экране);
 * без MEDIA_CONTENT_CONTROL - пустое состояние, ничего не падает.
 */
internal object MusicController {
    private const val TAG = "HeadSwapLauncher"
    var state by mutableStateOf(MusicState())
        private set
    @Volatile private var controller: MediaController? = null
    private var lastArt: Bitmap? = null
    private var lastArtImage: ImageBitmap? = null

    fun refresh(ctx: Context, preferred: String?) {
        if (ctx.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
            controller = null
            if (state.access || state.hasSession) state = MusicState(access = false)
            return
        }
        val sessions = try {
            ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
        } catch (e: SecurityException) {
            controller = null
            state = MusicState(access = false)
            return
        } catch (e: Exception) {
            Log.w(TAG, "sessions: ${e.javaClass.simpleName}")
            return
        }.filter { it.packageName != ctx.packageName }
        val c = MusicLogic.pickSession(sessions, { it.packageName }, { it.playbackState?.state == PlaybackState.STATE_PLAYING }, preferred)
        controller = c
        if (c == null) { state = MusicState(access = true); return }
        val md = c.metadata
        val ps = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val actions = ps?.customActions.orEmpty().map { MusicLogic.Action(it.action, it.name.toString()) }
        val like = MusicLogic.findLike(actions)?.let { LikeInfo(it, MusicLogic.isLiked(it)) }
        val next = MusicState(
            access = true, hasSession = true, pkg = c.packageName, title = title, artist = artist, art = art(md),
            playing = ps?.state == PlaybackState.STATE_PLAYING, like = like,
        )
        if (next != state) state = next
    }

    private fun art(md: MediaMetadata?): ImageBitmap? {
        val bmp: Bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return null
        if (bmp === lastArt) return lastArtImage
        lastArt = bmp
        lastArtImage = bmp.asImageBitmap()
        return lastArtImage
    }

    // --- управление: вызывать не с главного потока нельзя ограничивать, это короткие Binder-вызовы ---

    fun previous() { runCatching { controller?.transportControls?.skipToPrevious() } }
    fun next() { runCatching { controller?.transportControls?.skipToNext() } }

    fun playPause(ctx: Context, fallbackPkg: String?) {
        val c = controller
        if (c == null) { fallbackPkg?.let { startPlayer(ctx, it) }; return }
        runCatching {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
    }

    fun like() {
        val l = state.like ?: return
        runCatching { controller?.transportControls?.sendCustomAction(l.action.id, null) }
    }

    /** Медиаприложения: сервисы MediaBrowserService и пакеты с активной сессией, без самого Headswap. */
    fun players(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val fromServices = runCatching {
            pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0).map { it.serviceInfo.packageName }
        }.getOrDefault(emptyList())
        val fromSessions = runCatching {
            if (ctx.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) emptyList()
            else ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(null).map { it.packageName }
        }.getOrDefault(emptyList())
        return (fromSessions + fromServices).filter { it != ctx.packageName }.distinct()
    }

    /** Сделать плеер текущим: если сессия уже есть - играть, иначе разбудить его MediaBrowserService и нажать play. */
    fun startPlayer(ctx: Context, pkg: String) {
        // Только главный поток (у MediaBrowser Handler); любое исключение здесь убило бы весь процесс моста.
        try {
            controller?.takeIf { it.packageName == pkg }?.let { it.transportControls.play(); return }
            val svc = ctx.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .firstOrNull { it.serviceInfo.packageName == pkg }?.serviceInfo ?: return
            lateinit var browser: MediaBrowser
            browser = MediaBrowser(ctx, ComponentName(pkg, svc.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    runCatching { MediaController(ctx, browser.sessionToken).transportControls.play() }
                    runCatching { browser.disconnect() }
                }
                override fun onConnectionFailed() { Log.w(TAG, "player $pkg refused the browser connection") }
            }, null)
            browser.connect()
        } catch (e: Exception) {
            Log.w(TAG, "startPlayer $pkg: ${e.javaClass.simpleName}")
        }
    }
}
