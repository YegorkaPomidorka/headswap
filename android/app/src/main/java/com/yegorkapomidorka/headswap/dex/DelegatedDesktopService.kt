// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep

/** Non-exported helper in :delegated; its producer may exit without ending the main bootstrap. */
@Keep
class DelegatedDesktopService : Service() {
    private val engines = mutableMapOf<String, DexUserService>()

    override fun onBind(intent: Intent?): IBinder? = try {
        check(Application.getProcessName() == "$packageName:delegated") { "Incorrect desktop helper process" }
        check(DelegatedDesktopAccess.hasPermissions(this)) { "Computer activation is not available" }
        val action = checkNotNull(intent?.action).also { check(it.startsWith(BIND_ACTION_PREFIX)) }
        val gateway = checkNotNull(IDelegatedInputGateway.Stub.asInterface(intent.extras?.getBinder(DelegatedInputGateway.EXTRA_BINDER)))
        check(!engines.containsKey(action)) { "Desktop binding already exists" }
        DexUserService.forDelegated(applicationContext, gateway).also { engines[action] = it }
    } catch (error: Exception) {
        Log.e(DelegatedDesktopAccess.LOG_TAG, "Cannot bind desktop producer", error)
        null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        engines.remove(intent?.action)?.shutdownDelegated()
        return false
    }

    override fun onDestroy() {
        try { engines.values.toList().forEach { it.shutdownDelegated() } }
        finally { engines.clear(); super.onDestroy() }
    }

    companion object { const val BIND_ACTION_PREFIX = "headswap.delegated.session." }
}
