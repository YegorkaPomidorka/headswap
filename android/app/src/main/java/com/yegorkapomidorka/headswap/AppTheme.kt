package com.yegorkapomidorka.headswap

import android.content.Context
import android.content.res.Configuration
import com.yegorkapomidorka.headswap.ui.UiPrefs

/**
 * Тема интерфейса: «system» (по умолчанию, следует системе), «light», «medium» (нейтральная тёмно-серая), «dark».
 * Выбор хранится в prefs "ui" (UiPrefs.theme). MainActivity оборачивает контекст через [wrap] в attachBaseContext:
 * light форсирует uiMode night=NO, medium и dark - night=YES (medium потом подменяет цвета на *_mid через [res]).
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val MEDIUM = "medium"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, MEDIUM, DARK)

    /** Включена «средняя»: Context.clr() и ресурсные ссылки идут через [res]. Выставляется в [wrap]. */
    @Volatile
    var medium = false
        private set

    fun get(ctx: Context): String = UiPrefs(ctx).theme.let { if (it in MODES) it else SYSTEM }

    fun set(ctx: Context, mode: String) {
        UiPrefs(ctx).theme = if (mode in MODES) mode else SYSTEM
    }

    /** Контекст с форсированным night-режимом; локаль из уже обёрнутого [base] сохраняется (копия его конфигурации). */
    fun wrap(base: Context): Context {
        val mode = get(base)
        medium = mode == MEDIUM
        val night = when (mode) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            MEDIUM, DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    /** Ресурс для текущей темы: в «средней» вариант *_mid, иначе тот же id. Явная карта, не getIdentifier (его вырежет shrinkResources). */
    fun res(id: Int): Int = if (medium) MID[id] ?: id else id

    private val MID: Map<Int, Int> = mapOf(
        R.color.bg to R.color.bg_mid,
        R.color.surf to R.color.surf_mid,
        R.color.surf2 to R.color.surf2_mid,
        R.color.on to R.color.on_mid,
        R.color.on2 to R.color.on2_mid,
        R.color.line to R.color.line_mid,
        R.color.pri to R.color.pri_mid,
        R.color.onpri to R.color.onpri_mid,
        R.color.acc to R.color.acc_mid,
        R.color.pric to R.color.pric_mid,
        R.color.onpric to R.color.onpric_mid,
        R.color.ok to R.color.ok_mid,
        R.color.okc to R.color.okc_mid,
        R.color.onok to R.color.onok_mid,
        R.color.warn to R.color.warn_mid,
        R.color.warnc to R.color.warnc_mid,
        R.color.onwarnc to R.color.onwarnc_mid,
        R.color.err to R.color.err_mid,
        R.color.errc to R.color.errc_mid,
        R.color.onerr to R.color.onerr_mid,
        R.color.ripple to R.color.ripple_mid,
        R.color.radio_tint to R.color.radio_tint_mid,
        R.drawable.switch_thumb to R.drawable.switch_thumb_mid,
        R.drawable.switch_track to R.drawable.switch_track_mid,
        R.drawable.ic_ring_active to R.drawable.ic_ring_active_mid,
    )
}
