package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.R

/**
 * Иллюстрации-схемы для шагов онбординга: «что где нажать» (Onboarding.dc.html). Только рисунок, ничего не нажимается.
 * Цвета из темы приложения; синий системного UI Android (переключатели, галочки) - литералами, как в макете.
 * «Размытие» - полупрозрачные полоски цвета line, на API 31+ ещё и RenderEffect.
 */
object Mocks {
    private val SYS_BLUE = Color.parseColor("#3B7BEA")
    private val SYS_BUTTON = Color.parseColor("#2F6FD8")
    private val AA_CHECK = Color.parseColor("#1A73E8")
    private val WHITE = Color.WHITE

    fun build(ctx: Context, id: StepId): View = when (id) {
        StepId.AA_DEV -> ctx.vbox(10).apply {
            addView(
                ctx.aaWindow(
                    title = ctx.getString(R.string.mock_aa_title), row = ctx.getString(R.string.mock_aa_version),
                    chrome = true, tap = true, check = false, compact = false,
                ),
                lp(MATCH, WRAP),
            )
        }
        StepId.AA_UNKNOWN -> ctx.vbox(10).apply {
            addView(
                ctx.aaWindow(
                    title = ctx.getString(R.string.mock_aadev_title), row = ctx.getString(R.string.mock_aadev_unknown),
                    chrome = false, tap = false, check = true, compact = true,
                ),
                lp(MATCH, WRAP),
            )
        }
        StepId.AUTOSTART -> ctx.vbox(10).apply { addView(ctx.menuWindow(), lp(MATCH, ctx.dp(232))) }
        StepId.NOTIF -> ctx.vbox(10).apply { addView(ctx.shade(), lp(MATCH, WRAP)) }
        StepId.BACKGROUND -> ctx.vbox(10).apply {
            addView(ctx.activityControl(), lp(MATCH, WRAP))
            if (Readiness.isXiaomi()) addView(ctx.autostartList(), lp(MATCH, WRAP))
        }
        StepId.MIC -> ctx.vbox(10).apply { addView(ctx.micDialog(), lp(MATCH, WRAP)) }
        StepId.BLUETOOTH -> ctx.vbox(8).apply { ctx.bluetoothRows().forEach { addView(it, lp(MATCH, WRAP)) } }
        StepId.LOCATION -> ctx.vbox(8).apply { ctx.locationRows().forEach { addView(it, lp(MATCH, WRAP)) } }
        /** Шаг выбора машины рисует свой список (OnboardingScreen.renderCar), схемы нет. */
        StepId.CAR -> ctx.vbox()
        StepId.FIRST -> ctx.vbox(10).apply { addView(ctx.usbDialog(), lp(MATCH, WRAP)) }
        /** Шаг выбора режима рисует свои карточки (OnboardingScreen.renderMode), схемы нет. */
        StepId.MODE -> ctx.vbox()
        StepId.DEVMODE -> aboutPhone(ctx)
        StepId.ACTIVATE -> developerOptions(ctx)
    }

    // --- окна Android Auto ---

