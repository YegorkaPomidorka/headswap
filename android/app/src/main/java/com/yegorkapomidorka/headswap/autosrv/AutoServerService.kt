package com.yegorkapomidorka.headswap.autosrv

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog

/**
 * Служба спецвозможностей: без фильтра по пакетам (см. res/xml/autosrv_config.xml), чтобы эксперимент h135 видел
 * Настройки и шторку. Сама ничего не делает; сценарии гоняет AutoServer на своей нити: AA (ServerAutomation) через
 * AccessibilityServerUi, который сам берёт только окна com.google.android.projection.gearhead, и точка доступа
 * (HotspotAutomation) через AccessibilityHotspotUi.
 */
class AutoServerService : AccessibilityService() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.init(this)
        AutoServer.service = this
        AutoServer.log("служба спецвозможностей подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // события не нужны: подписка только заставляет систему отдавать дерево узлов окон
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (AutoServer.service === this) AutoServer.service = null
        AutoServer.log("служба спецвозможностей отключена")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (AutoServer.service === this) AutoServer.service = null
        super.onDestroy()
    }
}

/** ServerUi поверх AccessibilityService; вызывается с рабочей нити AutoServer. */
class AccessibilityServerUi(private val svc: AutoServerService) : ServerUi {
    private fun roots(): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        try {
            svc.rootInActiveWindow?.let { if (it.packageName == AA_PACKAGE) res.add(it) }
        } catch (_: Throwable) {
        }
        try {
            for (w in svc.windows) {
                val r = w.root ?: continue
                if (r.packageName == AA_PACKAGE && res.none { it == r }) res.add(r)
            }
        } catch (_: Throwable) {
        }
        return res
    }

    /** Обход в ширину; visit вернул true = стоп. Глубина и число узлов ограничены. */
    private fun walk(visit: (AccessibilityNodeInfo) -> Boolean) {
        var seen = 0
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && seen < MAX_NODES) {
                val n = queue.removeFirst()
                seen++
                if (visit(n)) return
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun prepareScreen(): Pair<Boolean, String> {
        val pm = svc.getSystemService(PowerManager::class.java)
        val km = svc.getSystemService(KeyguardManager::class.java)
        var note = "interactive=${pm.isInteractive} keyguard=${km.isKeyguardLocked}"
        if (!pm.isInteractive) {
            try {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "headswap:autosrv")
                wl.acquire(10_000)
                Thread.sleep(800)
                note += " -> после включения экрана interactive=${pm.isInteractive} keyguard=${km.isKeyguardLocked}"
            } catch (e: Exception) {
                note += " (включить экран не вышло: ${e.javaClass.simpleName})"
            }
        }
        if (!pm.isInteractive) return Pair(false, "экран выключен ($note)")
        if (km.isKeyguardLocked) return Pair(false, "телефон заблокирован ($note)")
        return Pair(true, note)
    }

    override fun openAaSettings(): Boolean = try {
        AaLabels.load(svc)
        svc.startActivity(
            Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        true
    } catch (e: Exception) {
        AutoServer.log("не открыть настройки AA: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    override fun aaVisible(): Boolean = try {
        svc.rootInActiveWindow?.packageName == AA_PACKAGE
    } catch (_: Throwable) {
        false
    }

    override fun clickMore(): Boolean {
        var target: AccessibilityNodeInfo? = null
        walk { n ->
            if (AutoServerText.isMore(n.contentDescription) && (n.isClickable || n.parent?.isClickable == true)) {
                target = n
                true
            } else false
        }
        target?.let {
            moreSearchSince = 0
            return click(it)
        }
        // запасной путь без языка: ⋮ = самая правая кликабельная кнопка-иконка (без текста) в полосе тулбара сверху.
        // Только после 1,5 с безуспешного поиска по описанию, чтобы не нажать соседнюю иконку, пока тулбар дорисовывается
        // (на ru/en описание находится сразу). Ошибка не фатальна: без пунктов сервера в меню сценарий жмёт «назад».
        val now = System.currentTimeMillis()
        if (moreSearchSince == 0L) moreSearchSince = now
        if (now - moreSearchSince < 1_500) return false
        val overflow = findOverflowByPosition() ?: return false
        moreSearchSince = 0
        if (!moreFallbackLogged) {
            moreFallbackLogged = true
            AutoServer.log("кнопка «Ещё» не найдена по описанию, нажимаю по положению: ${describe(overflow)}")
        }
        return click(overflow)
    }

    private var moreFallbackLogged = false
    private var moreSearchSince = 0L

    private fun findOverflowByPosition(): AccessibilityNodeInfo? {
        val screen = svc.resources.displayMetrics
        val topLimit = screen.heightPixels / 6
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestRight = -1
        walk { n ->
            if (n.isClickable && n.isVisibleToUser && n.text.isNullOrBlank()) {
                val cls = n.className?.toString() ?: ""
                if (cls.endsWith("ImageView") || cls.endsWith("ImageButton") || cls.endsWith("Button")) {
                    n.getBoundsInScreen(r)
                    if (r.top < topLimit && r.width() > 0 && r.width() < screen.widthPixels / 4 && r.right > bestRight) {
                        bestRight = r.right
                        best = n
                    }
                }
            }
            false
        }
        return best?.takeIf { bestRight > screen.widthPixels * 3 / 4 }
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        return "${n.className?.toString()?.substringAfterLast('.')}#${n.viewIdResourceName ?: ""}[${n.contentDescription ?: ""}] $r"
    }

    override fun texts(): List<String> {
        val res = ArrayList<String>()
        walk { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { res.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { res.add(it) }
            false
        }
        return res
    }

    override fun viewIds(): List<String> {
        val res = ArrayList<String>()
        walk { n ->
            n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { res.add(it) }
            false
        }
        return res
    }

    override fun clickText(match: (CharSequence?) -> Boolean): Boolean {
        var target: AccessibilityNodeInfo? = null
        walk { n ->
            if (match(n.text) || match(n.contentDescription)) {
                target = n
                true
            } else false
        }
        return target?.let { click(it) } ?: false
    }

    override fun back() {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun returnToApp() {
        try {
            svc.startActivity(
                Intent(svc, MainActivity::class.java).putExtra(MainActivity.EXTRA_CMD, "front")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            AutoServer.log("возврат в приложение")
        } catch (e: Exception) {
            AutoServer.log("возврат в приложение не вышел (${e.javaClass.simpleName}), жму Home")
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    override fun dump(): String {
        val sb = ArrayList<String>()
        walk { n ->
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (!t.isNullOrBlank() || !d.isNullOrBlank() || n.isClickable) {
                val id = n.viewIdResourceName?.substringAfterLast('/')?.let { "#$it" } ?: ""
                sb.add("${n.className?.toString()?.substringAfterLast('.')}$id[${t ?: ""}|${d ?: ""}${if (n.isClickable) "|click" else ""}]")
            }
            sb.size >= 60
        }
        return sb.joinToString(" ")
    }

    override fun now() = System.currentTimeMillis()

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    companion object {
        const val AA_PACKAGE = "com.google.android.projection.gearhead"
        const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
        private const val MAX_NODES = 600
    }
}

/**
 * Подписи пунктов меню сервера ГУ и кнопки ⋮ из ресурсов самого Android Auto: на том языке, на котором AA сейчас
 * рисует интерфейс (системный), с подтянутыми языковыми сплитами. Так автоматизация не зависит от языка телефона.
 * Имена ресурсов из AA 17.7 (aapt2 dump strings); если Google их переименует, останутся списки в AutoServerText.
 * Нужен <queries> на пакет AA в манифесте (API 30+).
 */
object AaLabels {
    private val START = listOf("menu_action_developer_head_unit_network_server")
    private val STOP = listOf("menu_action_developer_head_unit_network_server_stop")
    private val MORE = listOf("abc_action_menu_overflow_description", "mtrl_action_menu_overflow_description")

    fun load(ctx: Context) {
        val res = try {
            ctx.packageManager.getResourcesForApplication(AccessibilityServerUi.AA_PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            AutoServer.log("подписи AA: пакет не виден (${e.javaClass.simpleName}), только встроенные списки")
            AutoServerText.setAaLabels(emptyList(), emptyList(), emptyList())
            return
        } catch (e: Exception) {
            AutoServer.log("подписи AA: ресурсы не открылись (${e.javaClass.simpleName}: ${e.message}), только встроенные списки")
            AutoServerText.setAaLabels(emptyList(), emptyList(), emptyList())
            return
        }
        fun read(names: List<String>): List<String> = names.mapNotNull { name ->
            try {
                @Suppress("DiscouragedApi")
                val id = res.getIdentifier(name, "string", AccessibilityServerUi.AA_PACKAGE)
                if (id == 0) null else res.getString(id).takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
        val start = read(START)
        val stop = read(STOP)
        val more = read(MORE)
        AutoServerText.setAaLabels(start, stop, more)
        AutoServer.log("подписи AA (${res.configuration.locales.toLanguageTags()}): запустить=$start остановить=$stop ещё=$more")
    }
}
