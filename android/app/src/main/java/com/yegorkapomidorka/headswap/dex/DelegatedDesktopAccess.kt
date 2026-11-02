// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** A selected backend is not proof of access: the live bootstrap and all grants are required. */
object DelegatedDesktopAccess {
    const val PROTOCOL = 1
    const val LOG_TAG = "HeadSwapDelegated"
    const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
    const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    const val REAL_GET_TASKS = "android.permission.REAL_GET_TASKS"
    const val MEDIA_CONTENT_CONTROL = "android.permission.MEDIA_CONTENT_CONTROL"
    /** Optional: without it the displays are created as before and the phone lock covers them. */
    const val ADD_ALWAYS_UNLOCKED_DISPLAY = "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"
    /** Trusted virtual displays and the adopted permissions are Android 13+; the app itself still runs on minSdk. */
    const val MIN_SDK = 33
    enum class Status { INACTIVE, PERMISSION_LOST, READY }

    private val bootstrap = DelegatedBootstrapState()

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

    fun status(context: Context): Status {
        if (!isSupported || !bootstrap.isActive()) return Status.INACTIVE
        return if (hasPermissions(context)) Status.READY else Status.PERMISSION_LOST
    }

    fun isReady(context: Context): Boolean = status(context) == Status.READY

    /** Also usable in the separate app-UID producer process, which has no bootstrap registry. */
    fun hasPermissions(context: Context): Boolean = isSupported && runCatching {
        context.checkSelfPermission(ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(REAL_GET_TASKS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Also usable in the producer process: the adopted permission belongs to the app UID. */
    fun canKeepUnlocked(context: Context): Boolean = isSupported && runCatching {
        context.checkSelfPermission(ADD_ALWAYS_UNLOCKED_DISPLAY) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    internal fun begin(stop: () -> Unit): DelegatedBootstrapState.Lease = bootstrap.begin(stop)
    internal fun end(lease: DelegatedBootstrapState.Lease) = bootstrap.end(lease)

    /** Explicitly ends activation and may close the app. USB/session Stop must never call this. */
    fun requestStop(): Boolean = bootstrap.requestStop()
}
