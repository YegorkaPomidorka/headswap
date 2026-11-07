package com.yegorkapomidorka.headswap.dex

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.view.Display

/** Дисплей рабочего стола: свой VirtualDisplay этого процесса или дисплей VirtualDevice в главном процессе (vdm1). */
internal interface OwnedDisplay {
    val display: Display
    fun resize(width: Int, height: Int, density: Int)
    fun release()
}

internal class LocalDisplay(private val virtual: VirtualDisplay) : OwnedDisplay {
    override val display: Display get() = virtual.display
    override fun resize(width: Int, height: Int, density: Int) = virtual.resize(width, height, density)
    override fun release() = virtual.release()
}

/** [owner] держит главный процесс: смерть этого процесса закрывает дисплей и там. */
internal class GatewayDisplay(private val gateway: IDelegatedInputGateway, private val id: Int,
    private val owner: Binder, displays: DisplayManager) : OwnedDisplay {
    override val display: Display = checkNotNull(displays.getDisplay(id)) { "Экран виртуального устройства не найден" }
    override fun resize(width: Int, height: Int, density: Int) = gateway.resizeDeviceDisplay(id, width, height, density)
    override fun release() = gateway.releaseDeviceDisplay(id)
}