    /**
     * Окно настроек Android Auto: шапка, размытые строки и подсвеченная строка с нужным пунктом;
     * справа стрелка «пролистайте вниз» на всю высоту окна. chrome = стрелка назад и ⋮; compact = низкая строка и две полоски.
     */
    private fun Context.aaWindow(title: String, row: String, chrome: Boolean, tap: Boolean, check: Boolean, compact: Boolean): View {
        val c = this
        val win = card(20)
        win.addView(
            hbox(10).apply {
                pad(c, 14, 0, 14, 0)
                if (chrome) addView(icon(R.drawable.ic_back, 18, clr(R.color.on2)))
                addView(text(title, 16f, 600), lp(0, WRAP, 1f))
                if (chrome) addView(icon(R.drawable.ic_more_vert, 20, clr(R.color.on2)))
            },
            lpDp(MATCH, 48),
        )
        win.addView(divider())
        win.addView(
            vbox(12).apply {
                pad(c, 14, 14, 14, 12)
                for (w in if (compact) listOf(62, 80) else listOf(62, 80, 54, 70)) {
                    addView(
                        hbox(10).apply {
                            addView(bar(w), lp(0, WRAP, 1f))
                            if (check) {
                                addView(View(c).apply { background = shape(0, 3, clr(R.color.line), 2) }, lp(dp(14), dp(14)))
                            }
                        },
                        lp(MATCH, WRAP),
                    )
                }
                blurred(0.55f, 1.4f)
            },
            lp(MATCH, WRAP),
        )
        win.addView(
            hbox(8).apply {
                setBackgroundColor(clr(R.color.pric))
                pad(c, 14, 0, 10, 0)
                addView(text(row, if (compact) 15f else 18f, 700, clr(R.color.onpric)).apply { maxLines = 1 }, lp(0, WRAP, 1f))
                if (check) {
                    addView(
                        FrameLayout(c).apply {
                            background = shape(AA_CHECK, 5)
                            addView(icon(R.drawable.ic_check, 20, WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        },
                        lp(dp(28), dp(28)),
                    )
                }
                if (tap) {
                    addView(
                        hbox(6).apply {
                            addView(icon(R.drawable.ic_hand_tap, 34, clr(R.color.onpric)))
                            addView(badge("×10", 18f, 6))
                        },
                    )
                }
            },
            lpDp(MATCH, if (compact) 52 else 72),
        )
        return FrameLayout(this).apply {
            addView(win, FrameLayout.LayoutParams(MATCH, WRAP).apply { marginEnd = dp(34) })
            addView(scrollArrow(), FrameLayout.LayoutParams(dp(24), MATCH, Gravity.END))
        }
    }

    /** Стрелка прокрутки вниз: шток затухает сверху (прозрачный -> акцент), внизу наконечник. */
    private fun Context.scrollArrow(): View {
        val acc = clr(R.color.acc)
        return FrameLayout(this).apply {
            addView(
                View(this@scrollArrow).apply {
                    background = GradientDrawable().apply {
                        orientation = GradientDrawable.Orientation.TOP_BOTTOM
                        setColors(intArrayOf(acc and 0x00FFFFFF, acc, acc), floatArrayOf(0f, 0.7f, 1f))
                    }
                },
                FrameLayout.LayoutParams(dp(4), MATCH).apply { marginStart = dp(10); bottomMargin = dp(15) },
            )
            addView(icon(R.drawable.ic_scroll_arrow, 24, acc), FrameLayout.LayoutParams(dp(24), dp(16), Gravity.BOTTOM))
        }
    }

    /** Окно Android Auto с выпадающим меню ⋮ из четырёх пунктов; нужный подсвечен и помечен рукой. */
    private fun Context.menuWindow(): View {
        val c = this
        val back = vbox().apply {
            addView(
                hbox(10).apply {
                    pad(c, 14, 0, 8, 0)
                    addView(icon(R.drawable.ic_back, 18, clr(R.color.on2)))
                    addView(text(getString(R.string.mock_aa_title), 16f, 600), lp(0, WRAP, 1f))
                    addView(
                        FrameLayout(c).apply {
                            background = oval(clr(R.color.pric))
                            addView(icon(R.drawable.ic_more_vert, 20, clr(R.color.onpric)), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        },
                        lp(dp(32), dp(32)),
                    )
                },
                lpDp(MATCH, 48),
            )
            addView(divider())
            addView(
                vbox(12).apply {
                    pad(c, 14, 14, 14, 14)
                    for (w in listOf(62, 80, 54, 70, 48)) addView(bar(w), lp(MATCH, WRAP))
                    blurred(0.45f, 1.4f)
                },
                lp(MATCH, WRAP),
            )
        }
        fun item(s: String, h: Int, hi: Boolean = false) = hbox(4).apply {
            setPaddingRelative(dp(12), 0, dp(if (hi) 6 else 12), 0)
            if (hi) setBackgroundColor(clr(R.color.pric))
            val t = text(s, if (hi) 11f else 12f, if (hi) 700 else 400, clr(if (hi) R.color.onpric else R.color.on2)).apply { maxLines = 1 }
            if (hi) t.letterSpacing = -0.01f
            addView(t, lp(0, WRAP, 1f))
            if (hi) addView(icon(R.drawable.ic_hand_tap, 20, clr(R.color.onpric)))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(h))
        }
        val popup = vbox().apply {
            background = shape(clr(R.color.surf), 12)
            pad(c, 0, 6, 0, 6)
            elevation = dp(6).toFloat()
            addView(item(getString(R.string.mock_menu_help), 34))
            addView(item(getString(R.string.mock_aadev_title), 34))
            addView(item(getString(R.string.mock_menu_start), 42, hi = true))
            addView(item(getString(R.string.mock_menu_exit), 34))
        }
        return FrameLayout(this).apply {
            background = shape(clr(R.color.surf), 20)
            clipToOutline = true
            addView(back, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(popup, FrameLayout.LayoutParams(dp(228), WRAP, Gravity.END or Gravity.TOP).apply { topMargin = dp(44); marginEnd = dp(6) })
        }
    }

    // --- системные окна ---

    /** Шторка с уведомлением Headswap (шаг уведомлений). */
    private fun Context.shade(): View {
        val c = this
        return vbox(8).apply {
            background = shape(clr(R.color.surf), 20)
            pad(c, 10, 8, 10, 10)
            addView(
                hbox().apply {
                    pad(c, 6, 0, 6, 0)
                    addView(text("12:30", 12f, 600), lp(0, WRAP, 1f))
                    addView(icon(R.drawable.ic_status_wifi, 14, clr(R.color.on)))
                    addView(icon(R.drawable.ic_status_battery, 18, clr(R.color.on)), lp(dp(18), dp(10)).margins(c, 6, 0, 0, 0))
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(8).apply {
                    repeat(4) { addView(View(c).apply { background = shape(clr(R.color.line), 13) }, lp(0, dp(26), 1f)) }
                    blurred(0.55f, 1.2f)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(12).apply {
                    gravity = Gravity.TOP
                    background = shape(clr(R.color.bg), 16, clr(R.color.pri), 3)
                    pad(c, 12, 10, 12, 10)
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 12)
                            addView(icon(R.drawable.ic_logo, 22, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        },
                        lp(dp(36), dp(36)),
                    )
                    addView(
                        vbox(2).apply {
                            addView(text(getString(R.string.mock_notif_now), 13f, 400, clr(R.color.on2)))
                            addView(text(getString(R.string.home_title_ok), 16f, 600))
                            addView(text(getString(R.string.notif_video_usb), 14f, 400, clr(R.color.on2)))
                        },
                        lp(0, WRAP, 1f),
                    )
                },
                lp(MATCH, WRAP),
            )
            addView(View(c).apply { background = shape(clr(R.color.line), 2) }, lp(dp(36), dp(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    /** Сведения о приложении: «Контроль активности: Нет ограничений» с синей галочкой. */
    private fun Context.activityControl(): View {
        val c = this
        return vbox(6).apply {
            addView(text(getString(R.string.mock_bg_activity), 13f, 400, clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) })
            addView(
                hbox(10).apply {
                    background = shape(clr(R.color.surf), 16)
                    pad(c, 14, 12, 14, 12)
                    addView(
                        vbox(2).apply {
                            addView(text(getString(R.string.mock_bg_no_limits), 15f, 600, SYS_BLUE))
                            addView(text(getString(R.string.mock_bg_no_limits_sub), 12f, 400, SYS_BLUE).apply { lineHeight = dp(16) })
                        },
                        lp(0, WRAP, 1f),
                    )
                    addView(icon(R.drawable.ic_check, 22, SYS_BLUE))
                },
                lp(MATCH, WRAP),
            )
        }
    }

    /** Список «Автозапуск» Xiaomi с включённым Headswap (только на Xiaomi). */
    private fun Context.autostartList(): View {
        val c = this
        return card(16).apply {
            addView(text(getString(R.string.mock_bg_autostart), 13f, 600, clr(R.color.on2)).apply { pad(c, 14, 0, 14, 0); gravity = Gravity.CENTER_VERTICAL }, lpDp(MATCH, 36))
            addView(
                hbox(12).apply {
                    setBackgroundColor(clr(R.color.pric))
                    pad(c, 14, 0, 12, 0)
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 10)
                            addView(icon(R.drawable.ic_logo, 22, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        },
                        lp(dp(32), dp(32)),
                    )
                    addView(text("Headswap", 15f, 700, clr(R.color.onpric)), lp(0, WRAP, 1f))
                    addView(styledSwitch().apply { isChecked = true })
                },
                lpDp(MATCH, 56),
            )
        }
    }

    /** Запрос разрешения на микрофон: кнопка «При использовании приложения» обведена. */
    private fun Context.micDialog(): View {
        val c = this
        return vbox(8).apply {
            background = shape(clr(R.color.surf), 22)
            pad(c, 14, 14, 14, 12)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                FrameLayout(c).apply {
                    background = shape(clr(R.color.surf2), 8)
                    addView(icon(R.drawable.ic_mic, 18, clr(R.color.on)), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                },
                lp(dp(32), dp(32)),
            )
            addView(
                text(getString(R.string.mock_mic_question), 14f, 400).apply {
                    lineHeight = dp(19)
                    gravity = Gravity.CENTER
                    pad(c, 8, 0, 8, 4)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(6).apply {
                    gravity = Gravity.CENTER
                    background = shape(SYS_BUTTON, 20, clr(R.color.pri), 3)
                    addView(text(getString(R.string.mock_mic_while_using), 13f, 600, WHITE))
                    addView(icon(R.drawable.ic_hand_tap, 20, WHITE))
                },
                lpDp(MATCH, 40),
            )
            for (s in listOf(R.string.mock_mic_once, R.string.mock_mic_deny)) {
                addView(
                    text(getString(s), 13f, 400).apply {
                        gravity = Gravity.CENTER
                        background = shape(clr(R.color.surf2), 18)
                    },
                    lpDp(MATCH, 36),
                )
            }
        }
    }

    /** Системное окно Bluetooth: переключатель, подключённая магнитола, размытая строка. */
    private fun Context.bluetoothRows(): List<View> {
        val c = this
        val toggle = hbox().apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 0, 12, 0)
            addView(text(getString(R.string.mock_bt_title), 15f, 600), lp(0, WRAP, 1f))
            addView(sysSwitch())
            minimumHeight = dp(52)
        }
        val headUnit = hbox(12).apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 0, 12, 0)
            addView(icon(R.drawable.ic_car, 24, clr(R.color.on)))
            addView(
                vbox(2).apply {
                    addView(text(getString(R.string.mock_bt_headunit), 15f, 600))
                    addView(text(getString(R.string.mock_bt_connected), 12f, 400, SYS_BLUE))
                },
                lp(0, WRAP, 1f),
            )
            addView(
                FrameLayout(c).apply {
                    background = oval(clr(R.color.surf2))
                    addView(icon(R.drawable.ic_chevron, 14, clr(R.color.on2)), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                },
                lp(dp(26), dp(26)),
            )
            minimumHeight = dp(60)
        }
        val ghost = hbox(12).apply {
            background = shape(clr(R.color.surf), 16)
            pad(c, 14, 0, 14, 0)
            addView(View(c).apply { background = oval(clr(R.color.line)) }, lp(dp(22), dp(22)))
            addView(bar(55), lp(0, WRAP, 1f))
            minimumHeight = dp(48)
            blurred(0.5f, 1.2f)
        }
        return listOf(toggle, headUnit, ghost)
    }

    /** Системное окно геолокации: переключатель «Включить геолокацию» обведён, ниже размытые строки. */
    private fun Context.locationRows(): List<View> {
        val c = this
        val toggle = hbox(12).apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 12, 12, 12)
            addView(
                vbox(2).apply {
                    addView(text(getString(R.string.mock_loc_use), 15f, 600))
                    addView(text(getString(R.string.mock_loc_use_sub), 12f, 400, clr(R.color.on2)).apply { lineHeight = dp(16) })
                },
                lp(0, WRAP, 1f),
            )
            addView(sysSwitch())
        }
        val ghosts = vbox(8).apply {
            for (w in listOf(60, 45)) {
                addView(
                    hbox().apply {
                        background = shape(clr(R.color.surf), 16)
                        pad(c, 14, 0, 14, 0)
                        addView(bar(w), lp(0, WRAP, 1f))
                    },
                    lpDp(MATCH, 48),
                )
            }
            blurred(0.5f, 1.2f)
        }
        return listOf(toggle, ghosts)
    }

    /** Запрос «Открыть Headswap для USB-аксессуара»: галочка «По умолчанию» и ОК. */
    private fun Context.usbDialog(): View {
        val c = this
        return vbox(14).apply {
            background = shape(clr(R.color.surf), 24)
            pad(c, 20, 20, 20, 20)
            addView(text(getString(R.string.mock_usb_question), 15f, 400).apply { lineHeight = dp(21) })
            addView(
                vbox(6).apply {
                    addView(bar(85, 9), lp(MATCH, WRAP))
                    addView(bar(60, 9), lp(MATCH, WRAP))
                    blurred(0.6f, 1.6f)
                },
                lp(MATCH, WRAP),
            )
            addView(
                hbox(10).apply {
                    addView(
                        FrameLayout(c).apply {
                            background = shape(clr(R.color.pri), 4)
                            addView(icon(R.drawable.ic_check, 14, clr(R.color.onpri)), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                        },
                        lp(dp(20), dp(20)),
                    )
                    addView(text(getString(R.string.mock_usb_default), 14f, 400, clr(R.color.on2)))
                },
            )
            addView(
                hbox(20).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(text(getString(R.string.mock_cancel), 15f, 600, clr(R.color.acc)).apply { alpha = 0.6f })
                    addView(text(getString(R.string.mock_ok), 15f, 600, clr(R.color.acc)).apply {
                        background = shape(clr(R.color.pric), 12)
                        pad(c, 10, 2, 10, 2)
                    })
                },
            )
        }
    }

    /**
     * Экран «Для разработчиков» для шага активации в «Режиме»: заголовок, обведённая строка «Отладка по Wi-Fi»
     * с включённым переключателем, под ней «Подключить устройство с кодом сопряжения» и размытая строка.
     */
    fun developerOptions(ctx: Context): View = ctx.developerRows().let { rows ->
        ctx.vbox(8).apply { rows.forEach { addView(it, lp(MATCH, WRAP)) } }
    }

    private fun Context.developerRows(): List<View> {
        val c = this
        val title = text(getString(R.string.mock_dev_title), 13f, 600, clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) }
        val wifi = hbox().apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 0, 12, 0)
            addView(text(getString(R.string.mock_dev_wifi), 15f, 600), lp(0, WRAP, 1f))
            addView(sysSwitch())
            minimumHeight = dp(52)
        }
        val pair = hbox().apply {
            background = shape(clr(R.color.surf), 16)
            pad(c, 14, 8, 14, 8)
            addView(text(getString(R.string.mock_dev_pair), 14f, 400).apply { lineHeight = dp(19) }, lp(0, WRAP, 1f))
            minimumHeight = dp(48)
        }
        val ghost = hbox(12).apply {
            background = shape(clr(R.color.surf), 16)
            pad(c, 14, 0, 14, 0)
            addView(bar(55), lp(0, WRAP, 1f))
            minimumHeight = dp(44)
            blurred(0.5f, 1.2f)
        }
        return listOf(title, wifi, pair, ghost)
    }

