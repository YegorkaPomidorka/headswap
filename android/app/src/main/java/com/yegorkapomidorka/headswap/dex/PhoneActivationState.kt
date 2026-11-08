package com.yegorkapomidorka.headswap.dex

import android.content.Context

/**
 * The on-phone activation restarts this app, so the attempt lives on disk: when the new process starts, the
 * only proof is its own access state ([DelegatedDesktopAccess]); this record says whether one is expected.
 */
class PhoneActivationState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("headswap_phone_activation", Context.MODE_PRIVATE)

    /** The adb key was accepted by the phone at least once; later activations need no code. */
    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(value) { prefs.edit().putBoolean("paired", value).apply() }

    /** Порт беспроводной отладки последней активации: перезапуск хоста пробует его первым (vdm5). */
    var connectPort: Int
        get() = prefs.getInt("connect_port", 0)
        set(value) { prefs.edit().putInt("connect_port", value).commit() }

    val nonce: String? get() = prefs.getString("nonce", null)?.takeIf(PhoneActivationPolicy::isValidNonce)
    val startedMs: Long get() = prefs.getLong("started", 0)

    /** The launcher's failure line, read back from its log once the attempt expired. */
    var failure: String?
        get() = prefs.getString("failure", null)
        set(value) { prefs.edit().putString("failure", value).apply() }

    /** The log was already asked for: a failed read must not make every screen open reconnect. */
    var failureChecked: Boolean
        get() = prefs.getBoolean("failure_checked", false)
        set(value) { prefs.edit().putBoolean("failure_checked", value).apply() }

    // commit(): the process is replaced right after the dispatch.
    fun begin(nonce: String) {
        check(prefs.edit().putString("nonce", nonce).putLong("started", System.currentTimeMillis())
            .putBoolean("failure_checked", false).remove("failure").commit()) { "Не удалось сохранить состояние активации" }
    }

    fun clear() {
        prefs.edit().remove("nonce").remove("started").remove("failure").remove("failure_checked").commit()
    }

    fun progress(accessReady: Boolean, nowMs: Long = System.currentTimeMillis()): PhoneActivationPolicy.Progress =
        PhoneActivationPolicy.progress(if (nonce == null) 0 else startedMs, nowMs, accessReady)
}
