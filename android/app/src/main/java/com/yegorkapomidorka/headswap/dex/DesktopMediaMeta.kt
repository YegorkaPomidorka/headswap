package com.yegorkapomidorka.headswap.dex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaMediaMetadata
import com.yegorkapomidorka.headswap.aa.AaMediaStatus
import com.yegorkapomidorka.headswap.aa.AaMeta
import com.yegorkapomidorka.headswap.launcher.MusicLogic
import java.io.ByteArrayOutputStream

/**
 * Трек плеера телефона на приборку в режиме рабочего стола (h1054, VW CNS3): в AA его отдаёт ch9, а рабочий стол
 * MEDIA_INFO не слал вовсе. Раз в секунду читает активную MediaSession и кормит ею штатный MetaRelay ядра
 * (MEDIA_INFO + MEDIA_PROGRESS_BAR, пауза = трек не показываем). Нужен MEDIA_CONTENT_CONTROL (делегированный доступ),
 * без него молчит. Только главный поток.
 */
internal object DesktopMediaMeta {
    private const val TICK_MS = 1000L
    private const val ART_SIDE = 256

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var core: AaBridgeCore? = null
    private var noAccessLogged = false
    private var lastMetaKey: String? = null
    private const val ART_RETRIES = 3
    private var lastArtPng: ByteArray? = null
    private var artRetries = 0
    private var lastPkg: String? = null

    private val tick = object : Runnable {
        override fun run() {
            poll()
            if (core != null) main.postDelayed(this, TICK_MS)
        }
    }

    fun start(ctx: Context, target: AaBridgeCore) = onMain {
        context = ctx.applicationContext
        core = target
        noAccessLogged = false
        lastMetaKey = null
        lastPkg = null
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() = onMain {
        main.removeCallbacks(tick)
        core = null
        context = null
        lastMetaKey = null
        lastArtPng = null
        artRetries = 0
    }

    private fun poll() {
        val ctx = context ?: return
        val target = core ?: return
        if (ctx.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) {
            if (!noAccessLogged) AppLog.i("DESKTOP META: нет MEDIA_CONTENT_CONTROL, трек на ГУ не шлю", false)
            noAccessLogged = true
            return
        }
        try {
            val manager = ctx.getSystemService(MediaSessionManager::class.java)
            val sessions = manager.getActiveSessions(null).filter { it.packageName != ctx.packageName }
            val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager.mediaKeyEventSessionPackageName else null
            val c = MusicLogic.pickSession(sessions, { it.packageName }, { it.playbackState?.state == PlaybackState.STATE_PLAYING }, preferred)
            if (c?.packageName != lastPkg) {
                lastPkg = c?.packageName
                AppLog.i("DESKTOP META: плеер ${c?.packageName ?: "нет"}", false)
                // источник до метаданных: MetaRelay кладёт его в source трека
                if (c != null) target.onDesktopMediaStatus(status(c.packageName, null, null))
            }
            if (c == null) { target.onDesktopMediaStatus(status(null, AaMeta.STATE_STOPPED, null)); return }
            val ps = c.playbackState
            val playing = ps?.state == PlaybackState.STATE_PLAYING
            // метаданные раньше статуса: на «заиграл» MetaRelay сразу шлёт отложенный MEDIA_INFO
            meta(c)?.let { target.onMediaMetadata(it) }
            val pos = ps?.let {
                DesktopMediaMetaLogic.positionSeconds(it.position, it.lastPositionUpdateTime, it.playbackSpeed, playing, SystemClock.elapsedRealtime())
            }
            target.onDesktopMediaStatus(status(c.packageName, if (playing) AaMeta.STATE_PLAYING else AaMeta.STATE_PAUSED, pos))
        } catch (e: Exception) {
            AppLog.i("DESKTOP META: сессии недоступны (${e.javaClass.simpleName})", false)
        }
    }

    private fun status(pkg: String?, state: Int?, pos: Int?) = AaMediaStatus(state, pkg, pos, null, null, null)

    /**
     * Новые метаданные или null, если трек тот же. Ключ только из текста: getMetadata() идёт через Binder и каждый раз
     * отдаёт новый Bitmap, по ссылке не сравнить. Обложку кодируем на смене трека; не было - дозапрашиваем ART_RETRIES тиков
     * (плееры часто отдают её позже названия).
     */
    private fun meta(c: MediaController): AaMediaMetadata? {
        val md = c.metadata ?: return null
        val song = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = DesktopMediaMetaLogic.durationSeconds(md.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val key = DesktopMediaMetaLogic.trackKey(c.packageName, song, artist, album, duration)
        if (key == lastMetaKey) {
            if (lastArtPng != null || artRetries <= 0) return null
            artRetries--
            lastArtPng = png(art(md)) ?: return null
        } else {
            lastMetaKey = key
            lastArtPng = png(art(md))
            artRetries = if (lastArtPng == null) ART_RETRIES else 0
        }
        return AaMediaMetadata(song, artist, album, lastArtPng, null, duration, null)
    }

    private fun art(md: MediaMetadata): Bitmap? =
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)

    private fun png(bmp: Bitmap?): ByteArray? {
        if (bmp == null) return null
        return runCatching {
            val scaled = if (bmp.width > ART_SIDE || bmp.height > ART_SIDE) Bitmap.createScaledBitmap(bmp, ART_SIDE, ART_SIDE, true) else bmp
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
}

/** Чистая логика DesktopMediaMeta (тестируется без Android). */
internal object DesktopMediaMetaLogic {
    /** Позиция сейчас: PlaybackState отдаёт позицию на момент [updateMs], пока играет - досчитываем по скорости. */
    fun positionSeconds(positionMs: Long, updateMs: Long, speed: Float, playing: Boolean, nowMs: Long): Int? {
        if (positionMs < 0) return null
        val elapsed = if (playing && updateMs > 0 && nowMs > updateMs) ((nowMs - updateMs) * speed).toLong() else 0L
        return ((positionMs + elapsed) / 1000).toInt()
    }

    /** Ключ трека без обложки: одинаковый ключ = тот же трек, MEDIA_INFO повторно не шлём. */
    fun trackKey(pkg: String, song: String?, artist: String?, album: String?, duration: Int?): String =
        listOf(pkg, song, artist, album, duration).joinToString("\u0000")

    /** METADATA_KEY_DURATION в мс; 0 и меньше (радио, нет поля) - null. */
    fun durationSeconds(durationMs: Long): Int? = if (durationMs > 0) (durationMs / 1000).toInt() else null
}
