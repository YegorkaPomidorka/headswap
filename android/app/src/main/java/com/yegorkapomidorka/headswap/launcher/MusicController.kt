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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import com.yegorkapomidorka.headswap.AppLog
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
    private const val LAUNCH_AFTER_MS = 4000L
    private const val PENDING_MS = 15000L
    private class Pending(val pkg: String, val launchAt: Long, val giveUpAt: Long) { var launched = false }
    @Volatile private var pending: Pending? = null
    private val main = Handler(Looper.getMainLooper())
    /** Открыть приложение плеера на ГУ, когда [startPlayer] не разбудил его в фоне (ставит карточка музыки). */
    @Volatile var launchApp: ((String) -> Unit)? = null

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
        checkPending(sessions)
        // выбран плеер - чужая сессия на паузе его не подменяет (Play и обложка вели в Telegram, поездки 16-17.11)
        val c = MusicLogic.pickSession(sessions, { it.packageName }, { it.playbackState?.state == PlaybackState.STATE_PLAYING },
            preferred, chosenOnly = true)
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

    private fun sessionsOf(ctx: Context, pkg: String): List<MediaController> = runCatching {
        ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(null).filter { it.packageName == pkg }
    }.getOrDefault(emptyList())

    /** Поставить на паузу все сессии [pkg] (YouTube Music, которую включил Gemini). */
    fun pausePackage(ctx: Context, pkg: String) {
        sessionsOf(ctx, pkg).forEach { runCatching { it.transportControls.pause() } }
    }

    /** Играть [pkg]: его сессия, если есть, иначе [startPlayer]. Главный поток. */
    fun playPackage(ctx: Context, pkg: String) {
        val s = sessionsOf(ctx, pkg).firstOrNull()
        if (s != null) runCatching { s.transportControls.play() } else startPlayer(ctx, pkg)
    }

    /**
     * Сделать плеер текущим: если сессия уже есть - играть. Иначе будим его тремя путями: MediaBrowserService (чужих
     * пускает не всякий: у Яндекс Музыки остался один Media3LibraryService, и он отказывает), MEDIA_BUTTON PLAY на его
     * ресивер (возобновление воспроизведения Media3) и, если за [LAUNCH_AFTER_MS] сессии нет, - открыть приложение на
     * ГУ ([launchApp]). Появилась сессия, а плеер стоит - [checkPending] нажимает play. Главный поток.
     */
    fun startPlayer(ctx: Context, pkg: String) {
        // Только главный поток (у MediaBrowser Handler); любое исключение здесь убило бы весь процесс моста.
        try {
            controller?.takeIf { it.packageName == pkg }?.let { it.transportControls.play(); return }
            sessionsOf(ctx, pkg).firstOrNull()?.let {
                AppLog.i("MUSIC $pkg: play в его сессию", false)
                it.transportControls.play()
                return
            }
            val now = SystemClock.uptimeMillis()
            pending = Pending(pkg, now + LAUNCH_AFTER_MS, now + PENDING_MS)
            val services = ctx.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .filter { it.serviceInfo.packageName == pkg }.map { it.serviceInfo.name }
            AppLog.i("MUSIC запуск $pkg: сессии нет, браузер (${services.size}) + MEDIA_BUTTON", false)
            connectPlay(ctx, pkg, services, 0)
            mediaButtonPlay(ctx, pkg)
        } catch (e: Exception) {
            AppLog.i("MUSIC запуск $pkg: ${e.javaClass.simpleName}: ${e.message}", false)
        }
    }

    private fun connectPlay(ctx: Context, pkg: String, services: List<String>, i: Int) {
        val name = services.getOrNull(i) ?: return
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(ctx, ComponentName(pkg, name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                AppLog.i("MUSIC $pkg/$name: браузер подключился, play", false)
                runCatching { MediaController(ctx, browser.sessionToken).transportControls.play() }
                runCatching { browser.disconnect() }
            }
            override fun onConnectionFailed() {
                AppLog.i("MUSIC $pkg/$name: браузер не пустил", false)
                runCatching { connectPlay(ctx, pkg, services, i + 1) }
            }
        }, null)
        browser.connect()
    }

    /** PLAY на MEDIA_BUTTON-ресивер [pkg]: так система возобновляет плеер, которого нет среди сессий. */
    private fun mediaButtonPlay(ctx: Context, pkg: String) {
        val receiver = runCatching {
            ctx.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0).firstOrNull()
        }.getOrNull()?.activityInfo?.name
        if (receiver == null) { AppLog.i("MUSIC $pkg: MEDIA_BUTTON-ресивера нет", false); return }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(pkg, receiver))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            runCatching { ctx.sendBroadcast(intent) }
                .onFailure { AppLog.i("MUSIC $pkg: MEDIA_BUTTON ${it.javaClass.simpleName}", false); return }
        }
        AppLog.i("MUSIC $pkg: MEDIA_BUTTON PLAY -> $receiver", false)
    }

    /** Ждём сессию запущенного плеера: появилась - play (если стоит); нет к сроку - открыть приложение, потом сдаться. */
    private fun checkPending(sessions: List<MediaController>) {
        val p = pending ?: return
        val now = SystemClock.uptimeMillis()
        val s = sessions.firstOrNull { it.packageName == p.pkg }
        when {
            s != null -> {
                pending = null
                if (s.playbackState?.state == PlaybackState.STATE_PLAYING) AppLog.i("MUSIC ${p.pkg}: заиграл", false)
                else { AppLog.i("MUSIC ${p.pkg}: сессия появилась, play", false); runCatching { s.transportControls.play() } }
            }
            now >= p.giveUpAt -> { pending = null; AppLog.i("MUSIC ${p.pkg}: сессия так и не появилась", false) }
            now >= p.launchAt && !p.launched -> {
                p.launched = true
                AppLog.i("MUSIC ${p.pkg}: сессии нет - открываю приложение", false)
                main.post { launchApp?.invoke(p.pkg) }
            }
        }
    }
}
