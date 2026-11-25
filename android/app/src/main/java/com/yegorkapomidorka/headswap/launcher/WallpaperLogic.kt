package com.yegorkapomidorka.headswap.launcher

/** Обои стола в стиле «Стекло»: none - как раньше (градиент палитры), четыре встроенные картинки (две праздничные - наборов «Ёлка» и «Новогодний») и своё фото из файла. */
enum class WallpaperId(val key: String) {
    NONE("none"), HEADSWAP("headswap"), MOUNTAINS("mountains"), TREE("tree"), WINTER_ROAD("winter_road"), CUSTOM("custom");

    companion object {
        /** Незнакомое или пустое значение - без обоев. */
        fun restore(key: String?): WallpaperId = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** Чистая часть обоев: границы настроек, размытие в пикселях, подбор масштаба при чтении картинок. */
object WallpaperLogic {
    const val BLUR_DEFAULT = 35
    const val DIM_DEFAULT = 30
    /** Файл своего фото в filesDir; его пишет телефон ([WallpaperImport]), читает лаунчер. */
    const val CUSTOM_FILE = "launcher_wallpaper.jpg"
    /** Ширина своего фото после импорта; встроенные обои нарисованы в 2560. */
    const val IMPORT_MAX_W = 2560
    const val IMPORT_MAX_H = 4096

    fun clamp(v: Int): Int = v.coerceIn(0, 100)

    /** Размытие 0..100 в пиксели экрана: 100 - 4,5% короткой стороны (на 720p около 32 px). */
    fun blurPx(blur: Int, minSidePx: Int): Float = clamp(blur) / 100f * 0.045f * minSidePx

    /** Крупнее на столько, чтобы края размытой картинки не светились (по 2 радиуса на сторону). */
    fun overscan(blurPx: Float, minSidePx: Int): Float = 1f + blurPx * 2f / minSidePx.coerceAtLeast(1)

    /** Затемнение 0..100 в альфу чёрного слоя: 100 - 85%, чтобы картинка не пропадала совсем. */
    fun dimAlpha(dim: Int): Float = clamp(dim) / 100f * 0.85f

    /**
     * Степень двойки для BitmapFactory.inSampleSize: самая крупная, при которой картинка всё ещё не меньше
     * целевой по обеим сторонам (под Crop нужно покрыть экран целиком).
     */
    fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= targetW && srcH / (s * 2) >= targetH) s *= 2
        return s
    }

    /** Во сколько раз уменьшить фото после чтения: не шире [IMPORT_MAX_W], не выше [IMPORT_MAX_H]; увеличивать не нужно. */
    fun importScale(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 1f
        return minOf(1f, IMPORT_MAX_W.toFloat() / w, IMPORT_MAX_H.toFloat() / h)
    }
}