    /**
     * Экран «О телефоне» для шага режима разработчика: обведённая строка «Номер сборки» с подписью для Xiaomi,
     * рукой и меткой ×7; выше и ниже размытые строки.
     */
    fun aboutPhone(ctx: Context): View = ctx.aboutRows().let { rows ->
        ctx.vbox(8).apply { rows.forEach { addView(it, lp(MATCH, WRAP)) } }
    }

    private fun Context.aboutRows(): List<View> {
        val c = this
        val title = text(getString(R.string.mock_about_title), 13f, 600, clr(R.color.on2)).apply { pad(c, 6, 0, 6, 0) }
        fun ghost(pct: Int) = hbox(12).apply {
            background = shape(clr(R.color.surf), 16)
            pad(c, 14, 0, 14, 0)
            addView(bar(pct), lp(0, WRAP, 1f))
            minimumHeight = dp(44)
            blurred(0.5f, 1.2f)
        }
        val build = hbox(8).apply {
            background = shape(clr(R.color.surf), 16, clr(R.color.pri), 3)
            pad(c, 14, 8, 12, 8)
            addView(
                vbox(2).apply {
                    addView(text(getString(R.string.mock_about_build), 15f, 600))
                    addView(text(getString(R.string.mock_about_build_sub), 12f, 400, clr(R.color.on2)).apply { lineHeight = dp(16) })
                },
                lp(0, WRAP, 1f),
            )
            addView(icon(R.drawable.ic_hand_tap, 30, clr(R.color.acc)))
            addView(badge("×7", 16f, 5))
            minimumHeight = dp(60)
        }
        return listOf(title, ghost(62), build, ghost(48))
    }

