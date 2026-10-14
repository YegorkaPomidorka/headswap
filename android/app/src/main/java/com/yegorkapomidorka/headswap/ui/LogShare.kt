package com.yegorkapomidorka.headswap.ui

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import com.yegorkapomidorka.headswap.AdbLink
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Раздаёт файлы логов и отчётов другим приложениям (ACTION_SEND); без AndroidX FileProvider. Только чтение. */
class LogProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val ctx = context ?: return null
        return LogShare.dirs(ctx).map { File(it, name) }.firstOrNull { it.isFile }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".zip") == true) "application/zip" else "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

object LogShare {
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_LOGS = 5

    fun logDir(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    private fun reportDir(ctx: Context): File = File(ctx.cacheDir, "reports").also { it.mkdirs() }

    fun dirs(ctx: Context): List<File> = listOf(logDir(ctx), reportDir(ctx))

    /** Файлы headswap-*.log, новые первыми. */
    fun logFiles(ctx: Context): List<File> =
        logDir(ctx).listFiles { f -> f.name.startsWith("headswap-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Authority провайдера в манифесте: ${applicationId}.logs (пакет зависит от сборки). */
    fun authority(ctx: Context): String = ctx.packageName + ".logs"

    fun uriFor(ctx: Context, f: File): Uri = Uri.parse("content://${authority(ctx)}/${Uri.encode(f.name)}")

    private fun shareFile(ctx: Context, f: File, mime: String, subject: String) {
        val uri = uriFor(ctx, f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть меню «Поделиться»", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Текущий файл headswap-*.log как есть. */
    fun shareLog(ctx: Context) {
        val f = AppLog.file?.takeIf { it.isFile } ?: logFiles(ctx).firstOrNull()
        if (f == null) {
            Toast.makeText(ctx, ctx.getString(R.string.share_no_log), Toast.LENGTH_SHORT).show()
            return
        }
        shareFile(ctx, f, "text/plain", ctx.getString(R.string.share_subject_log, f.name))
    }

    /** Архив: логи + сведения об устройстве и настройках. Без контактов и геопозиции. */
    fun shareReport(ctx: Context) {
        val zip = try {
            buildZip(ctx)
        } catch (e: Exception) {
            AppLog.e("не собрать архив отчёта", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_report_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        shareFile(ctx, zip, "application/zip", ctx.getString(R.string.share_subject_report))
    }

    /** Архив в «Загрузки» (MediaStore, без разрешений); при неудаче - меню «Поделиться». */
    fun saveZip(ctx: Context) {
        val zip = try {
            buildZip(ctx)
        } catch (e: Exception) {
            AppLog.e("не собрать архив", e)
            Toast.makeText(ctx, ctx.getString(R.string.share_zip_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val dst = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore не создал файл")
            ctx.contentResolver.openOutputStream(dst)!!.use { out -> zip.inputStream().use { it.copyTo(out) } }
            AppLog.i("архив сохранён в Загрузки: ${zip.name}")
            Toast.makeText(ctx, ctx.getString(R.string.share_saved, zip.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.e("не сохранить в Загрузки, делюсь файлом", e)
            shareFile(ctx, zip, "application/zip", ctx.getString(R.string.share_subject_archive))
        }
    }

    private fun buildZip(ctx: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = reportDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "headswap-$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(deviceInfo(ctx).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for (f in logFiles(ctx).take(MAX_LOGS)) {
                z.putNextEntry(ZipEntry("logs/${f.name}"))
                copyTail(f, z)
                z.closeEntry()
            }
        }
        return out
    }

    /** Файл целиком или только последние MAX_FILE_BYTES (hexdump'ы раздувают лог). */
    private fun copyTail(f: File, to: java.io.OutputStream) {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len > MAX_FILE_BYTES) {
                raf.seek(len - MAX_FILE_BYTES)
                to.write("[... начало обрезано, показаны последние ${MAX_FILE_BYTES / 1024} КиБ ...]\n".toByteArray())
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
            }
        }
    }

    fun deviceInfo(ctx: Context): String {
        val aa = AaPrefs(ctx)
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        return buildString {
            appendLine("Headswap $ver")
            appendLine("пакет: ${ctx.packageName} $ver")
            appendLine("Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
            appendLine("Аптайм: ${SystemClock.elapsedRealtime() / 1000} с")
            appendLine()
            appendLine("Настройки моста")
            appendLine("  видео: ${if (aa.transcode) "перекодирование" else "passthrough"}")
            appendLine("  разрешение видео: выбрано ${aa.videoMode.name}, в сессии: ${CarLifeState.videoMode?.describe() ?: "сессии не было"}")
            appendLine("  частота кадров: ${aa.fpsSetting.name}")
            appendLine("  звук по USB: ${aa.usbAudio}")
            appendLine("  сертификат: ${aa.certSet}")
            appendLine("  автозапуск сервера ГУ: ${aa.autoServer}")
            appendLine("  микрофон: ${aa.micMode.label}, MODULE_STATUS VR: ${aa.micVrStatus}")
            appendLine("  метаданные: музыка ${aa.metaMusic}, навигация ${aa.metaNavi}, поле 6 NAVI_NEXT_TURN_INFO: ${aa.naviField6}, прогресс в ${if (aa.progressSeconds) "с" else "мс"}")
            appendLine()
            appendLine("Данные ГУ и состояние")
            appendLine(CarLifeState.summary())
            if (BuildConfig.CARLIFE_ADB) appendLine("ADB: ${AdbLink.describe()}")
            appendLine(AaState.summary())
            appendLine(AutoServer.summary(ctx))
        }
    }

    /** Очистить: удалить старые файлы headswap-*.log (текущий оставить). */
    fun deleteOldLogs(ctx: Context): Int {
        val cur = AppLog.file
        var n = 0
        for (f in logFiles(ctx)) if (f != cur && f.delete()) n++
        return n
    }

    fun sizeText(ctx: Context, f: File?): String {
        val b = f?.length() ?: return "—"
        return if (b >= 1024 * 1024) ctx.getString(R.string.share_size_mib, "%.1f".format(Locale.US, b / 1048576.0))
        else ctx.getString(R.string.share_size_kib, b / 1024)
    }
}
