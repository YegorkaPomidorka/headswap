package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.aa.CarPreset

/**
 * Онбординг (Onboarding.dc.html) и чеклист готовности. Первый шаг - выбор режима, дальше путь зависит от него
 * (OnboardingPath): Android Auto - прежние десять шагов из Readiness.STEPS, свой лаунчер - режим разработчика, активация и общие шаги.
 * При первом запуске уже выполненные шаги (определяются автоматически или отмечены вручную) пропускаются, при повторном
 * прохождении показываются все. В конце - чеклист со статусом каждого пункта; тот же чеклист открывается из Настроек.
 */
class OnboardingScreen(host: MainActivity, private val showAll: Boolean, startAtChecklist: Boolean, startAt: StepId? = null) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root
    private val ui = UiPrefs(ctx)
    private val aa = AaPrefs(ctx)
    private val desktopOk = BridgeMode.desktopSupported(Build.VERSION.SDK_INT)

    /** Режим, по которому строится путь: сохранённый в настройках, при отладке шагом лаунчера - лаунчер. */
    private var chosen = OnboardingPath.modeFor(BridgeMode.effective(aa.bridgeMode, Build.VERSION.SDK_INT), startAt)
    private var queue: List<StepInfo>
    private var pos = 0

    /** Блок активации живёт между перерисовками шага: идущая попытка и введённые порты не теряются. */
    private var activation: PhoneActivationBlock? = null
    private var renderedReady = false
    private var checklist = false

    /** Шаг открыт из чеклиста: после него возвращаемся в чеклист. */
    private var single: StepInfo? = null
    private var shownDone = false

    /** Машина, отмеченная на шаге выбора, но ещё не применённая. */
    private var carChoice: CarPreset? = null

    init {
        queue = buildQueue(startAt)
        checklist = startAtChecklist || queue.isEmpty()
        if (startAt != null) pos = queue.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        render()
    }

    private fun buildQueue(keep: StepId?): List<StepInfo> =
        OnboardingPath.queue(chosen, desktopOk, showAll, { Readiness.isDone(ctx, it) }, keep).map { Readiness.info(it) }

    /** Строки чеклиста: шаги выбранного пути без выбора режима. */
    private fun checklistSteps(): List<StepInfo> = OnboardingPath.full(chosen, false).map { Readiness.info(it) }

    override fun tick() {
        val block = activation ?: return
        if (checklist || current()?.id != StepId.ACTIVATE) return
        block.refresh()
        if (block.ready != renderedReady) render()      // «Дальше» открывается, когда заголовок стал «Активировано»
    }

    override fun onShow() {
        // вернулись из системных настроек: статус мог измениться
        if (checklist) render() else if (current() != null && Readiness.isDone(ctx, current()!!.id) != shownDone) render()
    }

    override fun onPermissionResult() {
        val s = current() ?: return
        if (!checklist && s.id == StepId.ACTIVATE) { activation?.onPermissionResult(); return }      // render() потерял бы ожидание разрешения
        if (!checklist && Readiness.isDone(ctx, s.id) && (s.id == StepId.NOTIF || s.id == StepId.MIC)) next() else render()
    }

    override fun onBack(): Boolean {
        when {
            checklist -> { ui.onboardingDone = true; return false }
            single != null -> { single = null; checklist = true; render() }
            pos > 0 -> { pos--; render() }
            else -> { ui.onboardingDone = true; return false }      // вышли из онбординга сами: больше не навязываем
        }
        return true
    }

    private fun current(): StepInfo? = if (checklist) null else (single ?: queue.getOrNull(pos))

    private fun next() {
        if (single != null) {
            single = null
            checklist = true
        } else if (pos + 1 < queue.size) {
            pos++
        } else {
            checklist = true
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val s = if (checklist) null else current()
        when {
            checklist -> renderChecklist()
            s == null -> return
            s.id == StepId.CAR -> renderCar(s)
            s.id == StepId.MODE -> renderMode(s)
            s.id == StepId.ACTIVATE -> renderActivate(s)
            else -> renderStep(s)
        }
    }

    /** Полоса «Шаг n из N» с точками. */
    private fun renderProgress(s: StepInfo) {
        val c = ctx
        // из чеклиста номер считается по полному пути, иначе по очереди (выполненные шаги в ней уже пропущены)
        val ids = if (single != null) OnboardingPath.full(chosen, desktopOk) else queue.map { it.id }
        val (n, total) = OnboardingPath.progress(ids, s.id)
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_step_progress, n, total), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
            val dots = c.hbox(6)
            for (i in 1..total) {
                dots.addView(View(c).apply { background = c.shape(c.clr(if (i <= n) R.color.pri else R.color.line), 3) }, lp(c.dp(if (total > 8) 14 else if (total > 6) 18 else 28), c.dp(6)))
            }
            addView(dots)
        }
        root.addView(top, c.lpDp(MATCH, 48))
    }

    private fun renderStep(s: StepInfo) {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        shownDone = done
        renderProgress(s)
        renderTitle(s)

        // прокручивается только картинка и текст, заголовок остаётся на месте
        val body = c.vbox().apply {
            // с подсказкой и на шагах с окном Android Auto макет компактнее: текст целиком виден без прокрутки
            val compact = s.hint != null || s.id == StepId.AA_DEV || s.id == StepId.AA_UNKNOWN
            addView(c.stepMockCard(Mocks.build(c, s.id), compact), lp(MATCH, WRAP).margins(c, 0, 4, 0, 0))
            val text = c.vbox(10).apply { pad(c, 0, 18, 0, 36) }     // нижний отступ больше затемнения края: конец текста не тонет в fade
            if (done) text.addView(c.doneChip(c.getString(R.string.onb_done_chip)), lp(WRAP, WRAP))
            text.addView(c.numberedList(s.listItems(c)), lp(MATCH, WRAP))
            s.hint?.let { text.addView(c.stepHint(c.getString(it)), lp(MATCH, WRAP)) }
            addView(text, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        val buttons = c.vbox(4)
        val primaryLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_next) else c.getString(s.primary)
        buttons.addView(c.primaryButton(primaryLabel).apply { setOnClickListener { onPrimary(s, done) } })
        val showExtra = s.extra != null && !done && (s.id != StepId.BACKGROUND || Readiness.isXiaomi())
        if (showExtra) {
            buttons.addView(c.containerButton(c.getString(s.extra!!)).apply { setOnClickListener { onExtra(s) } })
        }
        val skipLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_back) else c.getString(s.skip)
        buttons.addView(c.linkButton(skipLabel, 48).apply { setOnClickListener { onSkip(s, done) } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** Первый шаг: Android Auto или свой лаунчер. Выбор пишется в тот же режим моста, что переключатель в «Режиме». */
    private fun renderMode(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val launcher = chosen == BridgeMode.DESKTOP
        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(
            c.modeCard(R.drawable.ic_car, c.getString(R.string.bm_aa), c.getString(R.string.step_mode_aa_body), emptyList(), !launcher) { pickMode(BridgeMode.ANDROID_AUTO) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
        )
        body.addView(
            c.modeCard(R.drawable.ic_plug, c.getString(R.string.bm_own_launcher), c.getString(R.string.step_mode_own_body), listOf("Headswap", "CarDash"), launcher) { pickMode(BridgeMode.DESKTOP) },
            lp(MATCH, WRAP),
        )
        if (launcher) {
            body.addView(
                c.modeWarning(c.getString(R.string.step_mode_warn_title), c.getString(R.string.step_mode_warn_body), c.getString(R.string.step_mode_warn_risk)),
                lp(MATCH, WRAP),
            )
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))
        root.addView(c.primaryButton(c.getString(s.primary)).apply {
            setOnClickListener {
                ui.setManualDone(s.id.key, true)
                AppLog.i("онбординг, режим: ${chosen.key}")
                next()
            }
        })
    }

    private fun pickMode(m: BridgeMode) {
        if (aa.bridgeMode != m) {
            aa.bridgeMode = m
            AppLog.i("настройка: режим моста -> ${m.key} (онбординг)")
        }
        if (chosen != m) {
            chosen = m
            queue = buildQueue(StepId.MODE)      // хвост очереди зависит от режима
            pos = queue.indexOfFirst { it.id == StepId.MODE }.coerceAtLeast(0)
        }
        render()
    }

    /**
     * Шаг активации лаунчера: заголовок, статус, макет, пункты и кнопки ведёт PhoneActivationBlock (тот же поток, что в «Режиме»).
     * «Дальше» открывается, когда заголовок стал зелёным «Активировано»; пока нет - «Позже».
     */
    private fun renderActivate(s: StepInfo) {
        val c = ctx
        val block = activation ?: PhoneActivationBlock(host, c.resources.getStringArray(s.list!!).toList(), s.hint!!).also { activation = it }
        block.refresh()
        shownDone = Readiness.isDone(c, s.id)
        renderedReady = block.ready
        renderProgress(s)
        (block.view.parent as? ViewGroup)?.removeView(block.view)
        val body = c.vbox().apply {
            pad(c, 0, 0, 0, 36)
            addView(block.view, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))
        val ready = block.ready
        val buttons = c.vbox(4)
        buttons.addView(c.primaryButton(c.getString(R.string.onb_next)).apply {
            isEnabled = ready
            alpha = if (ready) 1f else 0.45f
            setOnClickListener { if (ready) next() }
        })
        buttons.addView(c.linkButton(c.getString(if (ready) R.string.onb_back else s.skip), 48).apply {
            setOnClickListener { if (ready) stepBack() else next() }
        })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /**
     * Последний шаг: какая у вас машина. Выпадашка только показывает настройки машины, «Применить» пишет их (AaPrefs.applyCar),
     * «Пропустить» (для опытных) ничего не трогает и ставит ручную отметку шага.
     */
    private fun renderCar(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val aa = AaPrefs(c)
        if (carChoice == null) carChoice = aa.carPreset

        val applyBtn = c.primaryButton(c.getString(s.primary))
        fun refresh() {
            applyBtn.isEnabled = carChoice != null
            applyBtn.alpha = if (carChoice != null) 1f else 0.45f
        }
        val chooser = CarChooser(c, withOther = false) { p ->
            carChoice = p
            refresh()
        }
        carChoice?.let { chooser.show(it) }

        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(chooser.view, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        applyBtn.setOnClickListener {
            val p = carChoice ?: return@setOnClickListener
            aa.applyCar(p)
            AppLog.i("онбординг, мой автомобиль: ${p.name}, применены ${p.settings}")
            next()
        }
        refresh()
        val buttons = c.vbox(4)
        buttons.addView(applyBtn)
        buttons.addView(c.linkButton(c.getString(s.skip), 48).apply {
            setOnClickListener {
                AppLog.i("онбординг, мой автомобиль: пропущен")
                ui.setManualDone(s.id.key, true)
                next()
            }
        })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** Прокрутка с затемнением края: видно, что текст продолжается. */
    private fun fadingScroll(content: View) = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true     // постоянная полоса ложится на правый край карточки-иллюстрации; хвост текста и так виден по затемнению
        addView(content)
    }

    /** Заголовок шага под строкой «Шаг n из N»: вне прокрутки. */
    private fun renderTitle(s: StepInfo) {
        val c = ctx
        root.addView(
            c.text(c.getString(s.title), 24f, 600).apply { letterSpacing = -0.01f; lineHeight = c.dp(30) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 16),
        )
    }

    private fun onPrimary(s: StepInfo, done: Boolean) {
        val c = ctx
        if (done && s.id != StepId.FIRST) { next(); return }
        when (s.id) {
            StepId.AA_DEV, StepId.AA_UNKNOWN -> Actions.openAaSettings(c)
            StepId.AUTOSTART -> Actions.startServerFromUi(c)
            StepId.NOTIF -> requestNotifications()
            StepId.BACKGROUND -> if (!Readiness.batteryOk(c)) Readiness.requestIgnoreBattery(c) else Readiness.openXiaomiAutostart(c)
            StepId.MIC -> requestMic()
            StepId.BLUETOOTH -> Readiness.openBluetoothSettings(c)
            StepId.LOCATION -> Readiness.openLocationSettings(c)
            StepId.FIRST -> { ui.setManualDone(s.id.key, true); next() }
            StepId.CAR -> next()      // свои кнопки в renderCar
            StepId.DEVMODE -> Readiness.openDeviceInfo(c)
            StepId.MODE, StepId.ACTIVATE -> {}      // свои кнопки в renderMode / renderActivate
        }
    }

    private fun onExtra(s: StepInfo) {
        try {
            when (s.id) {
                StepId.BACKGROUND -> Readiness.openXiaomiAutostart(ctx)
                else -> {}
            }
        } catch (e: Exception) {
            AppLog.e("шаг ${s.id}: не открыть системный экран", e)
        }
    }

    private fun onSkip(s: StepInfo, done: Boolean) {
        if (done && s.id != StepId.FIRST) { stepBack(); return }
        when (s.id) {
            StepId.FIRST -> stepBack()
            StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.BACKGROUND, StepId.BLUETOOTH, StepId.LOCATION, StepId.DEVMODE -> { ui.setManualDone(s.id.key, true); next() }
            else -> next()
        }
    }

    private fun stepBack() {
        if (!onBack()) host.back()
    }

    private fun requestNotifications() {
        val c = ctx
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && firstAsk(p)) {
            markAsked(p)
            host.requestPermissions(arrayOf(p), 1)
            return
        }
        // системный диалог уже был (или SDK < 33): включаем в настройках уведомлений приложения
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestMic() {
        val c = ctx
        if (!firstAsk(Manifest.permission.RECORD_AUDIO) && !host.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Readiness.appDetails(c)     // отказано насовсем: разрешить можно только в карточке приложения
            return
        }
        markAsked(Manifest.permission.RECORD_AUDIO)
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun firstAsk(p: String) = !ui.isManualDone("asked_$p")

    private fun markAsked(p: String) = ui.setManualDone("asked_$p", true)

    // --- чеклист ---

    private fun renderChecklist() {
        val c = ctx
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_checklist_title), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val list = c.vbox(12).apply { pad(c, 0, 8, 0, 8) }
        val steps = checklistSteps()
        val doneCount = steps.count { Readiness.isDone(c, it.id) }
        val heading = if (doneCount == steps.size) c.getString(R.string.onb_all_ready) else c.getString(R.string.onb_ready_count, doneCount, steps.size)
        list.addView(c.text(heading, 28f, 600).apply {
            letterSpacing = -0.01f
            lineHeight = c.dp(34)
        })
        list.addView(c.text(c.getString(R.string.onb_checklist_hint), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        val card = c.card(24).apply { pad(c, 0, 6, 0, 6) }
        for (s in steps) card.addView(checkRow(s), c.lpDp(MATCH, 60))
        list.addView(card, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(list), lp(MATCH, 0, 1f))

        root.addView(c.primaryButton(c.getString(R.string.onb_done)).apply {
            setOnClickListener {
                ui.onboardingDone = true
                host.back()
            }
        })
    }

    private fun checkRow(s: StepInfo): View {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        val auto = Readiness.auto(c, s.id)
        val statusText: String
        val statusColor: Int
        when {
            done -> { statusText = c.getString(if (auto == null) R.string.onb_status_marked else R.string.onb_status_ready); statusColor = R.color.ok }
            auto == null -> { statusText = c.getString(R.string.onb_status_unmarked); statusColor = R.color.on2 }
            else -> { statusText = c.getString(R.string.onb_status_off); statusColor = R.color.warn }
        }
        return c.hbox(14).apply {
            pad(c, 20, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener {
                single = s
                checklist = false
                render()
            }
            val mark = FrameLayout(c)
            when {
                done -> {
                    mark.background = c.oval(c.clr(R.color.ok))
                    mark.addView(c.icon(R.drawable.ic_check, 16, c.clr(R.color.onok)), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                }
                auto == null -> mark.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0)
                    setStroke(c.dp(2), c.clr(R.color.line))
                }
                else -> {
                    mark.background = c.oval(c.clr(R.color.warn))
                    mark.addView(c.text("!", 16f, 700, c.clr(R.color.warnc)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
            addView(mark, lp(c.dp(28), c.dp(28)))
            addView(c.vbox(1).apply {
                addView(c.text(c.getString(s.short), 16f, 500))
                addView(c.text(statusText, 13f, 500, c.clr(statusColor)))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }
}
