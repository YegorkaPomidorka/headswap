package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.Intent

/** What the settings screen sees and asks of the on-phone activation (the work itself is [PhoneActivationService]). */
object PhoneActivation {
    enum class Step { IDLE, SEARCHING, WAIT_CODE, PAIRING, CONNECTING, STARTING, FAILED }

    /** [detail] is the launcher's own failure line when the phone reported one. */
    class Status(val step: Step, val reason: AdbReason? = null, val detail: String? = null)

    @Volatile
    var status: Status = Status(Step.IDLE)
        internal set

    val busy: Boolean get() = status.step.let { it != Step.IDLE && it != Step.FAILED }

    /** Manual values are optional: ports from the two wireless-debugging screens and the code from the pairing dialog. */
    fun start(context: Context, pairPort: Int = 0, code: String? = null, connectPort: Int = 0) {
        if (busy) return
        context.startForegroundService(PhoneActivationService.intent(context, PhoneActivationService.ACTION_START)
            .putExtra(PhoneActivationService.EXTRA_PAIR_PORT, pairPort)
            .putExtra(PhoneActivationService.EXTRA_CODE, code)
            .putExtra(PhoneActivationService.EXTRA_CONNECT_PORT, connectPort))
    }

    fun cancel(context: Context) {
        if (!busy) return
        context.startService(PhoneActivationService.intent(context, PhoneActivationService.ACTION_CANCEL))
    }

    /** An expired attempt: ask the phone's launcher log why. Needs the saved key and wireless debugging. */
    fun collectFailure(context: Context) {
        if (busy) return
        context.startForegroundService(PhoneActivationService.intent(context, PhoneActivationService.ACTION_COLLECT))
    }

    /** Forget the saved key: the next activation asks for a new pairing code. */
    fun forgetPairing(context: Context) {
        PhoneAdb.forget(context)
        PhoneActivationState(context).apply { paired = false; clear() }
        status = Status(Step.IDLE)
    }

    /** Opens the wireless-debugging screen where the pairing dialog lives. */
    fun developerSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
