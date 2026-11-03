package com.yegorkapomidorka.headswap.dex

import android.content.Context

/**
 * Per-installation secret that ties a PC-side activation to this app. The app creates it (SecureRandom,
 * 128 bit), keeps it in its private storage and shows it only inside the ready-made adb command. The
 * instrumentation compares it in constant time before it adopts any permission, so no `run-as` (and no
 * debuggable build) is needed. It is deliberately not rotated on use: after a reboot the same command
 * still works; the user rotates it explicitly with [regenerate].
 */
class ActivationToken(context: Context) {
    private val app = context.applicationContext ?: context
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): String = synchronized(LOCK) {
        prefs.getString(KEY, null)?.takeIf(ManualAccessPolicy::isValidToken) ?: store(ManualAccessPolicy.newToken())
    }

    /** Explicit user action: every command shown before stops working. */
    fun regenerate(): String = synchronized(LOCK) { store(ManualAccessPolicy.newToken()) }

    /** False when no token was ever issued, so a checking instrumentation never creates one itself. */
    fun matches(given: String?): Boolean = ManualAccessPolicy.tokensMatch(prefs.getString(KEY, null), given)

    fun options(audio: Boolean): ManualAccessPolicy.Options =
        ManualAccessPolicy.Options(audio, false, app.packageName, current())

    /** Typed after `adb -d shell`; carries the installed applicationId, the APK path and the token. */
    fun shellCommand(audio: Boolean): String = ManualAccessPolicy.shellCommand(app.applicationInfo.sourceDir, options(audio))

    /** Complete line for the computer, ready to paste. */
    fun adbCommand(audio: Boolean): String = ManualAccessPolicy.adbCommand(app.applicationInfo.sourceDir, options(audio))

    // commit(), not apply(): the instrumentation restarts the process and must find the file on disk.
    private fun store(value: String): String {
        check(prefs.edit().putString(KEY, value).commit()) { "Не удалось сохранить токен активации" }
        return value
    }

    private companion object {
        const val PREFS = "headswap_activation"
        const val KEY = "token"
        val LOCK = Any()
    }
}
