package com.yegorkapomidorka.headswap.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * Одноразовое окно «Отказ от спецвозможностей» для тех, кто обновился до 2.6.0: службы спецвозможностей больше нет,
 * сервер ГУ включают вручную. Новички узнают это из онбординга, им окно не нужно.
 */
object UpgradeNotice {
    enum class Launch { NONE, ONBOARDING, NOTICE_30, NOTICE, NOTICE_40 }

    /**
     * Обычное открытие приложения: что показать, не больше одного окна за запуск. Сначала окно 3.0 ([Upgrade30Notice]),
     * окно 2.6.0 (если его ещё не видели) - при следующем запуске, окно 4.0 ([Upgrade40Notice]) - после них: старые
     * объявления не вытесняются новым. Флаг «показано» ставится в каждом случае (см. MainActivity).
     */
    fun onLaunch(onboardingDone: Boolean, v30Shown: Boolean, noticeShown: Boolean, v40Shown: Boolean): Launch = when {
        !onboardingDone -> Launch.ONBOARDING
        !v30Shown -> Launch.NOTICE_30
        !noticeShown -> Launch.NOTICE
        !v40Shown -> Launch.NOTICE_40
        else -> Launch.NONE
    }

    fun show(act: Activity) {
        AppLog.i("окно «Отказ от спецвозможностей»: показываю")
        val c = act
        val dialog = Dialog(act, R.style.Theme_Headswap_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content(c, dialog), FrameLayout.LayoutParams(MATCH, WRAP))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
        }
        dialog.show()
    }

    /** Поля вокруг панели: по бокам 16 dp, сверху и снизу 24 dp, чтобы на коротком экране панель не липла к краям. */
    private fun content(c: Context, dialog: Dialog): View {
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
            c.text(c.getString(R.string.upg_lead), 15f, 500, c.clr(R.color.onpric)).apply {
                background = c.shape(c.clr(R.color.pric), 16)
                pad(c, 14, 12, 14, 12)
                lineHeight = c.dp(20)
            },
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.upg_pros, R.color.ok, R.color.okc, R.drawable.ic_plus, listOf(R.string.upg_pro_1, R.string.upg_pro_2, R.string.upg_pro_3), false),
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.upg_cons, R.color.warn, R.color.warnc, R.drawable.ic_minus, listOf(R.string.upg_con_1, R.string.upg_con_2, R.string.upg_con_3), true),
            lp(MATCH, WRAP),
        )

        // Прокручивается всё содержимое, кнопка «Понятно» остаётся внизу панели, вне прокрутки.
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(scroll, lp(MATCH, 0, 1f))
        panel.addView(
            c.primaryButton(c.getString(R.string.upg_ok), 0, 52, 17f).apply { setOnClickListener { dialog.dismiss() } },
            lp(MATCH, c.dp(52)).margins(c, 0, 16, 0, 0),
        )

        return FrameLayout(c).apply {
            pad(c, 16, 24, 16, 24)
            addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun titleRow(c: Context): View = c.hbox(12).apply {
        addView(ImageView(c).apply { setImageResource(R.drawable.ic_a11y_badge) }, lp(c.dp(48), c.dp(48)))
        addView(
            c.text(c.getString(R.string.upg_title), 20f, 600).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
            },
            lp(0, WRAP, 1f),
        )
    }

    /** Блок «Плюсы» / «Минусы»; withNotif = под последним пунктом картинка-карточка уведомления. */
    private fun section(c: Context, head: Int, fg: Int, bg: Int, icon: Int, items: List<Int>, withNotif: Boolean): View {
        val box = c.vbox(6)
        box.addView(
            c.text(c.getString(head).uppercase(c.resources.configuration.locales[0]), 13f, 600, c.clr(fg)).apply {
                letterSpacing = 0.02f
            },
            lp(MATCH, WRAP),
        )
        items.forEachIndexed { i, res ->
            box.addView(item(c, c.getText(res), fg, bg, icon, withNotif && i == items.lastIndex), lp(MATCH, WRAP))
        }
        return box
    }

    private fun item(c: Context, text: CharSequence, fg: Int, bg: Int, icon: Int, notif: Boolean): View {
        val row = c.hbox(10).apply {
            addView(
                FrameLayout(c).apply {
                    background = c.oval(c.clr(bg))
                    addView(c.icon(icon, 12, c.clr(fg)), FrameLayout.LayoutParams(c.dp(12), c.dp(12), Gravity.CENTER))
                },
                lp(c.dp(20), c.dp(20)),
            )
            addView(c.text(text, 14f).apply { lineHeight = c.dp(19) }, lp(0, WRAP, 1f))
        }
        if (!notif) return row
        return c.vbox(8).apply {
            addView(row, lp(MATCH, WRAP))
            addView(notifCard(c), lp(MATCH, WRAP))
        }
    }

    /** Картинка: карточка уведомления «Android Auto Developer» в шторке. Только для вида, нажатий нет. */
    private fun notifCard(c: Context): View {
        val night = c.isNight
        val title = if (night) Color.WHITE else Color.parseColor("#1B1B1B")
        val sub = Color.parseColor(if (night) "#BDBDBD" else "#5F6368")
        val card = c.hbox(10).apply {
            background = c.shape(Color.parseColor(if (night) "#2A2A2A" else "#F1F3F4"), 18)
            elevation = c.dp(if (night) 3 else 2).toFloat()
            pad(c, 12, 0, 12, 0)
            addView(ImageView(c).apply { setImageResource(R.drawable.ic_aa_dev_notif) }, lp(c.dp(28), c.dp(28)))
            addView(
                c.vbox(1).apply {
                    addView(
                        c.hbox(8).apply {
                            addView(oneLine(c.text("Android Auto Developer", 13f, 500, title)), lp(0, WRAP, 1f))
                            addView(c.text(c.getString(R.string.upg_notif_when), 12f, 400, sub), lp(WRAP, WRAP))
                        },
                        lp(MATCH, WRAP),
                    )
                    addView(oneLine(c.text(c.getString(R.string.upg_notif_text), 12f, 400, sub)), lp(MATCH, WRAP))
                },
                lp(0, WRAP, 1f),
            )
        }
        // Запас под тень: иначе её обрежет прокрутка.
        return FrameLayout(c).apply {
            clipChildren = false
            pad(c, 3, 2, 3, 6)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(card, FrameLayout.LayoutParams(MATCH, c.dp(56)))
        }
    }

    private fun oneLine(t: TextView): TextView = t.apply {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }
}
