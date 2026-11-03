// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Absence must be observed successfully; an IO error or old broadcast alone is not a detach. */
internal object UsbDetachPolicy {
    fun confirmedDetached(ownedUsbSession: Boolean, accessoryPresent: Boolean?): Boolean =
        ownedUsbSession && accessoryPresent == false
}
