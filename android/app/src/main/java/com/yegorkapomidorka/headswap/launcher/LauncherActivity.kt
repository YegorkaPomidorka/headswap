package com.yegorkapomidorka.headswap.launcher

import android.annotation.SuppressLint
import android.content.Context
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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shell may launch this Activity before the connector receives start()'s display ID.
        // Render a neutral first frame; reveal the launcher only after the client confirms ownership.
        if (currentDisplayId() <= 0) { finish(); return }
        AppLog.init(this)
        LauncherModel.init(this)
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

    override fun onResume() {
        super.onResume()
        if (currentDisplayId() <= 0) { finish(); return }
        visible = true
        connected = ownsDisplay()
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
        visible = false
        main.removeCallbacks(awaitOwnership)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = display?.displayId ?: -1
    private fun ownsDisplay(): Boolean = DesktopAppCatalog.ownsDisplay(currentDisplayId(), DexState.displayId)
}
