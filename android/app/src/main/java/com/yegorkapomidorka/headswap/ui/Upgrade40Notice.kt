package com.yegorkapomidorka.headswap.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R

/**
 * Одноразовое окно «Свой лаунчер на магнитоле» для тех, кто обновился до 4.0: новый режим, Android Auto по умолчанию.
 * Новички выбирают режим в онбординге, им окно не нужно. Когда показывать - см. [UpgradeNotice.onLaunch].
 */
object Upgrade40Notice {
    fun show(act: MainActivity) {
        AppLog.i("окно «Свой лаунчер на магнитоле» (4.0): показываю")
        val dialog = Dialog(act, R.style.Theme_Headswap_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content(act, dialog), FrameLayout.LayoutParams(MATCH, WRAP))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
        }
        dialog.show()
    }

    /** Поля вокруг панели: по бокам 16 dp, сверху и снизу 24 dp, чтобы на коротком экране панель не липла к краям. */
    private fun content(act: MainActivity, dialog: Dialog): View {
        val c: Context = act
        val panel = object : LinearLayout(c) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = c.dp(480)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(
                    if (w > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthMeasureSpec,
                    heightMeasureSpec,
                )
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = c.shape(c.clr(R.color.surf), 28)
            pad(c, 20, 20, 20, 12)
        }

        val body = c.vbox(12)
        body.addView(titleRow(c))
        body.addView(
            c.text(c.getString(R.string.u40_lead), 15f, 500, c.clr(R.color.onpric)).apply {
                background = c.shape(c.clr(R.color.pric), 16)
                pad(c, 14, 12, 14, 12)
                lineHeight = c.dp(20)
            },
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u40_pros, R.color.ok, R.color.okc, R.drawable.ic_plus, listOf(R.string.u40_pro_1, R.string.u40_pro_2, R.string.u40_pro_3)),
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u40_needs, R.color.warn, R.color.warnc, R.drawable.ic_minus, listOf(R.string.u40_need_1, R.string.u40_need_2, R.string.u40_need_3)),
            lp(MATCH, WRAP),
        )
        body.addView(whereBlock(c), lp(MATCH, WRAP))

        // Прокручивается всё содержимое, кнопки остаются внизу панели, вне прокрутки.
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(scroll, lp(MATCH, 0, 1f))
        panel.addView(
            c.primaryButton(c.getString(R.string.u40_ok), 0, 52, 17f).apply { setOnClickListener { dialog.dismiss() } },
            lp(MATCH, c.dp(52)).margins(c, 0, 16, 0, 0),
        )
        panel.addView(
            c.linkButton(c.getString(R.string.u40_try), 44, 15f).apply {
                setOnClickListener {
                    dialog.dismiss()
                    // то же, что `--es cmd onboarding --es step mode`: онбординг сразу с выбора режима
                    act.showOnboarding(rerun = true, startAt = StepId.MODE)
                }
            },
            lp(MATCH, c.dp(44)).margins(c, 0, 4, 0, 0),
        )

        return FrameLayout(c).apply {
            pad(c, 16, 24, 16, 24)
            addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        }
    }

    /** Плашка «4.0» и заголовок. */
    private fun titleRow(c: Context): View = c.hbox(12).apply {
        addView(
            FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pri), 12)
                addView(
                    c.text("4.0", 20f, 700, c.clr(R.color.onpri)).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER),
                )
            },
            lp(c.dp(48), c.dp(48)),
        )
        addView(
            c.text(c.getString(R.string.u40_title), 20f, 600).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
            },
            lp(0, WRAP, 1f),
        )
    }

    /** Блок с заголовком секции и пунктами в кружках с иконкой; цвет заголовка тот же, что у кружков. */
    private fun section(c: Context, head: Int, fg: Int, bg: Int, icon: Int, items: List<Int>): View {
        val box = c.vbox(6)
        box.addView(heading(c, head, fg), lp(MATCH, WRAP))
        for (res in items) box.addView(item(c, c.getText(res), fg, bg, icon), lp(MATCH, WRAP))
        return box
    }

    private fun heading(c: Context, res: Int, color: Int) =
        c.text(c.getString(res).uppercase(c.resources.configuration.locales[0]), 13f, 600, c.clr(color)).apply {
            letterSpacing = 0.02f
        }

    private fun item(c: Context, text: CharSequence, fg: Int, bg: Int, icon: Int): View = c.hbox(10).apply {
        addView(
            FrameLayout(c).apply {
                background = c.oval(c.clr(bg))
                addView(c.icon(icon, 12, c.clr(fg)), FrameLayout.LayoutParams(c.dp(12), c.dp(12), Gravity.CENTER))
            },
            lp(c.dp(20), c.dp(20)),
        )
        addView(c.text(text, 14f).apply { lineHeight = c.dp(19) }, lp(0, WRAP, 1f))
    }

    /** Путь в настройках и картинка переключателя режима. */
    private fun whereBlock(c: Context): View {
        val box = c.vbox(6)
        box.addView(c.text(c.getString(R.string.u40_where), 12f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        box.addView(modeSwitchPicture(c), lp(MATCH, c.dp(44)))
        return box
    }

    /** Картинка: переключатель «Режим» из настроек (Segmented) с выбранным Android Auto. Только для вида, нажатий нет. */
    private fun modeSwitchPicture(c: Context): View = c.hbox().apply {
        background = c.shape(0, 22, c.clr(R.color.line), 1.5f)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val half = { label: String, selected: Boolean ->
            c.text(label, 14f, 600, c.clr(if (selected) R.color.onpric else R.color.on)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                pad(c, 6, 0, 6, 0)
                if (selected) background = c.shape(c.clr(R.color.pric), 0)
            }
        }
        addView(half("✓ Android Auto", true), lp(0, MATCH, 1f))
        addView(half(c.getString(R.string.bm_own_launcher), false), lp(0, MATCH, 1f))
    }
}
