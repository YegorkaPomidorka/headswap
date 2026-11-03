package com.yegorkapomidorka.headswap.launcher

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.dex.DexState

/**
 * Домашний экран Headswap Desktop (Compose) на виртуальном дисплее ГУ. Запускается только на этом дисплее,
 * на телефон не открывает ничего и домашним приложением телефона не становится.
 * API 30+ (display, insetsController): активность стартует только в режиме Desktop, а он доступен с Android 13.
 */
@SuppressLint("NewApi")
class LauncherActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var connected by mutableStateOf(false)
    private var visible = false
    private var ownershipDeadline = 0L

    private val awaitOwnership = object : Runnable {
        override fun run() {
            if (!visible || isDestroyed) return
            when {
                ownsDisplay() -> connected = true
                currentDisplayId() <= 0 || SystemClock.elapsedRealtime() >= ownershipDeadline -> finish()
                else -> main.postDelayed(this, 100)
            }
        }
    }

    /** Блокировка телефона во время сеанса: в логе видно, что случилось с экраном и держит ли рабочий стол ключ-защита. */
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
            AppLog.i("SCREEN ${intent.action?.substringAfterLast('.')} keyguard=$locked дисплей=${currentDisplayId()} " +
                "активность=${if (visible) "видна" else "не видна"} подключён=$connected", false)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shell may launch this Activity before the connector receives start()'s display ID.
        // Render a neutral first frame; reveal the launcher only after the client confirms ownership.
        if (currentDisplayId() <= 0) { finish(); return }
        AppLog.init(this)
        // Рабочий стол остаётся на экране при блокировке телефона (сам дисплей не блокируется: ALWAYS_UNLOCKED).
        setShowWhenLocked(true)
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        LauncherModel.init(this)
        ForeignActions.reload(this, currentDisplayId(), goHome = false) // сразу в чужой лаунчер, если он выбран
        // дочерние окна живут с плотностью дисплея ГУ
        val dpi = resources.displayMetrics.densityDpi.coerceIn(96, 1280)
        LauncherModel.densityDpi = dpi
        EmbedHost.densityDpi = dpi
        window.setDecorFitsSystemWindows(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back inside the launcher must not close it and leave the video surface empty.
            override fun handleOnBackPressed() { LauncherActions.hardwareBack() }
        })
        setContent { LauncherRoot(connected) }
    }

    /**
     * Сюда приходят HOME ГУ и смена лаунчера в настройках телефона (оба через showDesktop): выбор из DexPrefs перечитывается
     * и в режиме чужого лаунчера выполняется «Домой».
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (currentDisplayId() <= 0) return
        AppLog.i("LAUNCHER onNewIntent: HOME или смена лаунчера", false)
        ForeignActions.reload(this, currentDisplayId(), goHome = true)
    }

    override fun onResume() {
        super.onResume()
        if (currentDisplayId() <= 0) { finish(); return }
        ForeignActions.reload(this, currentDisplayId(), goHome = false) // «Домой» только по onNewIntent, иначе каждый возврат на экран сбрасывал бы лаунчер
        visible = true
        connected = ownsDisplay()
        AppLog.i("LAUNCHER активность на экране, дисплей=${currentDisplayId()}", false)
        LauncherModel.reloadCatalog() // за время работы могли поставить или удалить приложение
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ownershipDeadline = SystemClock.elapsedRealtime() + 10_000L
        main.removeCallbacks(awaitOwnership)
        main.post(awaitOwnership)
    }

    override fun onPause() {
        AppLog.i("LAUNCHER активность ушла с экрана (onPause), дисплей=${currentDisplayId()}", false)
        visible = false
        main.removeCallbacks(awaitOwnership)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenEvents) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = display?.displayId ?: -1
    private fun ownsDisplay(): Boolean = DesktopAppCatalog.ownsDisplay(currentDisplayId(), DexState.displayId)
}
