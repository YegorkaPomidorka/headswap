package com.yegorkapomidorka.headswap

import android.content.Context
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Диагностика ADB-режима (только сборка -PcarlifePackage): за одну поездку понять, на каком шаге ГУ отваливается.
 * Ничего не отвечает и не влияет на AdbLink. Три части: доп. порты оригинала (9440 Data, 8270 заглушка bdim),
 * опрос файлов, которые ГУ пушит в /data/local/tmp (bdsc, bdim.jar), и опрос USB/ADB-свойств системы.
 */
object AdbDiag {
    private const val BUILD = "h60-diag1"
    private const val TMP_DIR = "/data/local/tmp"
    private const val FILES_POLL_MS = 1000L
    private const val PROPS_POLL_MS = 2000L
    private const val HEX_HEAD_BYTES = 64
    private const val STATS_EVERY_MS = 5000L

    private val EXTRA_PORTS = listOf("DATA" to 9440, "BDIM" to 8270)
    private val FILES = listOf(
        "bdim.jar", "bdsc", "bdsc16", "bdsc17", "bdsc18", "bdsc19", "bdsc19_01", "bdscdata", "sc.sock", "bdim.sock",
    )
    private val PROPS = listOf("sys.usb.state", "sys.usb.config", "persist.sys.usb.config", "init.svc.adbd", "service.adb.tcp.port")
    private val GLOBALS = listOf("adb_enabled", "adb_wifi_enabled")

    private class PortStat(val name: String, val port: Int) {
        var listening = false
        var bindError: String? = null
        var accepts = 0
        var bytes = 0L
    }

    private class FileState(val size: Long, val mtime: Long) {
        override fun equals(other: Any?) = other is FileState && other.size == size && other.mtime == mtime
        override fun hashCode() = size.hashCode() * 31 + mtime.hashCode()
    }

    private val lock = Any()
    private var started = false
    private val ports = EXTRA_PORTS.map { PortStat(it.first, it.second) }
    private val files = HashMap<String, FileState>()
    private val props = LinkedHashMap<String, String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Идемпотентно: всё на daemon-нитях, главную не блокирует. */
    fun start(ctx: Context) {
        synchronized(lock) {
            if (started) return
            started = true
        }
        AppLog.i("ADB-диаг: сборка $BUILD, старт опроса")
        val app = ctx.applicationContext
        for (p in ports) daemon("adb-diag-${p.name}") { acceptLoop(p) }
        daemon("adb-diag-files") { pollFiles() }
        daemon("adb-diag-props") { pollProps(app) }
    }

