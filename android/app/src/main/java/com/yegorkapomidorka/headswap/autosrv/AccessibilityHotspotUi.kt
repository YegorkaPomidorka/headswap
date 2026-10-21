package com.yegorkapomidorka.headswap.autosrv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.yegorkapomidorka.headswap.link.wifi.HotspotState

/**
 * HotspotUi поверх AccessibilityService; вызывается с рабочей нити AutoServer. В отличие от AccessibilityServerUi
 * видит окна любых пакетов (Настройки, SystemUI), кроме нашего: на экране Headswap свои подписи про точку доступа.
 */
class AccessibilityHotspotUi(private val svc: AutoServerService) : HotspotUi {
    private val base = AccessibilityServerUi(svc)
    private var toggleNode: AccessibilityNodeInfo? = null
    private var entryNode: AccessibilityNodeInfo? = null
    private var tileNode: AccessibilityNodeInfo? = null

    override fun prepareScreen() = base.prepareScreen()
    override fun hotspotUp() = HotspotState.up()
    override fun ifaces() = HotspotState.allUp()
    override fun back() = base.back()
    override fun returnToApp() = base.returnToApp()
    override fun now() = System.currentTimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)

    private fun activePkg(): String? = try {
        svc.rootInActiveWindow?.packageName?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        fun add(r: AccessibilityNodeInfo?) {
            if (r != null && r.packageName != svc.packageName && res.none { it == r }) res.add(r)
        }
        try {
            add(svc.rootInActiveWindow)
        } catch (_: Throwable) {
        }
        try {
            for (w in svc.windows) add(w.root)
        } catch (_: Throwable) {
        }
        return res
    }

    /** Обход в ширину по всем окнам; visit вернул true = стоп. Число узлов на окно ограничено. */
    private fun walk(visit: (AccessibilityNodeInfo) -> Boolean) {
        for (root in roots()) {
            var seen = 0
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

    private fun isCheckable(n: AccessibilityNodeInfo): Boolean {
        if (n.isCheckable) return true
        val cls = n.className?.toString() ?: return false
        return cls.endsWith("Switch") || cls.endsWith("CheckBox") || cls.endsWith("ToggleButton") || cls.endsWith("CompoundButton")
    }

    private fun top(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.top
    }

    private fun countCheckables(n: AccessibilityNodeInfo, depth: Int = 0): Int {
        var c = if (isCheckable(n)) 1 else 0
        if (depth < 4) for (i in 0 until n.childCount) n.getChild(i)?.let { c += countCheckables(it, depth + 1) }
        return c
    }

    private fun subtreeTexts(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0) {
        if (out.size >= 12) return
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { if (it !in out) out.add(it) }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { if (it !in out) out.add(it) }
        if (depth < 4) for (i in 0 until n.childCount) n.getChild(i)?.let { subtreeTexts(it, out, depth + 1) }
    }

    /** Подпись переключателя: свой текст/описание, иначе тексты ближайшего предка, в котором он единственный переключатель. */
    private fun labelOf(n: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>()
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        var p = n.parent
        var hops = 0
        while (p != null && hops < 3 && parts.isEmpty()) {
            if (countCheckables(p) > 1) break
            subtreeTexts(p, parts)
            p = p.parent
            hops++
        }
        return parts.joinToString(" | ")
    }

    private fun stateDesc(n: AccessibilityNodeInfo): CharSequence? =
        if (Build.VERSION.SDK_INT >= 30) n.stateDescription else null

    /** Ближайший кликабельный узел (сам или предок до 3 уровней), не слишком мелкий и не во весь экран (не строка состояния). */
    private fun tapTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = svc.resources.displayMetrics
        val minH = (40 * dm.density).toInt()
        val r = Rect()
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops <= 3) {
            if (c.isClickable && c.isEnabled) {
                c.getBoundsInScreen(r)
                if (r.height() >= minH && r.width() < dm.widthPixels * 0.9) return c
            }
            c = c.parent
            hops++
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        node.refresh()
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            hops++
        }
        return false
    }

    override fun tetherIntents() = listOf(
        "TetherSettings",
        "Settings\$TetherSettingsActivity",
        "action:TETHER_SETTINGS",
        "xiaomi:WifiTetherSettings",
        "xiaomi:Settings\$WifiTetherSettingsActivity",
        "ACTION_WIRELESS_SETTINGS",
    )

    private fun intentFor(label: String): Intent {
        val st = "com.android.settings"
        return when (label) {
            "TetherSettings" -> Intent().setComponent(ComponentName(st, "com.android.settings.TetherSettings"))
            "Settings\$TetherSettingsActivity" -> Intent().setComponent(ComponentName(st, "com.android.settings.Settings\$TetherSettingsActivity"))
            "action:TETHER_SETTINGS" -> Intent("android.settings.TETHER_SETTINGS")
            "xiaomi:WifiTetherSettings" -> Intent().setComponent(ComponentName(st, "com.android.settings.wifi.tether.WifiTetherSettings"))
            "xiaomi:Settings\$WifiTetherSettingsActivity" -> Intent().setComponent(ComponentName(st, "com.android.settings.Settings\$WifiTetherSettingsActivity"))
            else -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    override fun openTetherSettings(label: String): String? {
        toggleNode = null
        entryNode = null
        try {
            svc.startActivity(intentFor(label))
        } catch (e: Exception) {
            AutoServer.log("точка: интент $label: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            return null
        }
        // ждём, пока на экране появится окно не нашего пакета
        val end = System.currentTimeMillis() + 2_500
        var pkg = activePkg()
        while ((pkg == null || pkg == svc.packageName) && System.currentTimeMillis() < end) {
            Thread.sleep(150)
            pkg = activePkg()
        }
        val windows = try {
            svc.windows.mapNotNull { it.root?.packageName?.toString() }.distinct().joinToString(",")
        } catch (_: Throwable) {
            "?"
        }
        return "${pkg ?: "никакое окно не стало активным"} (окна: $windows)"
    }

    override fun findToggle(): HotspotToggle? {
        toggleNode = null
        val checkables = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        var pageHasTarget = false
        walk { n ->
            if (n.isVisibleToUser) {
                if (isCheckable(n)) checkables.add(Pair(n, labelOf(n)))
                else if (HotspotText.isTarget(n.text) || HotspotText.isTarget(n.contentDescription)) pageHasTarget = true
            }
            false
        }
        var best = checkables.filter { HotspotText.isTarget(it.second) }.minByOrNull { top(it.first) }
        var kind = "label"
        if (best == null && pageHasTarget) {
            // страница точки доступа, главный тумблер без внятной подписи: самый верхний свитч, не из списка соседних
            best = checkables.filter { !HotspotText.isExcluded(it.second) && (it.first.viewIdResourceName ?: "").contains("switch", true) }
                .minByOrNull { top(it.first) }
            kind = "page-main-guess"
        }
        val (node, label) = best ?: return null
        toggleNode = node
        return HotspotToggle(label, if (node.isCheckable || isCheckable(node)) node.isChecked else null, "$kind#${node.viewIdResourceName?.substringAfterLast('/') ?: ""}")
    }

    override fun findMenuEntry(): String? {
        entryNode = null
        var found: AccessibilityNodeInfo? = null
        var label = ""
        walk { n ->
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            val l = if (HotspotText.isTarget(t)) t else if (HotspotText.isTarget(d)) d else null
            if (l != null && n.isVisibleToUser && !isCheckable(n)) {
                val tgt = tapTarget(n)
                if (tgt != null && countCheckables(tgt) == 0) {
                    found = tgt
                    label = l
                }
            }
            found != null
        }
        entryNode = found
        return if (found != null) label else null
    }

    override fun clickToggle(): Boolean = toggleNode?.let { click(it) } ?: false

    override fun clickMenuEntry(): Boolean = entryNode?.let { click(it) } ?: false

    override fun openQuickSettings(): Boolean = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    override fun findTile(): HotspotToggle? {
        tileNode = null
        var found: AccessibilityNodeInfo? = null
        var label = ""
        var state: Boolean? = null
        walk { n ->
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            val l = if (HotspotText.isTarget(t)) t else if (HotspotText.isTarget(d)) d else null
            if (l != null && n.isVisibleToUser) {
                val tgt = tapTarget(n)
                if (tgt != null) {
                    found = tgt
                    label = l
                    val extra = ArrayList<String>()
                    subtreeTexts(tgt, extra)
                    val checked = if (isCheckable(tgt)) tgt.isChecked else null
                    state = checked ?: HotspotText.stateOf(listOf(stateDesc(tgt), stateDesc(n), tgt.contentDescription, n.contentDescription) + extra)
                }
            }
            found != null
        }
        tileNode = found
        val f = found ?: return null
        return HotspotToggle(label, state, "tile#${f.viewIdResourceName?.substringAfterLast('/') ?: ""} pkg=${f.packageName}")
    }

    override fun clickTile(): Boolean = tileNode?.let { click(it) } ?: false

    override fun dump(maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var total = 0
        fun add(s: String): Boolean {
            val l = s.take(200)
            if (total + l.length > maxChars) return false
            out.add(l)
            total += l.length
            return true
        }
        val wins = try {
            svc.windows.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (wins.isEmpty()) {
            val r = try {
                svc.rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            add("окон нет, активное: ${r?.packageName}")
            if (r != null) dumpTree(r, ::add)
            return out
        }
        for (w in wins) {
            val root = try {
                w.root
            } catch (_: Throwable) {
                null
            }
            val own = root?.packageName == svc.packageName
            if (!add("окно id=${w.id} type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} pkg=${root?.packageName}${if (own) " (наше, пропуск)" else ""}")) break
            if (root != null && !own && !dumpTree(root, ::add)) break
        }
        return out
    }

    /** false = дамп упёрся в лимит. */
    private fun dumpTree(root: AccessibilityNodeInfo, add: (String) -> Boolean): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val (n, depth) = queue.removeFirst()
            seen++
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            val chk = isCheckable(n)
            if (!t.isNullOrBlank() || !d.isNullOrBlank() || chk || n.isClickable) {
                val id = n.viewIdResourceName?.substringAfterLast('/')?.let { "#$it" } ?: ""
                val flags = (if (chk) "chk" + (if (n.isChecked) "=1" else "=0") + " " else "") + (if (n.isClickable) "click" else "")
                val line = "${" ".repeat(minOf(depth, 12))}${n.className?.toString()?.substringAfterLast('.')}$id[${t ?: ""}|${d ?: ""}] $flags".trimEnd()
                if (!add(line)) return false
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
        }
        return true
    }

    companion object {
        private const val MAX_NODES = 700
    }
}
