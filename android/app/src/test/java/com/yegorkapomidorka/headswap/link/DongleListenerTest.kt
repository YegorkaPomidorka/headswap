package com.yegorkapomidorka.headswap.link

import com.yegorkapomidorka.headswap.link.diag.WLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DongleListenerTest {
    private val accepted = LinkedBlockingQueue<Socket>()
    private val opened = ArrayList<Socket>()
    private var listener: DongleListener? = null

    @Before
    fun setUp() {
        WLog.clock = { System.currentTimeMillis() }
        WLog.sink = { }
    }

    @After
    fun tearDown() {
        listener?.close()
        opened.forEach { runCatching { it.close() } }
        accepted.forEach { runCatching { it.close() } }
    }

    private fun up(): DongleListener =
        DongleListener(port = 0, bind = InetAddress.getByName("127.0.0.1")) { accepted.put(it) }.open().also { listener = it }

    private fun connect(l: DongleListener): Socket =
        Socket(InetAddress.getByName("127.0.0.1"), l.boundPort).also { opened += it }

    @Test
    fun firstSocketGoesToCallbackWithOptions() {
        val l = up()
        connect(l)
        val s = accepted.poll(3, TimeUnit.SECONDS)
        assertNotNull(s)
        assertTrue(s!!.tcpNoDelay)
        assertTrue(s.sendBufferSize > 0)
    }

    @Test
    fun secondSocketIsRejectedWhileFirstLives() {
        val l = up()
        connect(l)
        val first = accepted.poll(3, TimeUnit.SECONDS)
        assertNotNull(first)
        val second = connect(l)
        // сервер закрывает лишнего клиента: чтение даёт EOF (или reset), в колбэк он не попадает
        val eof = try {
            second.soTimeout = 3000
            second.getInputStream().read() == -1
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue(eof)
        assertEquals(null, accepted.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun newSocketAcceptedAfterFirstClosed() {
        val l = up()
        connect(l)
        val first = accepted.poll(3, TimeUnit.SECONDS)!!
        first.close()
        connect(l)
        assertNotNull(accepted.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun closeReleasesPort() {
        val l = up()
        assertTrue(l.boundPort > 0)
        l.close()
        assertEquals(-1, l.boundPort)
    }
}
