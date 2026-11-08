package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.R

/** Мелкий набор помощников для сборки View в коде: размеры в dp, цвета из ресурсов (светлая/тёмная палитра), Golos Text. */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.clr(id: Int): Int = getColor(AppTheme.res(id))

fun Context.sans(weight: Int = 400): Typeface = Typeface.create(resources.getFont(R.font.golos), weight, false)

fun Context.mono(): Typeface = resources.getFont(R.font.jetbrains_mono)

fun Context.shape(fill: Int, radiusDp: Number, stroke: Int = 0, strokeDp: Number = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

fun Context.oval(fill: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

/** Фон с эффектом нажатия; radiusDp задаёт и скругление маски. */
fun Context.rippleBg(content: Drawable?, radiusDp: Number): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = dp(radiusDp).toFloat()
    }
    return RippleDrawable(ColorStateList.valueOf(clr(R.color.ripple)), content, mask)
}

fun Context.text(
    s: CharSequence,
    sp: Float,
    weight: Int = 400,
    color: Int = clr(R.color.on),
    monospace: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (monospace) mono() else sans(weight)
    includeFontPadding = false
}

fun Context.icon(res: Int, sizeDp: Number, tint: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.vbox(gapDp: Number = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp.toInt() > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setSize(1, dp(gapDp))
            setColor(Color.TRANSPARENT)
        }
    }
}

fun Context.hbox(gapDp: Number = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp.toInt() > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setSize(dp(gapDp), 1)
            setColor(Color.TRANSPARENT)
        }
    }
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.lpDp(w: Int, hDp: Number, weight: Float = 0f) = LinearLayout.LayoutParams(w, dp(hDp), weight)

/** Отступы относительные: [l] - начало (start), [r] - конец (end); в RTL стороны меняются местами. */
fun View.pad(ctx: Context, l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0) =
    setPaddingRelative(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))

/** Поля относительные: [l] - начало (start), [r] - конец (end). */
fun LinearLayout.LayoutParams.margins(ctx: Context, l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0) = apply {
    marginStart = ctx.dp(l)
    topMargin = ctx.dp(t)
    marginEnd = ctx.dp(r)
    bottomMargin = ctx.dp(b)
}

/** Приложение сейчас в RTL-локали (арабский); для поворотов и позиций, которые считаются в коде. */
fun Context.isRtl(): Boolean = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

/**
 * Поворот шеврона (ic_chevron, autoMirrored) на 90° «вниз» / -90° «вверх». В RTL вектор зеркален по X,
 * поэтому знак угла обратный - иначе шеврон смотрит наоборот.
 */
fun Context.chevronRotation(open: Boolean): Float = (if (open) -90f else 90f) * (if (isRtl()) -1f else 1f)

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(clr(R.color.surf2))
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
}

/** Заголовок группы настроек (14sp, акцентный). */
fun Context.sectionTitle(s: String): TextView = text(s, 14f, 600, clr(R.color.acc)).apply {
    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).margins(this@sectionTitle, 8, 12, 8, 4)
}

/** Белая карточка со скруглением. */
fun Context.card(radiusDp: Number = 20, fill: Int = clr(R.color.surf)): LinearLayout = vbox().apply {
    background = shape(fill, radiusDp)
    clipToOutline = true
}

fun Context.styledSwitch(): Switch = Switch(this).apply {
    thumbDrawable = getDrawable(AppTheme.res(R.drawable.switch_thumb))
    trackDrawable = getDrawable(AppTheme.res(R.drawable.switch_track))
    showText = false
    switchMinWidth = dp(52)
    isClickable = false
    isFocusable = false
}

/** Строка-переключатель: заголовок, подпись, Switch справа; нажатие на всю строку. */
class ToggleRow(
    ctx: Context,
    title: String,
    sub: String?,
    checked: Boolean,
    minHeightDp: Int = 72,
    standalone: Boolean = true,
    titleSp: Float = 17f,
    subSp: Float = 14f,
    val onChange: (Boolean) -> Unit,
) {
    val sw = ctx.styledSwitch().apply { isChecked = checked }
    val subView: TextView? = sub?.let { ctx.text(it, subSp, 400, ctx.clr(R.color.on2)) }
    val titleView = ctx.text(title, titleSp, 500)
    val view: LinearLayout = ctx.hbox(12).apply {
        minimumHeight = ctx.dp(minHeightDp)
        pad(ctx, 16, 12, 16, 12)
        val col = ctx.vbox(2)
        col.addView(titleView)
        subView?.let { col.addView(it) }
        addView(col, lp(0, WRAP, 1f))
        addView(sw, lp(WRAP, WRAP))
        background = ctx.rippleBg(if (standalone) ctx.shape(ctx.clr(R.color.surf), 20) else null, if (standalone) 20 else 0)
        isClickable = true
        setOnClickListener { sw.toggle(); onChange(sw.isChecked) }
    }
    fun setChecked(v: Boolean) {
        if (sw.isChecked != v) sw.isChecked = v
    }

    /** Неактивная строка затемнена и не нажимается; значение переключателя не трогается. */
    fun setEnabled(on: Boolean) {
        view.isEnabled = on
        view.isClickable = on
        sw.isEnabled = on
        view.alpha = if (on) 1f else 0.45f
    }
}

