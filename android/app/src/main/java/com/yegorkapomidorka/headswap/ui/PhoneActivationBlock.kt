package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.dex.AdbReason
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.PhoneActivation
import com.yegorkapomidorka.headswap.dex.PhoneActivationPolicy
import com.yegorkapomidorka.headswap.dex.PhoneActivationState

/**
 * «Активация с телефона» в разделе «Режим»: статус попытки, кнопка, ручной ввод портов и кода. Сама работа идёт в
 * PhoneActivationService (приложение при активации перезапускается), здесь только запуск и показ.
 */
class PhoneActivationBlock(private val host: MainActivity) {
    private val c = host
    private val state = PhoneActivationState(c)
    val view: LinearLayout = c.vbox(8)
    private val status: TextView
    private val hint: TextView
    private val activate: LinearLayout
    private val cancel: LinearLayout
    private val manualToggle: LinearLayout
    private val manualBox: LinearLayout
    private val forget: LinearLayout
    private val pairPort: EditText
    private val code: EditText
    private val connectPort: EditText
    private var awaitingPermission = false
    /** Номер попытки, для которой журнал уже запрошен в этой сессии экрана: тик идёт каждые 500 мс. */
    private var collected: String? = null

    init {
        view.addView(c.sectionTitle(c.getString(R.string.pa_title)))
        status = c.text("", 16f, 600).also { view.addView(it, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0)) }
        hint = c.text("", 13f, 400, c.clr(R.color.on2)).also { view.addView(it, lp(WRAP, WRAP).margins(c, 8, 2, 8, 0)) }
        activate = c.primaryButton(c.getString(R.string.pa_button), heightDp = 60, sp = 17f).apply { setOnClickListener { onActivate() } }
        view.addView(activate, c.lpDp(MATCH, 60).margins(c, 0, 4, 0, 0))
        cancel = c.tonalButton(c.getString(R.string.pa_cancel)).apply {
            setOnClickListener { PhoneActivation.cancel(c) }
        }
        view.addView(cancel, c.lpDp(MATCH, 52))
        manualToggle = c.linkButton(c.getString(R.string.pa_manual_toggle), 48, 15f).apply {
            setOnClickListener { manualBox.visibility = if (manualBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        view.addView(manualToggle, c.lpDp(MATCH, 48))
        manualBox = c.vbox(8).apply { visibility = View.GONE }
        manualBox.addView(c.text(c.getString(R.string.pa_manual_hint), 13f, 400, c.clr(R.color.on2)), lp(WRAP, WRAP).margins(c, 8, 0, 8, 0))
        pairPort = field(R.string.pa_field_pair_port)
        code = field(R.string.pa_field_code)
        connectPort = field(R.string.pa_field_connect_port)
        listOf(pairPort, code, connectPort).forEach { manualBox.addView(it, c.lpDp(MATCH, 52)) }
        manualBox.addView(c.tonalButton(c.getString(R.string.pa_manual_go)).apply { setOnClickListener { onManual() } }, c.lpDp(MATCH, 52))
        view.addView(manualBox, lp(MATCH, WRAP))
        forget = c.linkButton(c.getString(R.string.pa_forget), 48, 15f).apply {
            setOnClickListener {
                PhoneActivation.forgetPairing(c)
                Toast.makeText(c, R.string.pa_forget_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        view.addView(forget, c.lpDp(MATCH, 48))
        refresh()
    }

    /** Из тика экрана (500 мс) и onShow: все проверки дешёвые. */
    fun refresh() {
        val live = PhoneActivation.status
        val access = DelegatedDesktopAccess.status(c)
        val paired = state.paired
        val busy = PhoneActivation.busy
        val (text, color) = when {
            busy -> c.getString(stepText(live.step)) to R.color.warn
            live.step == PhoneActivation.Step.FAILED && access != DelegatedDesktopAccess.Status.READY ->
                c.getString(R.string.pa_s_failed, live.detail ?: c.getString(live.reason?.messageRes ?: AdbReason.INTERNAL.messageRes)) to R.color.warn
            else -> attempt(access, state)
        }
        status.text = text
        status.setTextColor(c.clr(color))
        hint.text = c.getString(if (paired) R.string.pa_hint_paired else R.string.pa_hint)
        (activate.getChildAt(0) as TextView).text = c.getString(if (paired || access == DelegatedDesktopAccess.Status.READY) R.string.pa_button_again else R.string.pa_button)
        activate.visibility = if (busy) View.GONE else View.VISIBLE
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        manualToggle.visibility = if (busy) View.GONE else View.VISIBLE
        if (busy) manualBox.visibility = View.GONE
        forget.visibility = if (paired && !busy) View.VISIBLE else View.GONE
    }

    /** Итог последней попытки после перезапуска приложения: показываем по состоянию доступа, а не по сообщению. */
    private fun attempt(access: DelegatedDesktopAccess.Status, state: PhoneActivationState): Pair<String, Int> {
        val nonce = state.nonce
        if (nonce != null) {
            when (state.progress(access == DelegatedDesktopAccess.Status.READY)) {
                PhoneActivationPolicy.Progress.READY -> state.clear()
                PhoneActivationPolicy.Progress.PENDING ->
                    return c.getString(R.string.pa_s_pending) to R.color.warn
                PhoneActivationPolicy.Progress.EXPIRED -> {
                    // Перезапуск мог прервать чтение журнала: просим один раз, дальше показываем что есть.
                    if (!state.failureChecked && collected != nonce) {
                        collected = nonce
                        PhoneActivation.collectFailure(c)
                    }
                    val why = state.failure
                    if (access != DelegatedDesktopAccess.Status.READY) {
                        return (if (why != null) c.getString(R.string.pa_s_expired_why, why) else c.getString(R.string.pa_s_expired)) to R.color.warn
                    }
                    state.clear()
                }
            }
        }
        return when (access) {
            DelegatedDesktopAccess.Status.READY -> c.getString(R.string.bm_act_on) to R.color.ok
            DelegatedDesktopAccess.Status.PERMISSION_LOST -> c.getString(R.string.bm_act_lost) to R.color.warn
            DelegatedDesktopAccess.Status.INACTIVE -> c.getString(R.string.bm_act_off) to R.color.warn
        }
    }

    private fun stepText(step: PhoneActivation.Step): Int = when (step) {
        PhoneActivation.Step.WAIT_CODE -> R.string.pa_s_wait_code
        PhoneActivation.Step.PAIRING -> R.string.pa_s_pairing
        PhoneActivation.Step.CONNECTING -> R.string.pa_s_connecting
        PhoneActivation.Step.STARTING -> R.string.pa_s_starting
        else -> R.string.pa_s_searching
    }

    // --- запуск ---

    private fun missingPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            add(Manifest.permission.POST_NOTIFICATIONS)
        // Android 17: без разрешения mDNS-поиск портов режется молча, сам loopback к adbd его не требует
        if (Build.VERSION.SDK_INT >= 37 && c.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun onActivate() {
        if (PhoneActivation.busy) return
        val ask = missingPermissions()
        if (ask.isNotEmpty()) {
            awaitingPermission = true
            host.requestPermissions(ask.toTypedArray(), REQ_PHONE_ACTIVATION)
            return
        }
        begin()
    }

    /** Результат системного запроса: отказ не блокирует, у человека остаётся ручной ввод. */
    fun onPermissionResult() {
        if (!awaitingPermission) return
        awaitingPermission = false
        begin()
    }

    private fun begin() {
        val needsCode = !state.paired
        if (needsCode && !c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            status.text = c.getString(R.string.pa_need_notif)
            status.setTextColor(c.clr(R.color.warn))
            manualBox.visibility = View.VISIBLE
            return
        }
        PhoneActivation.start(c)
        // код сопряжения вводят в уведомлении над системным окном: ведём человека на экран отладки
        if (needsCode) runCatching { c.startActivity(PhoneActivation.developerSettingsIntent()) }
        refresh()
    }

    private fun onManual() {
        if (PhoneActivation.busy) return
        val pair = pairPort.text.toString().toIntOrNull() ?: 0
        val connect = connectPort.text.toString().toIntOrNull() ?: 0
        val digits = code.text.toString().trim()
        if (!state.paired) {
            if (!PhoneActivationPolicy.isValidPort(pair)) { pairPort.error = c.getString(R.string.pa_field_bad_port); return }
            if (!PhoneActivationPolicy.isValidCode(digits)) { code.error = c.getString(R.string.pa_field_bad_code); return }
        } else if (!PhoneActivationPolicy.isValidPort(connect)) {
            connectPort.error = c.getString(R.string.pa_field_bad_port)
            return
        }
        if (connect != 0 && !PhoneActivationPolicy.isValidPort(connect)) { connectPort.error = c.getString(R.string.pa_field_bad_port); return }
        PhoneActivation.start(c, if (state.paired) 0 else pair, if (state.paired) null else digits, connect)
        code.text.clear()
        refresh()
    }

    private companion object {
        /** Уведомления (ввод кода) и локальная сеть (mDNS-поиск портов) перед активацией; 38-41 заняты в SettingsSectionScreen и CarPairing. */
        const val REQ_PHONE_ACTIVATION = 42
    }

    private fun field(hintRes: Int): EditText = EditText(c).apply {
        hint = c.getString(hintRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(c.clr(R.color.on))
        setHintTextColor(c.clr(R.color.on2))
        background = c.shape(c.clr(R.color.surf2), 14)
        pad(c, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
        typeface = c.sans(400)
    }
}
