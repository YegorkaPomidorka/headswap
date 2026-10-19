package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.CustomResError
import com.yegorkapomidorka.headswap.aa.CustomVideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** «Для разработчиков» (Dev.dc.html): отладочные флажки, кнопки, статус и живой лог (последние ~200 строк). */
class DevScreen(host: MainActivity) : Screen(host) {
    private val aa = AaPrefs(ctx)
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logMeta: TextView
    private lateinit var enableSvc: View
    private var lastLog = ""
    private var customDetail: View? = null
    private var customDivider: View? = null
    private var customWField: EditText? = null
    private var customHField: EditText? = null
    private var customFromHu: View? = null
    private var customFromHuText: TextView? = null
    private var customErr: TextView? = null
    private var customWarn: TextView? = null
    private var customCalc: View? = null
    private val customVals = arrayOfNulls<TextView>(4)
    private var customUpdating = false

    /** AVC-энкодеры телефона (null = список получить не удалось, тогда размер не проверяем). Список не меняется, считаем один раз. */
    private val avcEncoders: List<MediaCodecInfo.VideoCapabilities>? by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
                .mapNotNull { info ->
                    try {
                        info.getCapabilitiesForType("video/avc").videoCapabilities
                    } catch (e: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    override val view: View

    init {
        val c = ctx
        val body = c.vbox(6).apply { pad(c, 16, 4, 16, 24) }

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_mic)))
        body.addView(
            group(listOf(row(c.getString(R.string.dev_mic_vr_title), c.getString(R.string.dev_mic_vr_sub), aa.micVrStatus) { aa.micVrStatus = it })),
        )

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_meta)))
        body.addView(
            group(listOf(
                row(c.getString(R.string.dev_meta_music_title), c.getString(R.string.dev_meta_music_sub), aa.metaMusic) { aa.metaMusic = it },
                row(c.getString(R.string.dev_meta_navi_title), c.getString(R.string.dev_meta_navi_sub), aa.metaNavi) { aa.metaNavi = it },
                row(c.getString(R.string.dev_meta_icon_title), c.getString(R.string.dev_meta_icon_sub), aa.naviIcon) { aa.naviIcon = it },
                row(c.getString(R.string.dev_meta_time_title), c.getString(R.string.dev_meta_time_sub), aa.naviTime) { aa.naviTime = it },
                row(c.getString(R.string.dev_meta_progress_title), c.getString(R.string.dev_meta_progress_sub), aa.progressSeconds) { aa.progressSeconds = it },
            )),
        )

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_video)))
        body.addView(customResCard())

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_actions)))
        val actions = listOf(
            c.getString(R.string.dev_action_scan) to { Actions.scan(c, "кнопка") },
            c.getString(R.string.dev_action_stop_bridge) to { Actions.stopBridge(c) },
            c.getString(R.string.dev_action_restart_server) to { Actions.restartServer(c) },
            c.getString(R.string.dev_action_aa_settings) to { Actions.openAaSettings(c) },
            c.getString(R.string.dev_action_aa_selftest) to { Actions.aaSelftest(c) },
            c.getString(R.string.dev_action_mic_test) to { Actions.micTest(c) },
        )
        for (pair in actions.chunked(2)) {
            val r = c.hbox(8)
            for ((label, fn) in pair) {
                r.addView(c.tonalButton(label).apply { setOnClickListener { fn() } }, lp(0, c.dp(52), 1f))
            }
            if (pair.size == 1) r.addView(View(c), lp(0, c.dp(52), 1f))
            body.addView(r, lp(MATCH, WRAP))
        }
        enableSvc = c.tonalButton(c.getString(R.string.dev_action_enable_autostart)).apply { setOnClickListener { Actions.enableServiceDialog(c) } }
        body.addView(enableSvc, lp(MATCH, c.dp(52)))

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_status)))
        statusView = c.text("", 12f, 400, c.clr(R.color.on), monospace = true).apply {
            lineHeight = c.dp(18)
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 14, 12, 14, 12)
            setTextIsSelectable(true)
        }
        body.addView(statusView, lp(MATCH, WRAP))

        val logHead = c.hbox().apply { pad(c, 8, 12, 8, 4) }
        logHead.addView(c.text(c.getString(R.string.dev_section_log), 14f, 600, c.clr(R.color.acc)), lp(0, WRAP, 1f))
        logMeta = c.text("", 13f, 400, c.clr(R.color.on2))
        logHead.addView(logMeta)
        body.addView(logHead, lp(MATCH, WRAP))

        logView = c.text("", 11f, 400, c.clr(R.color.on2), monospace = true).apply { lineHeight = c.dp(17) }
        logScroll = ScrollView(c).apply {
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 14, 12, 14, 12)
            clipToPadding = false
            addView(logView)
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        body.addView(logScroll, c.lpDp(MATCH, 330))

        body.addView(
            c.primaryButton(c.getString(R.string.dev_log_share), R.drawable.ic_share, 56, 17f).apply { setOnClickListener { LogShare.shareLog(c) } },
            lp(MATCH, c.dp(56)).margins(c, 0, 8),
        )
        val bottom = c.hbox(8)
        bottom.addView(c.outlineButton(c.getString(R.string.dev_log_save_zip), 0, 48, 15f).apply { setOnClickListener { LogShare.saveZip(c) } }, lp(0, c.dp(48), 1f))
        bottom.addView(c.outlineButton(c.getString(R.string.dev_log_clear), 0, 48, 15f).apply { setOnClickListener { confirmClear() } }, lp(0, c.dp(48), 1f))
        body.addView(bottom, lp(MATCH, WRAP))
        body.addView(
            c.text(c.getString(R.string.dev_log_archive_note), 13f, 400, c.clr(R.color.on2))
                .apply { lineHeight = c.dp(18) },
            lp(MATCH, WRAP).margins(c, 8, 4, 8, 0),
        )

        body.addView(c.sectionTitle(c.getString(R.string.dev_section_notes)))
        body.addView(
            c.vbox(10).apply {
                background = c.shape(c.clr(R.color.surf), 20)
                pad(c, 16, 14, 16, 14)
                addView(
                    c.text(
                        c.getString(R.string.dev_notes_adb),
                        14f, 400, c.clr(R.color.on2),
                    ).apply { lineHeight = c.dp(20) },
                )
                addView(c.tonalButton(c.getString(R.string.dev_open_dev_settings)).apply {
                    setOnClickListener {
                        try {
                            c.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(c, c.getString(R.string.dev_open_failed, e.javaClass.simpleName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }, lp(MATCH, c.dp(52)))
            },
            lp(MATCH, WRAP),
        )

        view = scaffold(c, c.getString(R.string.dev_title), { host.back() }, body)
    }

    private fun row(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        ToggleRow(ctx, title, sub, checked, 64, false, 16f, 13f, onChange)

    /** Карточка из строк-переключателей с разделителями. */
    private fun group(rows: List<ToggleRow>): LinearLayout {
        val c = ctx
        val card = c.card()
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(c.divider())
            card.addView(r.view, lp(MATCH, WRAP))
        }
        return card
    }

    /** Карточка «Своё разрешение»: переключатель и, когда включено, поля ширины/высоты с живым расчётом. */
    private fun customResCard(): LinearLayout {
        val c = ctx
        val card = c.card()
        val toggle = ToggleRow(
            c, c.getString(R.string.dev_custom_res_title), c.getString(R.string.dev_custom_res_sub), aa.customRes, 64, false, 16f, 13f,
        ) {
            aa.customRes = it
            customDetail?.visibility = if (it) View.VISIBLE else View.GONE
            customDivider?.visibility = if (it) View.VISIBLE else View.GONE
        }
        card.addView(toggle.view, lp(MATCH, WRAP))
        val divider = c.divider()
        customDivider = divider
        card.addView(divider)

        val wField = customField().also { customWField = it }
        val hField = customField().also { customHField = it }
        fun column(label: String, field: EditText) = c.vbox(4).apply {
            addView(c.text(label, 13f, 400, c.clr(R.color.on2)))
            addView(field, lp(MATCH, c.dp(52)))
        }
        val sizes = c.hbox(8).apply {
            gravity = Gravity.BOTTOM
            addView(column(c.getString(R.string.dev_custom_w), wField), lp(0, WRAP, 1f))
            addView(c.text("×", 18f, 500, c.clr(R.color.on2)).apply { gravity = Gravity.CENTER }, c.lpDp(WRAP, 52))
            addView(column(c.getString(R.string.dev_custom_h), hField), lp(0, WRAP, 1f))
        }

        val fromHu = c.tonalButton("").also { customFromHu = it }
        customFromHuText = fromHu.getChildAt(0) as TextView
        fromHu.setOnClickListener {
            if (aa.lastHuW > 0 && aa.lastHuH > 0) {
                customUpdating = true
                wField.setText(aa.lastHuW.toString())
                hField.setText(aa.lastHuH.toString())
                customUpdating = false
                validateCustom()
            }
        }

        val calc = c.vbox(4).also { customCalc = it }
        val keys = intArrayOf(R.string.dev_custom_k_plate, R.string.dev_custom_k_margins, R.string.dev_custom_k_scale, R.string.dev_custom_k_bitrate)
        keys.forEachIndexed { i, key ->
            val value = c.text("", 14f, 500).apply { gravity = Gravity.END }.also { customVals[i] = it }
            calc.addView(c.hbox(8).apply {
                addView(c.text(c.getString(key), 14f, 400, c.clr(R.color.on2)), lp(0, WRAP, 1f))
                addView(value, lp(WRAP, WRAP))
            }, lp(MATCH, WRAP))
        }
        val err = c.text("", 14f, 500, c.clr(R.color.err)).apply { visibility = View.GONE }.also { customErr = it }
        val warn = c.text("", 13f, 400, c.clr(R.color.warn)).apply { visibility = View.GONE }.also { customWarn = it }

        val detail = c.vbox(10).apply {
            pad(c, 16, 12, 16, 14)
            addView(sizes, lp(MATCH, WRAP))
            addView(c.text(c.getString(R.string.dev_custom_hint), 13f, 400, c.clr(R.color.on2)))
            addView(fromHu, lp(MATCH, c.dp(52)))
            addView(calc, lp(MATCH, WRAP))
            addView(err, lp(MATCH, WRAP))
            addView(warn, lp(MATCH, WRAP))
            addView(c.text(c.getString(R.string.dev_custom_note), 13f, 400, c.clr(R.color.on2)))
        }
        customDetail = detail
        card.addView(detail, lp(MATCH, WRAP))

        wField.setText(aa.customW.toString())
        hField.setText(aa.customH.toString())
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!customUpdating) validateCustom()
            }
        }
        wField.addTextChangedListener(watcher)
        hField.addTextChangedListener(watcher)

        val on = aa.customRes
        detail.visibility = if (on) View.VISIBLE else View.GONE
        divider.visibility = if (on) View.VISIBLE else View.GONE
        refreshCustomFromHu()
        validateCustom()
        return card
    }

    /** Поле ввода числа: как wifiField в настройках, но 18sp/500; высота 52dp задаётся при добавлении. */
    private fun customField(): EditText = EditText(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        typeface = ctx.sans(500)
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(4))
    }

    /** Кнопка «Как просит ГУ» видна, только когда ГУ уже называла размер. */
    private fun refreshCustomFromHu() {
        val ok = aa.lastHuW > 0 && aa.lastHuH > 0
        customFromHu?.visibility = if (ok) View.VISIBLE else View.GONE
        if (ok) customFromHuText?.text = ctx.getString(R.string.dev_custom_from_hu, aa.lastHuW, aa.lastHuH)
    }

    /** Проверка введённого размера: ошибка = не сохраняем, предупреждения сохранению не мешают. */
    private fun validateCustom() {
        val c = ctx
        val w = customWField?.text?.toString()?.toIntOrNull() ?: 0
        val h = customHField?.text?.toString()?.toIntOrNull() ?: 0
        val problem = when (CustomVideoMode.check(w, h)) {
            CustomResError.WIDTH_RANGE -> R.string.dev_custom_err_width
            CustomResError.HEIGHT_RANGE -> R.string.dev_custom_err_height
            CustomResError.ODD -> R.string.dev_custom_err_odd
            CustomResError.PORTRAIT -> R.string.dev_custom_err_portrait
            null -> if (encoderSupports(w, h)) 0 else R.string.dev_custom_err_encoder
        }
        if (problem != 0) {
            customErr?.text = c.getString(R.string.dev_custom_err_fmt, c.getString(problem))
            customErr?.visibility = View.VISIBLE
            customWarn?.visibility = View.GONE
            customCalc?.visibility = View.GONE
            return
        }
        customErr?.visibility = View.GONE
        aa.customW = w
        aa.customH = h
        val g = CustomVideoMode(w, h)
        val oneToOne = g.areaW == g.outW && g.areaH == g.outH
        customVals[0]?.text = c.getString(R.string.dev_custom_v_size, g.aaW, g.aaH)
        customVals[1]?.text = c.getString(R.string.dev_custom_v_size, g.marginW, g.marginH)
        customVals[2]?.text = if (oneToOne) c.getString(R.string.dev_custom_v_scale_one) else c.getString(R.string.dev_custom_v_scale, g.scale)
        customVals[3]?.text = c.getString(R.string.dev_custom_v_bitrate, g.bitrate / 1_000_000.0)
        customCalc?.visibility = View.VISIBLE
        val warns = buildList {
            if (w % 16 != 0 || h % 16 != 0) add(c.getString(R.string.dev_custom_warn_16))
            if (g.scale > 1.3) add(c.getString(R.string.dev_custom_warn_scale))
            if (!aa.transcode) add(c.getString(R.string.dev_custom_warn_transcode))
        }
        customWarn?.text = warns.joinToString("\n")
        customWarn?.visibility = if (warns.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun encoderSupports(w: Int, h: Int): Boolean {
        val list = avcEncoders ?: return true
        return list.any {
            try {
                it.isSizeSupported(w, h)
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(ctx, R.style.Theme_Headswap_Dialog)
            .setTitle(R.string.dev_clear_title)
            .setMessage(R.string.dev_clear_message)
            .setPositiveButton(R.string.dev_clear_confirm) { _, _ ->
                AppLog.clearRing()
                val n = LogShare.deleteOldLogs(ctx)
                lastLog = "\u0000"
                Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.dev_deleted_old_files, n, n), Toast.LENGTH_SHORT).show()
                tick()
            }
            .setNegativeButton(R.string.dev_clear_cancel, null)
            .show()
    }

    override fun onShow() {
        refreshCustomFromHu()
        lastLog = "\u0000"
        tick()
    }

    override fun tick() {
        val c = ctx
        statusView.text = CarLifeState.summary() + "\n" + AaState.summary() + "\n" + AutoServer.summary(c)
        enableSvc.visibility = if (AutoServer.isConnected) View.GONE else View.VISIBLE
        val lines = AppLog.lastLines()
        val joined = lines.joinToString("\n")
        if (joined == lastLog) return
        lastLog = joined
        logMeta.text = c.getString(
            R.string.dev_log_meta,
            c.resources.getQuantityString(R.plurals.dev_log_lines, lines.size, lines.size),
            LogShare.sizeText(c, AppLog.file),
        )
        val atBottom = logScroll.scrollY + logScroll.height + c.dp(40) >= logView.height
        logView.text = colorize(lines)
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Время приглушено, ошибки/предупреждения/успех окрашены. */
    private fun colorize(lines: List<String>): CharSequence {
        val c = ctx
        val dim = (c.clr(R.color.on2) and 0x00FFFFFF) or 0xBF000000.toInt()
        val sb = SpannableStringBuilder()
        for ((i, l) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            val split = if (l.length > 13 && l[2] == ':' && l[12] == ' ') 12 else 0
            val start = sb.length
            sb.append(l)
            if (split > 0) sb.setSpan(ForegroundColorSpan(dim), start, start + split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val msg = l.substring(split)
            val color = when {
                ERR_RE.containsMatchIn(msg) -> c.clr(R.color.err)
                WARN_RE.containsMatchIn(msg) -> c.clr(R.color.warn)
                OK_RE.containsMatchIn(msg) -> c.clr(R.color.ok)
                else -> c.clr(R.color.on)
            }
            sb.setSpan(ForegroundColorSpan(color), start + split, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private companion object {
        val ERR_RE = Regex("ERROR|ОШИБКА|FAIL|упал|Exception")
        val WARN_RE = Regex("ОТКАТ|потерян|не пришёл|недоступен|не вышло|!!!|не удалось|отброшен")
        val OK_RE = Regex("\\bOK\\b|загружен|подключена|первый IDR|успеш")
    }
}
