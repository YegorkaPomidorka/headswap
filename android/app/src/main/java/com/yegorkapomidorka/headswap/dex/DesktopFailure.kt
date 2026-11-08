// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Why a desktop source ended; the host decides whether a recovery attempt is worth making. */
enum class DesktopFailure {
    HELPER_UNAVAILABLE,
    /**
     * h768: shell-хост VirtualDevice умер посреди сессии (Samsung перезапускает adbd при гашении экрана без кабеля) и унёс
     * дисплей. Хост поднимает [VdmHostRestart]; дисплей пересоздаём при любом транспорте и заблокированном телефоне.
     */
    VDM_DISPLAY_LOST,
    OTHER,
}