/** Строка с радио-кнопкой: выбранная подсвечена pric. */
class RadioRow(val ctx: Context, title: String, sub: String, val onPick: () -> Unit) {
    private val radio = RadioButton(ctx).apply {
        buttonTintList = ctx.getColorStateList(AppTheme.res(R.color.radio_tint))
        isClickable = false
        isFocusable = false
    }
    /** Край по направлению экрана, не по тексту: иначе «العربية» в русском списке языков уезжает вправо. */
    private val titleView = ctx.text(title, 17f, 500).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
    /** Пустой подзаголовок скрыт: иначе пустая строка сдвигает заголовок вверх (выбор языка). */
    private val subView = ctx.text(sub, 14f, 400, ctx.clr(R.color.on2)).apply {
        visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
    }
    val view: LinearLayout = ctx.hbox(10).apply {
        minimumHeight = ctx.dp(if (sub.isBlank()) 56 else 72)
        pad(ctx, 12, 12, 16, 12)
        addView(radio, lp(WRAP, WRAP))
        val col = ctx.vbox(2)
        col.addView(titleView)
        col.addView(subView)
        addView(col, lp(0, WRAP, 1f))
        isClickable = true
        setOnClickListener { onPick() }
    }

    fun setSub(text: String) {
        subView.text = text
        subView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setSelected(sel: Boolean) {
        radio.isChecked = sel
        view.background = ctx.rippleBg(ctx.shape(ctx.clr(if (sel) R.color.pric else R.color.surf), 20), 20)
        titleView.setTextColor(ctx.clr(if (sel) R.color.onpric else R.color.on))
        titleView.typeface = ctx.sans(if (sel) 600 else 500)
        subView.setTextColor(ctx.clr(if (sel) R.color.onpric else R.color.on2))
    }
}

/** Основная кнопка: pill 64dp, заливка pri. */
fun Context.primaryButton(label: String, iconRes: Int = 0, heightDp: Int = 64, sp: Float = 18f): LinearLayout =
    pill(label, iconRes, heightDp, sp, clr(R.color.pri), clr(R.color.onpri), 0)

/** Кнопка с контуром. */
fun Context.outlineButton(label: String, iconRes: Int = 0, heightDp: Int = 64, sp: Float = 18f): LinearLayout =
    pill(label, iconRes, heightDp, sp, 0, clr(R.color.on), clr(R.color.line))

/** Тональная кнопка (surf2). */
fun Context.tonalButton(label: String, heightDp: Int = 52, sp: Float = 15f): LinearLayout =
    pill(label, 0, heightDp, sp, clr(R.color.surf2), clr(R.color.on), 0)

/** Тональная кнопка-контейнер (pric/onpric): вторая по важности кнопка под основной. */
fun Context.containerButton(label: String, heightDp: Int = 56, sp: Float = 17f): LinearLayout =
    pill(label, 0, heightDp, sp, clr(R.color.pric), clr(R.color.onpric), 0)

/** Текстовая кнопка (акцент). */
fun Context.linkButton(label: String, heightDp: Int = 48, sp: Float = 16f): LinearLayout =
    pill(label, 0, heightDp, sp, 0, clr(R.color.acc), 0)

private fun Context.pill(label: String, iconRes: Int, heightDp: Int, sp: Float, fill: Int, fg: Int, stroke: Int): LinearLayout =
    hbox(10).apply {
        gravity = Gravity.CENTER
        val r = heightDp / 2
        background = rippleBg(
            if (fill != 0 || stroke != 0) shape(fill, r, stroke, 1.5f) else null, r,
        )
        isClickable = true
        isFocusable = true
        pad(this@pill, 20, 0, 20, 0)
        if (iconRes != 0) addView(icon(iconRes, 22, fg))
        addView(text(label, sp, 600, fg).apply { gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    }

/** Круглая кнопка-иконка 48dp (шапка). */
fun Context.iconButton(res: Int, tint: Int, desc: String): FrameLayout = FrameLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    background = rippleBg(null, 24)
    isClickable = true
    isFocusable = true
    contentDescription = desc
    addView(icon(res, 24, tint), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
}
