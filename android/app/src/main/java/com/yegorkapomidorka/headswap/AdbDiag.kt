package com.yegorkapomidorka.headswap

import android.content.Context
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Минимальная диагностика ADB-режима (только сборка -PcarlifePackage): видно, на каком шаге ГУ отваливается.
 * Ничего не слушает и не отвечает, на AdbLink не влияет (порт 8270 принадлежит настоящему bdim, который ГУ запускает
 * через adb shell, занимать его нельзя). Две части: опрос файлов, которые ГУ пушит в /data/local/tmp,
 * и опрос USB/ADB-состояния системы.
 */
object AdbDiag {
    private const val BUILD = "adb-2.4.0"
    private const val TMP_DIR = "/data/local/tmp"
    private const val FILES_POLL_MS = 1000L
    private const val PROPS_POLL_MS = 2000L

    private val FILES = listOf("bdim.jar", "bdscdata", "sc.sock")
    private val PROPS = listOf("sys.usb.state")
    private val GLOBALS = listOf("adb_enabled")

    private class FileState(val size: Long, val mtime: Long) {
        override fun equals(other: Any?) = other is FileState && other.size == size && other.mtime == mtime
        override fun hashCode() = size.hashCode() * 31 + mtime.hashCode()
    }

    private val lock = Any()
    private var started = false
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
        daemon("adb-diag-files") { pollFiles() }
        daemon("adb-diag-props") { pollProps(app) }
    }

    /** Строка для device.txt: найденные файлы /data/local/tmp и последние значения свойств. */
    fun describe(): String = synchronized(lock) {
        val filesStr = files.entries.sortedBy { it.key }.joinToString(", ") { (name, st) -> "$name ${st.size} Б" }
            .ifEmpty { "нет" }
        val propsStr = props.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "ещё не опрошены" }
        "сборка $BUILD; файлы $TMP_DIR: $filesStr; свойства: $propsStr"
    }

    private fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).also { it.isDaemon = true }.start()
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
