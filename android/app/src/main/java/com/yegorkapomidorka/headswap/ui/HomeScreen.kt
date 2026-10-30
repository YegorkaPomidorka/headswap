package com.yegorkapomidorka.headswap.ui

import android.animation.ObjectAnimator
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.update.Updater

/** Главный экран «Готов к поездке» (Main.dc.html): шесть состояний, этапы, кнопки; сверху плашка обновления. */
class HomeScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root

    private val updBox = ctx.vbox()
    private var updKey: String? = null
    private val heroBox = ctx.vbox(14)
    private val stagesBox = ctx.vbox()
    private val footer = ctx.vbox(4)
    private var lastState: HomeState? = null
    private var lastWireless: String? = null
    private val rings = ArrayList<ObjectAnimator>()
    private var graceLeftText: TextView? = null
    private var graceFill: View? = null
    private var graceRest: View? = null

    init {
        val c = ctx
        val header = c.hbox().apply {
            pad(c, 20, 8, 8, 0)
            val chip = FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pri), 10)
                addView(c.icon(R.drawable.ic_logo, 22, c.clr(R.color.onpri)), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }
            addView(chip, lp(c.dp(32), c.dp(32)))
            addView(c.text("Headswap", 22f, 600).apply { letterSpacing = -0.01f }, lp(WRAP, WRAP).margins(c, 10))
            addView(View(c), lp(0, 1, 1f))
            addView(c.iconButton(R.drawable.ic_settings, c.clr(R.color.on2), c.getString(R.string.home_settings)).apply {
                setOnClickListener { host.showSettings() }
            })
        }
        root.addView(header, c.lpDp(MATCH, 64))

        val body = c.vbox(12).apply { pad(c, 16, 8, 16, 8) }
        body.addView(updBox, lp(MATCH, WRAP))
        body.addView(heroBox, lp(MATCH, WRAP))
        body.addView(c.card(24).apply { pad(c, 0, 6, 0, 6); addView(stagesBox, lp(MATCH, WRAP)) }, lp(MATCH, WRAP))
        root.addView(ScrollView(c).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }, lp(MATCH, 0, 1f))

        footer.pad(c, 16, 16, 16, 24)
        root.addView(footer, lp(MATCH, WRAP))
    }

    override fun onShow() {
        lastState = null
        updKey = null
        tick()
    }

    /** Строка беспровода в карточке статуса: только когда режим включён и USB-сессии нет. */
    private fun wirelessLine(): String? {
        val w = CarLifeState.wireless
        // во время беспроводной сессии строка повторяла бы подзаголовок; после обрыва нужна («жду машину снова»)
        if (CarLifeState.wirelessSession) return null
        return if (w != null) ctx.getString(R.string.home_wireless, w) else null
    }

    override fun onHide() {
        rings.forEach { it.cancel() }
        rings.clear()
    }

    override fun tick() {
        refreshUpdate()
        val st = HeadswapStatus.current(ctx)
        val wl = wirelessLine()
        if (st == lastState && wl == lastWireless) return
        val prev = lastState
        lastState = st
        val wlSame = wl == lastWireless
        lastWireless = wl
        // меняется только обратный отсчёт: обновляем его, не пересобирая экран
        if (wlSame && prev != null && st.copy(graceLeftS = prev.graceLeftS) == prev && st.phase == Phase.RECONNECTING) {
            updateGrace(st)
            return
        }
        render(st)
    }

    /** Плашка «Доступна версия X»: не во время подключения к машине; крестик прячет её до следующей версии. */
    private fun refreshUpdate() {
        val c = ctx
        val rel = if (CarLifeState.running) null else Updater.banner(c)
        val key = if (rel == null) "" else "${rel.version}|${Updater.step}|${Updater.progress}"
        if (key == updKey) return
        updKey = key
        updBox.removeAllViews()
        if (rel == null) {
            updBox.visibility = View.GONE
            return
        }
        updBox.visibility = View.VISIBLE
        val sub = when (Updater.step) {
            Updater.Step.DOWNLOADING -> c.getString(R.string.upd_status_downloading, rel.version, Updater.progress)
            Updater.Step.INSTALLING -> c.getString(R.string.upd_status_installing)
            else -> c.getString(R.string.upd_banner_sub)
        }
        val row = c.hbox(14).apply {
            pad(c, 16, 10, 4, 10)
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 20), 20)
            isClickable = true
            setOnClickListener { Updater.start(host) }
        }
        row.addView(c.icon(R.drawable.ic_download, 24, c.clr(R.color.acc)))
        row.addView(c.vbox(2).apply {
            addView(c.text(c.getString(R.string.upd_status_available, rel.version), 16f, 600))
            addView(c.text(sub, 14f, 400, c.clr(R.color.on2)))
        }, lp(0, WRAP, 1f))
        row.addView(c.iconButton(R.drawable.ic_close, c.clr(R.color.on2), c.getString(R.string.upd_later)).apply {
            setOnClickListener {
                Updater.dismiss(c, rel.version)
                refreshUpdate()
            }
        })
        updBox.addView(row, lp(MATCH, WRAP))
    }

    private fun updateGrace(st: HomeState) {
        graceLeftText?.text = ctx.getString(R.string.home_grace_left, st.graceLeftS)
        val total = st.graceTotalS.coerceAtLeast(1)
        val f = (st.graceLeftS.toFloat() / total).coerceIn(0f, 1f)
        graceFill?.layoutParams = lp(0, MATCH, f.coerceAtLeast(0.001f))
        graceRest?.layoutParams = lp(0, MATCH, (1f - f).coerceAtLeast(0.001f))
        (graceFill?.parent as? View)?.requestLayout()
    }

    private fun render(st: HomeState) {
        val c = ctx
        onHide()
        val night = c.isNight
        val (bgRes, fgRes, onFgRes, textRes) = when (st.tone) {
            Tone.PRI -> listOf(R.color.pric, R.color.pri, R.color.onpri, if (night) R.color.on else R.color.acc)
            Tone.OK -> listOf(R.color.okc, R.color.ok, R.color.onok, if (night) R.color.on else R.color.ok)
            Tone.WARN -> listOf(R.color.warnc, R.color.warn, R.color.warnc, if (night) R.color.on else R.color.warn)
            Tone.ERR -> listOf(R.color.errc, R.color.err, R.color.onerr, if (night) R.color.on else R.color.err)
        }
        heroBox.removeAllViews()
        heroBox.background = c.shape(c.clr(bgRes), 28)
        heroBox.pad(c, 20, 20, 20, 20)
        val iconRes = when (st.icon) {
            HeroIcon.POWER -> R.drawable.ic_power
            HeroIcon.PLUG -> R.drawable.ic_plug
            HeroIcon.CAR -> R.drawable.ic_car
            HeroIcon.CAR_USB -> R.drawable.ic_car_usb
            HeroIcon.CAR_WIFI -> R.drawable.ic_car_wifi
            HeroIcon.REFRESH -> R.drawable.ic_refresh
            HeroIcon.ALERT -> R.drawable.ic_alert
        }
        // слева значок в кружке, справа заголовок в одну строку; подзаголовок ниже на всю ширину
        heroBox.addView(c.hbox(12).apply {
            addView(FrameLayout(c).apply {
                background = c.oval(c.clr(fgRes))
                addView(c.icon(iconRes, 24, c.clr(onFgRes)), FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER))
            }, lp(c.dp(44), c.dp(44)))
            addView(c.text(st.title.get(c), 20f, 600, c.clr(textRes)).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP))
        heroBox.addView(c.vbox(6).apply {
            addView(c.text(st.sub.get(c), 16f, 400).apply { lineHeight = c.dp(22) })
            lastWireless?.let { addView(c.text(it, 14f, 400, c.clr(R.color.on2))) }
        }, lp(MATCH, WRAP))
        if (st.showChain) heroBox.addView(chain(), lp(MATCH, WRAP))
        if (st.phase == Phase.RECONNECTING) heroBox.addView(graceBlock(st), lp(MATCH, WRAP))

        stagesBox.removeAllViews()
        for ((label, sm) in st.stages) stagesBox.addView(stageRow(label.get(c), sm.first, sm.second.get(c)), c.lpDp(MATCH, 52))

        footer.removeAllViews()
        // сервер ГУ включает пользователь сам: и первый запуск, и после обрыва кнопка ведёт в настройки Android Auto
        if (st.primary != null) {
            footer.addView(c.primaryButton(c.getString(R.string.action_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.startServerFromUi(c) } })
        }
        st.outline?.let { o ->
            val b = when (o) {
                OutlineAction.STOP -> c.outlineButton(c.getString(R.string.home_btn_stop), R.drawable.ic_stop).apply { setOnClickListener { Actions.stopBridge(c) } }
                OutlineAction.OPEN_AA -> c.outlineButton(c.getString(R.string.home_btn_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.openAaSettings(c) } }
                OutlineAction.NOTIF_ACCESS -> c.outlineButton(c.getString(R.string.home_btn_notif_access), R.drawable.ic_open).apply { setOnClickListener { Actions.openNotificationAccess(c) } }
            }
            footer.addView(b)
        }
        if (st.logLink) {
            // «Отправить лог» живёт внутри самодиагностики: на главном одна понятная вторая кнопка
            footer.addView(c.tonalButton(c.getString(R.string.diag_home_btn)).apply { setOnClickListener { host.showDiag() } })
        }
        // настройки AA нужны не только для сервера ГУ: кнопка внизу в любом состоянии, если её уже нет (основная ведёт туда же)
        if (st.primary == null && st.outline != OutlineAction.OPEN_AA) {
            footer.addView(c.outlineButton(c.getString(R.string.home_btn_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.openAaSettings(c) } })
        }
        if (st.phase == Phase.RECONNECTING) updateGrace(st)
    }

    /** Как запустить сервер ГУ: три пронумерованных пункта на плашке surf (Main.dc.html, isOff). */
    private fun chain(): View {
        val c = ctx
        val box = c.vbox().apply {
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 0, 4, 0, 4)
            contentDescription = c.getString(R.string.home_chain_label)
        }
        listOf(R.string.home_chain_1, R.string.home_chain_2, R.string.home_chain_3).forEachIndexed { i, res ->
            if (i > 0) box.addView(c.divider())
            box.addView(
                c.hbox(12).apply {
                    pad(c, 14, 12, 14, 12)
                    addView(
                        FrameLayout(c).apply {
                            background = c.oval(c.clr(R.color.pri))
                            addView(c.text("${i + 1}", 13f, 700, c.clr(R.color.onpri)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                        },
                        lp(c.dp(24), c.dp(24)),
                    )
                    addView(c.text(boldText(res), 15f, 400).apply { lineHeight = c.dp(20) }, lp(0, WRAP, 1f))
                },
                lp(MATCH, WRAP),
            )
        }
        return box
    }

    /** Строка ресурса с <b>: жирное рисуем шрифтом приложения (Golos 700), а не синтетическим BOLD. */
    private fun boldText(res: Int): CharSequence {
        val c = ctx
        val out = SpannableString(c.getText(res))
        for (sp in out.getSpans(0, out.length, StyleSpan::class.java)) {
            if (sp.style == Typeface.BOLD) {
                out.setSpan(TypefaceSpan(c.sans(700)), out.getSpanStart(sp), out.getSpanEnd(sp), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.removeSpan(sp)
            }
        }
        return out
    }

    private fun graceBlock(st: HomeState): View {
        val c = ctx
        val box = c.vbox(8)
        val bar = c.hbox().apply { background = c.shape(c.clr(R.color.surf), 4); clipToOutline = true }
        val fill = View(c).apply { background = c.shape(c.clr(R.color.warn), 4) }
        val rest = View(c)
        bar.addView(fill, lp(0, MATCH, 1f))
        bar.addView(rest, lp(0, MATCH, 0.001f))
        graceFill = fill
        graceRest = rest
        box.addView(bar, c.lpDp(MATCH, 8))
        val row = c.hbox()
        row.addView(c.text(c.getString(R.string.home_grace_waiting), 14f, 400, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        val left = c.text(c.getString(R.string.home_grace_left, st.graceLeftS), 14f, 600)
        graceLeftText = left
        row.addView(left)
        box.addView(row, lp(MATCH, WRAP))
        return box
    }

    private fun stageRow(label: String, state: StageState, meta: String): View {
        val c = ctx
        val row = c.hbox(14).apply { pad(c, 20, 0, 20, 0) }
        row.addView(stageIcon(state), lp(c.dp(28), c.dp(28)))
        row.addView(c.text(label, 16f, 500, c.clr(if (state == StageState.PEND) R.color.on2 else R.color.on)), lp(0, WRAP, 1f))
        val metaColor = when (state) {
            StageState.ERR -> R.color.err
            StageState.PAUSE -> R.color.warn
            StageState.TODO, StageState.ACTIVE -> R.color.acc
            else -> R.color.on2
        }
        row.addView(c.text(meta, 14f, 500, c.clr(metaColor)))
        return row
    }

    private fun stageIcon(state: StageState): View {
        val c = ctx
        val box = FrameLayout(c)
        fun glyph(res: Int, tint: Int) = box.addView(c.icon(res, 16, tint), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
        when (state) {
            StageState.DONE -> { box.background = c.oval(c.clr(R.color.ok)); glyph(R.drawable.ic_check, c.clr(R.color.onok)) }
            StageState.ACTIVE -> {
                val ring = ImageView(c).apply { setImageResource(AppTheme.res(R.drawable.ic_ring_active)) }
                box.addView(ring, FrameLayout.LayoutParams(c.dp(28), c.dp(28)))
                box.addView(View(c).apply { background = c.oval(c.clr(R.color.pri)) }, FrameLayout.LayoutParams(c.dp(8), c.dp(8), Gravity.CENTER))
                rings.add(ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                    duration = 1400
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                })
            }
            StageState.PAUSE -> { box.background = c.oval(c.clr(R.color.warnc)); glyph(R.drawable.ic_pause, c.clr(R.color.warn)) }
            StageState.ERR -> { box.background = c.oval(c.clr(R.color.err)); glyph(R.drawable.ic_close, c.clr(R.color.onerr)) }
            StageState.TODO -> {
                box.background = c.oval(c.clr(R.color.pri))
                box.addView(c.text("!", 16f, 700, c.clr(R.color.onpri)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            StageState.PEND -> box.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(c.dp(2), c.clr(R.color.line))
            }
        }
        return box
    }
}
