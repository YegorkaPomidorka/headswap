// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context

/** The only privilege backend is the PC-activated delegation; a saved preference is not proof of a live grant. */
object DesktopAccess {
    fun isReady(context: Context): Boolean = DelegatedDesktopAccess.isReady(context)
}