    /** Строка для device.txt: доп. порты, найденные файлы /data/local/tmp, последние значения свойств. */
    fun describe(): String = synchronized(lock) {
        val portsStr = ports.joinToString(", ") { p ->
            val state = when {
                p.listening -> "слушается"
                else -> p.bindError?.let { "ОШИБКА bind: $it" } ?: "не поднят"
            }
            "${p.name}:${p.port} $state, accept ${p.accepts}, байт ${p.bytes}"
        }
        val filesStr = files.entries.sortedBy { it.key }.joinToString(", ") { (name, st) -> "$name ${st.size} Б" }
            .ifEmpty { "нет" }
        val propsStr = props.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "ещё не опрошены" }
        "сборка $BUILD; доп. порты: $portsStr; файлы $TMP_DIR: $filesStr; свойства: $propsStr"
    }

    private fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).also { it.isDaemon = true }.start()
    }

    // ---- доп. слушатели ----

    private fun acceptLoop(p: PortStat) {
        val server = try {
            ServerSocket().also {
                try {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(p.port))
                } catch (e: Exception) {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            AppLog.e("ADB-диаг: bind ${p.name} :${p.port}", e)
            synchronized(lock) { p.bindError = "${e.javaClass.simpleName}: ${e.message}" }
            return
        }
        synchronized(lock) { p.listening = true }
        AppLog.i("ADB-диаг: слушаю ${p.name} :${p.port} (все интерфейсы)")
        while (true) {
            val sock = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) return
                AppLog.e("ADB-диаг: accept ${p.name} :${p.port}", e)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            synchronized(lock) { p.accepts++ }
            AppLog.i("ADB-диаг: accept ${p.name} :${p.port} от ${sock.remoteSocketAddress}")
            daemon("adb-diag-read-${p.name}") { readLoop(p, sock) }
        }
    }

    /** Блокирующее чтение до EOF: сокет держим, пока ГУ не закроет сам, в ответ ничего не пишем. */
    private fun readLoop(p: PortStat, sock: Socket) {
        val started = System.currentTimeMillis()
        var total = 0L
        var lastStats = started
        var lastLogged = 0L
        var firstDone = false
        val buf = ByteArray(4096)
        try {
            val ins = sock.getInputStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                synchronized(lock) { p.bytes += n }
                if (!firstDone) {
                    firstDone = true
                    AppLog.i("ADB-диаг: ${p.name} :${p.port} первые ${minOf(n, HEX_HEAD_BYTES)} из $n Б: ${hex(buf, minOf(n, HEX_HEAD_BYTES))}")
                    lastLogged = total
                    lastStats = System.currentTimeMillis()
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastStats >= STATS_EVERY_MS) {
                    AppLog.i("ADB-диаг: ${p.name} :${p.port} принято всего $total Б (+${total - lastLogged})", ui = false)
                    lastLogged = total
                    lastStats = now
                }
            }
            AppLog.i("ADB-диаг: ${p.name} :${p.port} EOF, принято $total Б за ${System.currentTimeMillis() - started} мс")
        } catch (e: Exception) {
            AppLog.e("ADB-диаг: ${p.name} :${p.port} ошибка чтения, принято $total Б за ${System.currentTimeMillis() - started} мс", e)
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun hex(b: ByteArray, n: Int): String {
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append("%02x".format(b[i].toInt() and 0xff))
        }
        return sb.toString()
    }

    // ---- файлы /data/local/tmp ----

    private fun pollFiles() {
        val dir = File(TMP_DIR)
        try {
            val list = dir.list()
            AppLog.i("ADB-диаг: каталог $TMP_DIR: list() ${if (list == null) "null" else "${list.size} записей"}, " +
                "canRead ${dir.canRead()}, canExecute ${dir.canExecute()}")
        } catch (e: Exception) {
            AppLog.e("ADB-диаг: каталог $TMP_DIR", e)
        }
        // exists()=false не отличает «файла нет» от запрета SELinux: errno stat на каталог и заведомо несуществующий
        // файл покажет, видим ли мы вообще /data/local/tmp (ENOENT - видим, EACCES - слепы)
        for (path in listOf(TMP_DIR, "$TMP_DIR/headswap-no-such-file")) {
            val res = try {
                android.system.Os.stat(path).let { "ok, mode ${Integer.toOctalString(it.st_mode)} uid ${it.st_uid}" }
            } catch (e: android.system.ErrnoException) {
                android.system.OsConstants.errnoName(e.errno) ?: e.message
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            AppLog.i("ADB-диаг: stat $path: $res")
        }
        while (true) {
            for (name in FILES) {
                try {
                    checkFile(name, File(dir, name))
                } catch (e: Exception) {
                    AppLog.e("ADB-диаг: файл $name", e)
                }
            }
            try {
                Thread.sleep(FILES_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun checkFile(name: String, f: File) {
        val now = if (f.exists()) FileState(f.length(), f.lastModified()) else null
        val path = "$TMP_DIR/$name"
        val msg = synchronized(lock) {
            val old = files[name]
            when {
                now == null && old == null -> null
                now == null -> {
                    files.remove(name)
                    "$path исчез"
                }
                old == null -> {
                    files[name] = now
                    "$path появился, ${now.size} Б, mtime ${timeFmt.format(Date(now.mtime))}"
                }
                old != now -> {
                    files[name] = now
                    "$path изменился, ${old.size} -> ${now.size} Б, mtime ${timeFmt.format(Date(now.mtime))}"
                }
                else -> null
            }
        }
        if (msg != null) AppLog.i("ADB-диаг: $msg")
    }

    // ---- свойства ----

    private fun pollProps(ctx: Context) {
        var propsErrLogged = false
        while (true) {
            for (key in PROPS) {
                val v = try {
                    getprop(key)
                } catch (e: Exception) {
                    if (!propsErrLogged) {
                        propsErrLogged = true
                        AppLog.e("ADB-диаг: getprop $key", e)
                    }
                    null
                }
                if (v != null) update(key, v)
            }
            for (key in GLOBALS) {
                val v = try {
                    Settings.Global.getInt(ctx.contentResolver, key, -1).toString()
                } catch (e: Exception) {
                    "ошибка ${e.javaClass.simpleName}"
                }
                update(key, v)
            }
            try {
                Thread.sleep(PROPS_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun update(key: String, value: String) {
        val old = synchronized(lock) { props.put(key, value) }
        if (old == value) return
        AppLog.i("ADB-диаг: $key = ${value.ifEmpty { "<пусто>" }} (${if (old == null) "первое чтение" else "было ${old.ifEmpty { "<пусто>" }}"})")
    }

    private fun getprop(key: String): String? {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", key))
        try {
            proc.outputStream.close()
            val text = proc.inputStream.bufferedReader().readText().trim()
            if (!proc.waitFor(1, TimeUnit.SECONDS)) return null
            return text
        } finally {
            proc.destroy()
        }
    }
}