    // --- кирпичики ---

    /** Полоска-«строка текста» шириной pct процентов (размытие рисуется полупрозрачной полоской цвета line). */
    private fun Context.bar(pct: Int, heightDp: Int = 10): View = hbox().apply {
        weightSum = 100f
        addView(View(this@bar).apply { background = shape(clr(R.color.line), heightDp / 2) }, LinearLayout.LayoutParams(0, dp(heightDp), pct.toFloat()))
        addView(View(this@bar), LinearLayout.LayoutParams(0, dp(heightDp), (100 - pct).toFloat()))
    }

    /** Полупрозрачность и, на API 31+, настоящее размытие. */
    private fun <T : View> T.blurred(alphaValue: Float, blurDp: Float): T = apply {
        alpha = alphaValue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val px = blurDp * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP))
        }
    }

    private fun Context.badge(s: String, sp: Float, vPadDp: Int) = text(s, sp, 700, clr(R.color.onpri)).apply {
        background = shape(clr(R.color.pri), 16)
        pad(this@badge, 12, vPadDp, 12, vPadDp)
    }

    /** Включённый переключатель системного UI Android (синий, как в макете). */
    private fun Context.sysSwitch(): View = FrameLayout(this).apply {
        background = shape(SYS_BLUE, 14)
        addView(
            View(this@sysSwitch).apply { background = oval(WHITE) },
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(3) },
        )
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(28))
    }
}
