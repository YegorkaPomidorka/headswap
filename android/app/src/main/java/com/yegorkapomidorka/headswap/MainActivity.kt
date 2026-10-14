package com.yegorkapomidorka.headswap

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.ui.Actions
import com.yegorkapomidorka.headswap.ui.DevScreen
import com.yegorkapomidorka.headswap.ui.HomeScreen
import com.yegorkapomidorka.headswap.ui.OnboardingScreen
import com.yegorkapomidorka.headswap.ui.Screen
import com.yegorkapomidorka.headswap.ui.SettingsScreen
import com.yegorkapomidorka.headswap.ui.StepId
import com.yegorkapomidorka.headswap.ui.UiPrefs

/**
 * Единственная activity: хранит стек экранов (главный, настройки, для разработчиков, онбординг) и принимает команды intent
 * (`--es cmd ...`). Вся работа с сервисами и USB - в ui.Actions, экраны - в пакете ui.
 */
class MainActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private val keys = ArrayList<String>()
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            current()?.tick()
            handler.postDelayed(this, 500)
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            AppLog.i("результат requestPermission: granted=$granted")
            if (granted) Actions.startBridge(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(root)
        registerReceiver(permReceiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        AppLog.i("MainActivity.onCreate SDK=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}; старт activity: ${describeIntent(intent)}")

        restoreScreens(savedInstanceState)
        val hadCmd = intent?.getStringExtra(EXTRA_CMD) != null
        val fromUsb = intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        if (savedInstanceState == null && !hadCmd && !fromUsb && !UiPrefs(this).onboardingDone) showOnboarding()
        handleIntent(intent, "старт activity")
        if (savedInstanceState == null && !hadCmd) {
            Thread({ AaCreds.selfTest(this) }, "aa-selftest").start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLog.i("MainActivity.onNewIntent: новый intent: ${describeIntent(intent)}")
        handleIntent(intent, "новый intent")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        current()?.onShow()
        handler.post(refresh)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        current()?.onHide()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SCREENS, keys)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current()?.onBack() == true) return
        if (stack.size > 1) back() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        current()?.onPermissionResult()
    }

    // --- навигация ---

    private fun current(): Screen? = stack.lastOrNull()

    private fun push(key: String, s: Screen) {
        if (resumed) current()?.onHide()
        stack.add(s)
        keys.add(key)
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    fun back() {
        if (stack.size <= 1) return
        if (resumed) current()?.onHide()
        stack.removeAt(stack.size - 1)
        keys.removeAt(keys.size - 1)
        val s = stack.last()
        root.removeAllViews()
        root.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (resumed) s.onShow()
    }

    /** Свернуть стек до главного экрана. */
    private fun toHome() {
        while (stack.size > 1) back()
        if (stack.isEmpty()) push("home", HomeScreen(this))
    }

    fun showSettings() = push("settings", SettingsScreen(this))

    fun showDev() = push("dev", DevScreen(this))

    /** rerun = показать все шаги; checklist = сразу чеклист готовности. */
    fun showOnboarding(rerun: Boolean = false, checklist: Boolean = false, startAt: StepId? = null) {
        push(if (checklist) "checklist" else if (rerun) "onboarding_all" else "onboarding", OnboardingScreen(this, rerun, checklist, startAt))
    }

    private fun restoreScreens(saved: Bundle?) {
        push("home", HomeScreen(this))
        val savedKeys = saved?.getStringArrayList(STATE_SCREENS) ?: return
        for (k in savedKeys.drop(1)) {
            when (k) {
                "settings" -> showSettings()
                "dev" -> showDev()
                "onboarding" -> showOnboarding()
                "onboarding_all" -> showOnboarding(rerun = true)
                "checklist" -> showOnboarding(checklist = true)
            }
        }
    }

    // --- команды intent ---

    private fun handleIntent(intent: Intent?, reason: String) {
        val cmd = intent?.getStringExtra(EXTRA_CMD)
        if (intent != null && cmd != null) {
            intent.removeExtra(EXTRA_CMD)        // не повторять команду при пересоздании activity
            AppLog.i("команда из intent: $cmd")
            when (cmd) {
                "aa_selftest" -> Actions.aaSelftest(this)
                "open_aa" -> Actions.openAaSettings(this)
                "aa_server_restart" -> {
                    val delay = intent.getIntExtra("delay_s", 0)
                    if (delay > 0) {
                        AppLog.i("AUTOSRV: перезапуск сервера через $delay с")
                        handler.postDelayed({ Actions.restartServer(this, fromCmd = true) }, delay * 1000L)
                    } else Actions.restartServer(this, fromCmd = true)
                }
                "front" -> {}                                  // возврат из автозапуска сервера: только вывести окно вперёд
                "mic_test" -> Actions.micTest(this)
                "transcode_test" -> Actions.transcodeTest(this, intent.getStringExtra("opts") ?: "")
                "screen_main" -> toHome()
                "screen_settings" -> { toHome(); showSettings() }
                "screen_dev" -> { toHome(); showDev() }
                "onboarding" -> {
                    toHome()
                    // --es step autostart|notif|... открывает онбординг сразу с нужного шага
                    val step = StepId.values().firstOrNull { it.key == intent.getStringExtra("step") }
                    showOnboarding(rerun = true, startAt = step)
                }
                "checklist" -> { toHome(); showOnboarding(checklist = true) }
                else -> AppLog.i("неизвестная команда '$cmd' (есть aa_selftest, mic_test, transcode_test, open_aa, aa_server_restart)")
            }
            if (intent.hasExtra("scroll_y")) current()?.scrollTo(intent.getIntExtra("scroll_y", 0))     // отладка: снимки нижней части экрана
            return
        }
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val acc = accessoryFromIntent(intent)
            AppLog.i("== USB_ACCESSORY_ATTACHED, в intent: ${if (acc != null) describe(acc) else "аксессуара нет"}")
        }
        Actions.scan(this, reason)
    }

    @Suppress("DEPRECATION")
    private fun accessoryFromIntent(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)

    companion object {
        const val ACTION_PERMISSION = "com.yegorkapomidorka.headswap.USB_PERMISSION"

        /** adb shell am start -n com.yegorkapomidorka.headswap/.MainActivity --es cmd aa_selftest */
        const val EXTRA_CMD = "cmd"
        private const val STATE_SCREENS = "screens"

        /**
         * Стартовый intent целиком одной строкой: так видно, как ГУ запускает приложение по ADB (am start -n / -a / extras).
         * Не должна ронять activity: на любом сбое возвращает то, что успела собрать.
         */
        @Suppress("DEPRECATION")
        fun describeIntent(i: Intent?): String {
            if (i == null) return "intent=null"
            return try {
                val extras = i.extras?.let { b -> b.keySet().sorted().joinToString(", ") { k -> "$k=${b.get(k)}" } } ?: ""
                "action=${i.action} cmp=${i.component?.flattenToShortString()} cat=${i.categories} data=${i.dataString} " +
                    "type=${i.type} pkg=${i.`package`} flags=0x${Integer.toHexString(i.flags)} extras={$extras}"
            } catch (e: Exception) {
                "action=${i.action} (разбор intent упал: ${e.javaClass.simpleName}: ${e.message})"
            }
        }

        /** Все поля аксессуара одной строкой. Серийник без разрешения на аксессуар бросает SecurityException. */
        fun describe(a: UsbAccessory): String {
            val serial = try { "\"${a.serial}\"" } catch (_: SecurityException) { "(нет разрешения)" }
            return "manufacturer=\"${a.manufacturer}\" model=\"${a.model}\" description=\"${a.description}\" " +
                "version=\"${a.version}\" uri=\"${a.uri}\" serial=$serial"
        }
    }
}
