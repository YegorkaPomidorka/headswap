// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.desktop

import android.app.Activity
import android.app.ActivityManager
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.dex.DexState
import java.util.concurrent.Executors

/**
 * Временный домашний экран рабочего стола (до лаунчера на Compose): сетка запускаемых приложений на виртуальном
 * дисплее ГУ. Запускается только на этом дисплее, на телефон не открывает ничего и домашним приложением не становится.
 * API 30+ (display, insetsController): активность стартует только в режиме Desktop, а он доступен с Android 13.
 */
@SuppressLint("NewApi")
class DesktopHomeActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "headswap-app-catalog").apply { isDaemon = true }
    }
    private var entries: List<DesktopAppCatalog.Entry> = emptyList()
    private var icons: Map<String, Drawable> = emptyMap()
    private var loading = false
    private var visible = false
    private var ownershipDeadline = 0L
    private val adapter = AppsAdapter()

    private data class LoadedApps(val entries: List<DesktopAppCatalog.Entry>, val icons: Map<String, Drawable>)

    private val awaitOwnership = object : Runnable {
        override fun run() {
            if (!visible || isDestroyed) return
            when {
                ownsDisplay() -> loadApps()
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
        // Render a neutral first frame; reveal the catalog only after the client confirms ownership.
        if (currentDisplayId() <= 0) { finish(); return }
        AppLog.init(this)
        buildView()
    }

    override fun onResume() {
        super.onResume()
        if (!::grid.isInitialized) return
        if (currentDisplayId() <= 0) { finish(); return }
        visible = true
        if (!ownsDisplay()) {
            grid.visibility = View.INVISIBLE
            status.text = getString(R.string.desk_home_connecting)
        }
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
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("The desktop remains open until its CarLife session ends")
    override fun onBackPressed() {
        // Back inside the launcher must not close it and leave the video surface empty.
        if (::grid.isInitialized) grid.smoothScrollToPosition(0)
    }

    private fun buildView() {
        window.setDecorFitsSystemWindows(false)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(18), dp(24), dp(12))
        }
        body.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(24) + safe.left, dp(18) + safe.top, dp(24) + safe.right, dp(12) + safe.bottom)
            insets
        }
        body.addView(TextView(this).apply {
            text = getString(R.string.desk_home_title)
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = getString(R.string.desk_home_connecting)
            textSize = 15f
            setTextColor(Color.rgb(180, 190, 203))
            setPadding(dp(8), 0, dp(8), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(156)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(dp(8), dp(4), dp(8), dp(12))
            clipToPadding = false
            setSelector(android.R.color.transparent)
            adapter = this@DesktopHomeActivity.adapter
            setOnItemClickListener { _, _, position, _ -> entries.getOrNull(position)?.let(::launchApp) }
        }
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(TextView(this).apply {
            text = getString(R.string.desk_home_hint)
            textSize = 13f
            setTextColor(Color.rgb(150, 161, 177))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(160), dp(8), dp(8), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(body)
    }

    private fun loadApps() {
        if (loading || !ownsDisplay()) return
        loading = true
        loader.execute {
            val result = runCatching {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val candidates = packageManager.queryIntentActivities(query, 0).mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    DesktopAppCatalog.Entry(activity.packageName, activity.name,
                        runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(activity.packageName),
                        enabled = activity.enabled && activity.applicationInfo.enabled, exported = activity.exported)
                }
                val selected = DesktopAppCatalog.select(candidates, packageName)
                // Package icon loading may involve another process. Keep it off the UI thread.
                LoadedApps(selected, selected.associate { entry ->
                    entry.key to runCatching {
                        packageManager.getActivityIcon(ComponentName(entry.packageName, entry.className))
                    }.getOrElse { packageManager.defaultActivityIcon }
                })
            }
            main.post {
                loading = false
                if (isDestroyed || !visible || !ownsDisplay()) return@post
                result.fold(onSuccess = {
                    entries = it.entries
                    icons = it.icons
                    adapter.notifyDataSetChanged()
                    grid.visibility = View.VISIBLE
                    status.text = getString(if (entries.isEmpty()) R.string.desk_home_empty else R.string.desk_home_choose)
                    status.setTextColor(Color.rgb(180, 190, 203))
                }, onFailure = {
                    showError(getString(R.string.desk_home_catalog_failed))
                    AppLog.i("Desktop home catalog: ${it.javaClass.simpleName}", false)
                })
            }
        }
    }

    private fun launchApp(entry: DesktopAppCatalog.Entry) {
        if (!ownsDisplay()) {
            showError(getString(R.string.desk_home_not_ready))
            return
        }
        val targetDisplay = currentDisplayId()
        try {
            check(packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
                "Secondary-display activities are not supported"
            }
            val component = ComponentName(entry.packageName, entry.className)
            val info = packageManager.getActivityInfo(component, 0)
            check(info.enabled && info.applicationInfo.enabled && info.exported) { "Activity unavailable" }
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            check(getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(this, targetDisplay, intent)) {
                "Display launch denied"
            }
            // No fallback without launchDisplayId: a refused second-display launch must not open on the phone.
            check(ownsDisplay()) { "Display retired" }
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplay).toBundle())
        } catch (error: Exception) {
            showError(getString(R.string.desk_home_launch_failed, entry.label))
            AppLog.i("Desktop home launch rejected: ${error.javaClass.simpleName}", false)
        }
    }

    private fun currentDisplayId(): Int = display?.displayId ?: -1
    private fun ownsDisplay(): Boolean = DesktopAppCatalog.ownsDisplay(currentDisplayId(), DexState.displayId)

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(255, 180, 160))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): DesktopAppCatalog.Entry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: LinearLayout(this@DesktopHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(10))
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(144))
                fun tile(color: Int) = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(14).toFloat()
                }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), tile(Color.rgb(49, 66, 88)))
                    addState(intArrayOf(android.R.attr.state_selected), tile(Color.rgb(49, 66, 88)))
                    addState(intArrayOf(), tile(Color.rgb(22, 27, 35)))
                }
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            val entry = getItem(position)
            val icon = icons[entry.key] ?: packageManager.defaultActivityIcon
            (card.getChildAt(0) as ImageView).setImageDrawable(icon.constantState?.newDrawable(resources) ?: icon)
            (card.getChildAt(1) as TextView).text = entry.label
            card.contentDescription = entry.label
            return card
        }
    }
}
