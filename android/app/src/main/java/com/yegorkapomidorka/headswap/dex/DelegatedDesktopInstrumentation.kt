// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PC-started, main-process lifetime for a narrowly scoped, temporary UID delegation.
 * The activation token from the command line must match the one this app issued; only then permissions are adopted.
 * The result goes back through sendStatus/finish, which `am instrument -r` prints for the launcher.
 */
@Keep
class DelegatedDesktopInstrumentation : Instrumentation() {
    private var requestAudio = false
    private var token: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestAudio = DesktopAudioPolicy.requestFromArgument(arguments?.getString(DesktopAudioPolicy.ACTIVATION_ARGUMENT))
        token = arguments?.getString(ManualAccessPolicy.TOKEN_ARGUMENT)
        start()
    }

    override fun onStart() {
        if (Build.VERSION.SDK_INT < DelegatedDesktopAccess.MIN_SDK) {
            return reject("Нужен Android 13 или новее")
        }
        // First check, before UiAutomation exists: a wrong or missing token adopts nothing. Never log the token.
        if (!ActivationToken(targetContext).matches(token)) {
            Log.w(DelegatedDesktopAccess.LOG_TAG, "REJECTED activation token mismatch")
            return reject("Токен активации не совпал. Скопируйте свежую команду из HeadSwap на телефоне")
        }
        token = null
        activate()
    }

    private fun reject(reason: String) {
        // Nothing was adopted, so there is nothing to clean up; the launcher reads the reason from am's output.
        val result = Bundle().apply { putString("headswap_access", "failed"); putString("headswap_access_error", reason) }
        try { finish(Activity.RESULT_CANCELED, result) }
        catch (error: Throwable) {
            Log.w(DelegatedDesktopAccess.LOG_TAG, "Launcher unavailable; ending only the instrumentation process", error)
            Process.killProcess(Process.myPid())
        }
    }

    // The SDK IntDef omits FLAG_DONT_USE_ACCESSIBILITY; this public flag was verified
    // on the target phone and prevents engaging/suppressing accessibility services.
    @RequiresApi(DelegatedDesktopAccess.MIN_SDK)
    @SuppressLint("WrongConstant")
    private fun activate() {
        val context = targetContext
        val stop = CountDownLatch(1)
        var lease: DelegatedBootstrapState.Lease? = null
        var automation: UiAutomation? = null
        var adoptedHere = false
        var ready = false
        var failureReason: String? = null
        try {
            check(Application.getProcessName() == context.packageName) { "Activation requires the main app process" }
            automation = checkNotNull(getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY or
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
            check(!requestAudio || DexPrefs(context).usbPlaybackAudio) {
                "Сначала включите «Звук по USB» в HeadSwap; разрешение не добавлено"
            }
            val permissions = mutableListOf(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY,
                DelegatedDesktopAccess.INJECT_EVENTS, DelegatedDesktopAccess.REAL_GET_TASKS,
                DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL)
            if (requestAudio) permissions.add(DesktopAudioPolicy.PERMISSION)
            // Optional (not part of hasPermissions): shells that lack it simply do not pass it on.
            permissions.add(DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY)
            if (requestAudio) permissions.add(DesktopAudioPolicy.ROUTING_PERMISSION)
            automation.adoptShellPermissionIdentity(*permissions.toTypedArray())
            adoptedHere = true
            check(DelegatedDesktopAccess.hasPermissions(context)) { "Android не предоставил все права рабочего стола и управления музыкой. Повторите явную активацию HeadSwap." }
            check(!requestAudio || DesktopAudioAccess.hasPermission(context)) { "Android не разрешил захват звука приложений" }
            lease = DelegatedDesktopAccess.begin { stop.countDown() }
            ready = true
            runCatching { PhoneActivationService.onAccessReady(context) }
            Log.i(DelegatedDesktopAccess.LOG_TAG, "READY protocol=1 uid=${Process.myUid()} pid=${Process.myPid()} permissions=${permissions.joinToString(",")} playbackAudio=$requestAudio alwaysUnlocked=${DelegatedDesktopAccess.canKeepUnlocked(context)}")
            runCatching { sendStatus(0, Bundle().apply {
                putString("headswap_access", "ready")
                putString("headswap_pid", Process.myPid().toString())
                putString("headswap_media_control", (context.checkSelfPermission(DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL) ==
                    PackageManager.PERMISSION_GRANTED).toString())
                putString("headswap_playback_audio", (requestAudio && DesktopAudioAccess.hasPermission(context)).toString())
            }) }
            // USB detach and producer/service shutdown must never count this latch down.
            while (!stop.await(5, TimeUnit.SECONDS)) {
                check(DelegatedDesktopAccess.hasPermissions(context)) { "Desktop permissions were revoked" }
            }
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failureReason = ManualAccessPolicy.conciseFailure(error)
            Log.e(DelegatedDesktopAccess.LOG_TAG, "FAILED protocol=1 ${error.javaClass.simpleName}", error)
        } finally {
            lease?.let(DelegatedDesktopAccess::end)
            DesktopMusicPlayback.cancel("activation ended")
            // A failed adoption must not drop another package's active delegation.
            if (adoptedHere) runCatching { automation?.dropShellPermissionIdentity() }
            Log.i(DelegatedDesktopAccess.LOG_TAG, "STOPPED protocol=1")
            // Report failure only after cleanup: the CLI may retire its launcher once it reads this result.
            val result = Bundle().apply {
                failureReason?.let { putString("headswap_access", "failed"); putString("headswap_access_error", it) }
            }
            try { finish(if (ready && failureReason == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result) }
            catch (error: Throwable) {
                // UiAutomation's original shell binder may be gone. Target death then
                // makes ActivityManager finish instrumentation and remove its delegate.
                Log.w(DelegatedDesktopAccess.LOG_TAG, "Launcher unavailable; ending only the instrumentation process", error)
                Process.killProcess(Process.myPid())
            }
        }
    }
}
