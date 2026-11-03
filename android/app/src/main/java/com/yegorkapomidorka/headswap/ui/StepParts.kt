package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.yegorkapomidorka.headswap.R

/** Части шага «что где нажать» (Onboarding.dc.html): их собирают шаги онбординга и блок активации в «Режиме». */

/** Карточка-иллюстрация шага: серая плашка с макетом экрана внутри. */
fun Context.stepMockCard(mock: View, compact: Boolean): FrameLayout = FrameLayout(this).apply {
    background = shape(clr(R.color.surf2), 28)
    minimumHeight = dp(if (compact) 0 else 190)
    pad(this@stepMockCard, 20, 20, 20, 20)
    addView(mock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
}

/** Пронумерованный список: кружок pri с цифрой и текст шага. */
fun Context.numberedList(items: List<String>): View {
    val c = this
    return vbox(12).apply {
        items.forEachIndexed { i, t ->
            addView(
                hbox(12).apply {
                    gravity = Gravity.TOP
                    addView(
                        FrameLayout(c).apply {
                            background = oval(clr(R.color.pri))
                            addView(text("${i + 1}", 14f, 700, clr(R.color.onpri)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                        },
                        lp(dp(26), dp(26)),
                    )
                    addView(text(t, 17f, 400).apply { lineHeight = dp(24) }, lp(0, WRAP, 1f))
                },
                lp(MATCH, WRAP),
            )
        }
    }
}

/** Пояснение под списком: зачем нужен шаг. */
fun Context.stepHint(body: String): TextView = text(body, 14f, 400, clr(R.color.onpric)).apply {
    lineHeight = dp(20)
    background = shape(clr(R.color.pric), 16)
    pad(this@stepHint, 14, 12, 14, 12)
}

/** Зелёная плашка «шаг выполнен». */
fun Context.doneChip(label: String): LinearLayout = hbox(6).apply {
    background = shape(clr(R.color.okc), 10)
    pad(this@doneChip, 10, 4, 12, 4)
    addView(icon(R.drawable.ic_check, 14, clr(R.color.ok)))
    addView(text(label, 13f, 600, clr(R.color.ok)))
}
