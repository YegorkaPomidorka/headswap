package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Чтение картинки обоев под нужный размер; небольшой кэш, чтобы рекомпозиция и миниатюры не декодировали заново. */
internal object WallpaperLoader {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean = size > 5
    }

    private fun resId(id: WallpaperId): Int? = when (id) {
        WallpaperId.HEADSWAP -> R.drawable.wallpaper_headswap
        WallpaperId.MOUNTAINS -> R.drawable.wallpaper_mountains
        WallpaperId.TREE -> R.drawable.wallpaper_tree
        WallpaperId.WINTER_ROAD -> R.drawable.wallpaper_winter_road
        else -> null
    }

    /** Есть ли что показывать: встроенные - всегда, своё - если файл на месте. */
    fun available(ctx: Context, id: WallpaperId): Boolean = when (id) {
        WallpaperId.NONE -> false
        WallpaperId.CUSTOM -> WallpaperImport.file(ctx).exists()
        else -> true
    }

    /** Картинка не меньше [w]x[h] (под Crop покрывает экран); null - нет обоев или файл не читается. Не из UI-потока. */
    fun load(ctx: Context, id: WallpaperId, ver: Int, w: Int, h: Int): ImageBitmap? {
        if (!available(ctx, id)) return null
        val key = "${id.key}:$ver:$w:$h"
        synchronized(cache) { cache[key]?.let { return it } }
        val res = ctx.resources
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val rid = resId(id)
            val file = WallpaperImport.file(ctx)
            if (rid != null) BitmapFactory.decodeResource(res, rid, bounds) else BitmapFactory.decodeFile(file.path, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = WallpaperLogic.sampleSize(bounds.outWidth, bounds.outHeight, w, h) }
            if (rid != null) BitmapFactory.decodeResource(res, rid, opts) else BitmapFactory.decodeFile(file.path, opts)
        }.getOrNull()?.asImageBitmap() ?: return null
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }
}

/**
 * Картинка обоев под размер [w]x[h] или null: стиль не «Стекло», обоев нет, файла нет, картинка ещё читается. Пока грузится
 * новая, держится предыдущая. От неё же зависит, красить ли область справа от рейки в bgSolid ([LauncherRoot]).
 */
@Composable
internal fun rememberWallpaper(active: Boolean, id: WallpaperId, ver: Int, w: Int, h: Int, ctx: Context): ImageBitmap? {
    val loaded by produceState<ImageBitmap?>(null, active, id, ver, w, h) {
        value = if (active && id != WallpaperId.NONE) withContext(Dispatchers.IO) { WallpaperLoader.load(ctx, id, ver, w, h) } else null
    }
    return loaded
}

/**
 * Обои на весь стол, первый слой корня: картинка по Crop, размытие по слою картинки (не фон под стеклом, ADR 0052) и
 * чёрное затемнение. Картинка чуть крупнее экрана, чтобы края размытия не светились. Размытие - от короткой стороны
 * [minSidePx]: в альбоме это высота, в портрете ширина, и ползунок мажет одинаково (ADR 0054).
 */
@Composable
internal fun WallpaperLayer(image: ImageBitmap, blur: Int, dim: Int, minSidePx: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val blurPx = WallpaperLogic.blurPx(blur, minSidePx)
    val scale = WallpaperLogic.overscan(blurPx, minSidePx)
    Box(modifier.fillMaxSize()) {
        Image(image, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (Build.VERSION.SDK_INT >= 31 && blurPx >= 0.5f) Modifier.blur(with(density) { blurPx.toDp() }) else Modifier),
            contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = WallpaperLogic.dimAlpha(dim))))
    }
}
