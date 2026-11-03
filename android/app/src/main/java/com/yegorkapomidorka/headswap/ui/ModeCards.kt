package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.R

/** Части шага «Выберите режим» (OnboardingMode.dc.html). */

/**
 * Карточка-радио режима. Выбранная: фон pric, обводка 2dp pri, иконка на pri. [chips] - плашки под описанием
 * (названия лаунчеров), у Android Auto их нет.
 */
fun Context.modeCard(iconRes: Int, title: String, body: String, chips: List<String>, selected: Boolean, onPick: () -> Unit): View {
    val c = this
    return hbox(14).apply {
        gravity = Gravity.TOP
        pad(c, 16, 16, 16, 16)
        background = rippleBg(shape(clr(if (selected) R.color.pric else R.color.surf), 24, clr(if (selected) R.color.pri else R.color.surf2), 2), 24)
        isClickable = true
        setOnClickListener { onPick() }
        addView(
            FrameLayout(c).apply {
                background = shape(clr(if (selected) R.color.pri else R.color.surf2), 14)
                addView(icon(iconRes, 24, clr(if (selected) R.color.onpri else R.color.on2)), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            },
            lp(dp(44), dp(44)),
        )
        addView(
            vbox(6).apply {
                addView(text(title, 18f, 600, clr(if (selected) R.color.onpric else R.color.on)))
                addView(text(body, 14f, 400, clr(if (selected) R.color.onpric else R.color.on2)).apply { lineHeight = dp(20) })
                if (chips.isNotEmpty()) {
                    addView(
                        hbox(8).apply {
                            chips.forEach { label ->
                                addView(
                                    text(label, 12f, 600, clr(if (selected) R.color.onpri else R.color.on2)).apply {
                                        background = shape(clr(if (selected) R.color.pri else R.color.surf2), 10)
                                        pad(c, 10, 4, 10, 4)
                                    },
                                )
                            }
                        },
                        lp(WRAP, WRAP).margins(c, 0, 2, 0, 0),
                    )
                }
            },
            lp(0, WRAP, 1f),
        )
        addView(
            RadioButton(c).apply {
                buttonTintList = getColorStateList(AppTheme.res(R.color.radio_tint))
                isChecked = selected
                isClickable = false
                isFocusable = false
            },
            lp(WRAP, WRAP),
        )
    }
}

/** Блок «Внимание» (warnc / onwarnc) со значком-треугольником. */
fun Context.modeWarning(title: String, body: String, risk: String): View {
    val c = this
    return hbox(12).apply {
        gravity = Gravity.TOP
        background = shape(clr(R.color.warnc), 20)
        pad(c, 16, 14, 16, 14)
        addView(icon(R.drawable.ic_alert, 24, clr(R.color.onwarnc)))
        addView(
            vbox(4).apply {
                addView(text(title, 16f, 700, clr(R.color.onwarnc)))
                addView(text(body, 14f, 400, clr(R.color.onwarnc)).apply { lineHeight = dp(20) })
                addView(text(risk, 14f, 600, clr(R.color.onwarnc)).apply { lineHeight = dp(20) })
            },
            lp(0, WRAP, 1f),
        )
    }
}
