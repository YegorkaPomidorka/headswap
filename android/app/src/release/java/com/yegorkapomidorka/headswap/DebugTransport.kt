package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor

/** В release TCP-транспорта нет: мост работает только с USB-аксессуаром. */
object DebugTransport {
    fun port(intent: Intent?): Int? = null

    fun iccoa(intent: Intent?): Pair<String, String?>? = null

    fun tcp6(intent: Intent?): Boolean = false

    fun wifiB(intent: Intent?): Boolean = false

    fun tcp6Bind(intent: Intent?): String = "127.0.0.1"

    fun connect(port: Int): ParcelFileDescriptor? = null
}
