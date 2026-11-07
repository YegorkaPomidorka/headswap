package com.yegorkapomidorka.headswap.dex

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.yegorkapomidorka.headswap.AppLog
import java.io.File

/**
 * Диагностика (h699/h768, Android 17): при гашении экрана телефона наши активности на виртуальном дисплее уходят в паузу,
 * хотя дисплей в своей группе и под wake lock. Снимаем dumpsys от shell (UiAutomation активации) до и после SCREEN_OFF,
 * чтобы понять, кто усыпляет: sleep token WM или питание группы дисплеев. Только отфильтрованные строки, в лог с SLEEPDIAG.
 */
internal class SleepDiagProbe(private val context: Context, private val automation: UiAutomation) {
    private val thread = HandlerThread("headswap-sleepdiag").apply { start() }
    private val handler = Handler(thread.looper)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val what = intent.action?.substringAfterLast('.') ?: return
            // Сразу и с задержкой: токены сна и состояние группы выставляются не мгновенно.
            for (delay in longArrayOf(300, 2500)) handler.postDelayed({ snapshot("$what+${delay}ms") }, delay)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed({ snapshot("READY") }, 3000)
        AppLog.init(context) // активация может прийти раньше MainActivity, которая открывает лог
        AppLog.i("SLEEPDIAG пробник включён, DUMP=${context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED}", false)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        thread.quitSafely()
    }

    private fun snapshot(tag: String) {
        for (probe in PROBES) {
            val lines = runCatching { run(probe.command) }.getOrElse {
                AppLog.i("SLEEPDIAG $tag ${probe.name}: ${it.javaClass.simpleName}: ${it.message}", false)
                continue
            }
            val hits = lines.filter { line -> probe.keep.containsMatchIn(line) && !DROP.containsMatchIn(line) }
            AppLog.i("SLEEPDIAG $tag ${probe.name}: ${hits.size}/${lines.size} строк", false)
            for (line in hits.take(probe.limit)) AppLog.i("SLEEPDIAG $tag ${probe.name}| ${line.trim().take(300)}", false)
        }
    }

    /**
     * Не executeShellCommand: его shell-процесс (am instrument) умирает, когда юзер отключает ПК после активации, и вызов
     * молча отдаёт пустой поток (h782 sleep1: 0/0 строк). Дамп сервиса напрямую через Binder.dump под принятым DUMP.
     */
    private fun run(command: String): List<String> {
        val parts = command.split(' ')
        val binder = SERVICE_MANAGER.invoke(null, parts[0]) as? IBinder ?: error("сервис ${parts[0]} не найден")
        val file = File(context.cacheDir, "sleepdiag.txt")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE).use { binder.dump(it.fileDescriptor, parts.drop(1).toTypedArray()) }
        return file.readLines().also { file.delete() }
    }

    private class Probe(val name: String, val command: String, val keep: Regex, val limit: Int)

    companion object {
        const val PERMISSION = "android.permission.DUMP"
        private val SERVICE_MANAGER by lazy {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        }
        private val DROP = Regex("""(?i)intent=|cmp=|packageName=.*(google|samsung).*icon""")
        private val PROBES = listOf(
            Probe("atm", "activity activities",
                Regex("""(?i)display #|sleep|keyguard|occlud|resumedactivity|topresumed|mAwake|headswap"""), 80),
            Probe("power", "power",
                Regex("""(?i)wakefulness|powergroup|groupid|group \d|displaygroup|headswap|mIsPowered|mScreenOff|sleep|doze"""), 80),
            Probe("display", "display",
                Regex("""(?i)headswap|displaygroup|mState=|state=|mScreenState|powerstate|mDisplayOffloadSession|group"""), 80),
            Probe("window", "window displays",
                Regex("""(?i)display: mDisplayId|mDisplayId=|sleep|keyguard|mAwake|mScreenOn|headswap"""), 60),
        )
    }
}
