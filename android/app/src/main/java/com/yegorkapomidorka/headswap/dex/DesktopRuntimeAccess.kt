// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.InputEvent
import android.view.Surface

/** The lease owner is shared; platform access stays behind this interface (the :delegated app process implements it). */
internal interface DesktopRuntimeAccess {
    fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int): VirtualDisplay
    fun createChildDisplay(surface: Surface, width: Int, height: Int, density: Int): VirtualDisplay
    fun inject(event: InputEvent, id: Int, actionButton: Int = 0)
    fun childTaskState(id: Int, packageName: String): Int
    fun registerParentDisplay(id: Int, token: IBinder, width: Int, height: Int) {}
    fun registerChildDisplay(parentId: Int, id: Int, token: IBinder, width: Int, height: Int) {}
    fun resizeRegisteredDisplay(id: Int, token: IBinder, width: Int, height: Int) {}
    fun unregisterDisplay(id: Int, token: IBinder) {}
    /** [component] - «пакет/класс» стороннего лаунчера вместо нашего; null - наш. */
    fun launchDesktop(mode: Int, id: Int, component: String?)
    fun launchChild(parentId: Int, id: Int, packageName: String, className: String)
}
