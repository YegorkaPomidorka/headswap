package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.yegorkapomidorka.headswap.AppLog
import java.io.File

/**
 * Своё фото для обоев: выбирается на телефоне (на виртуальном дисплее ГУ системный выбор файла не открывается), здесь
 * читается, уменьшается до [WallpaperLogic.IMPORT_MAX_W] и пишется в filesDir. Вызывать не из UI-потока.
 */
object WallpaperImport {
    fun file(ctx: Context): File = File(ctx.filesDir, WallpaperLogic.CUSTOM_FILE)

    fun run(ctx: Context, uri: Uri): Boolean = try {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("не картинка")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = WallpaperLogic.sampleSize(bounds.outWidth, bounds.outHeight, WallpaperLogic.IMPORT_MAX_W, 1)
        }
        val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("не прочиталась")
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val turned = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val k = WallpaperLogic.importScale(turned.width, turned.height)
        val out = if (k < 1f) Bitmap.createScaledBitmap(turned, (turned.width * k).toInt().coerceAtLeast(1), (turned.height * k).toInt().coerceAtLeast(1), true) else turned
        val target = file(ctx)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) error("не записалась") }
        AppLog.i("LAUNCHER обои: своё фото ${out.width}x${out.height}, ${target.length() / 1024} КБ", false)
        true
    } catch (e: Throwable) {
        AppLog.i("LAUNCHER обои: своё фото не взялось: ${e.javaClass.simpleName}: ${e.message}", false)
        false
    }
}
