package com.yegorkapomidorka.headswap.dex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.aa.DesktopMedia

/**
 * Плеер телефона для MUSIC=0/1 от ГУ в режиме рабочего стола. С MEDIA_CONTENT_CONTROL (даёт активация) - играющие
 * сессии напрямую; без него - медиаклавиши в текущую медиасессию. Названия треков не логируются.
 */
internal class DesktopMediaControl(context: Context) : DesktopMedia {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var paused: MediaSession.Token? = null

    override fun isPlaying(): Boolean = playing().isNotEmpty() || audio.isMusicActive

    override fun pause() {
        val list = playing()
        if (list.isEmpty()) {
            paused = null
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            AppLog.i("DESKTOP MUSIC: MUSIC=0 - медиаклавиша паузы (играющих сессий не видно)", false)
            return
        }
        paused = list.first().sessionToken
        for (c in list) runCatching { c.transportControls.pause() }
        AppLog.i("DESKTOP MUSIC: MUSIC=0 - пауза ${list.size} сесс.: ${list.joinToString { it.packageName }}", false)
    }

    override fun resume() {
        val token = paused
        paused = null
        val target = token?.let { t -> sessions().firstOrNull { it.sessionToken == t } }
        if (target != null) {
            runCatching { target.transportControls.play() }
            AppLog.i("DESKTOP MUSIC: MUSIC=1 - play ${target.packageName}", false)
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            AppLog.i("DESKTOP MUSIC: MUSIC=1 - медиаклавиша play", false)
        }
    }

    private fun playing(): List<MediaController> = sessions().filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    private fun sessions(): List<MediaController> {
        if (app.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching { app.getSystemService(MediaSessionManager::class.java).getActiveSessions(null) }.getOrDefault(emptyList())
    }

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
    }
}
