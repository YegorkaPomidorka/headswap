package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.CarSettings

/**
 * Выбор машины: поле-выпадашка (список CarPreset раскрывается прямо под полем, его ширины) и под ним карточка «Будет применено» с названием каждой настройки.
 * Строки карточки фиксированной высоты, при выборе меняются только значения: ничего не прыгает.
 * Последний шаг онбординга (в настройках раздела нет). Сам ничего не пишет, только сообщает выбор в [onPick];
 * применяет экран по своей кнопке «Применить».
 * [withOther] - последним пунктом «Моей машины нет в списке» (выбор null, настройки не трогаются).
 */
class CarChooser(val ctx: Context, private val withOther: Boolean, private val onPick: (CarPreset?) -> Unit) {
    private val fieldValue = ctx.text("", 17f, 500).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val values = ArrayList<TextView>()
    private val chevron = ctx.icon(R.drawable.ic_chevron, 20, ctx.clr(R.color.on2)).apply { rotation = ctx.chevronRotation(false) }
    private val field: LinearLayout
    private val opts: List<CarPreset?> = CarPreset.values().toList() + if (withOther) listOf(null) else emptyList()

    /** false - ещё ничего не выбрано (поле «Выберите машину», значения «—»). */
    var hasChoice = false
        private set
    var choice: CarPreset? = null
        private set
    val view: LinearLayout

    init {
        val c = ctx
        field = c.hbox(12).apply {
            pad(c, 16, 0, 16, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openList() }
            addView(c.vbox(2).apply {
                addView(c.text(c.getString(R.string.car_field), 13f, 500, c.clr(R.color.on2)))
                addView(fieldValue, lp(MATCH, WRAP))
            }, lp(0, WRAP, 1f))
            addView(chevron)
        }
        setOpen(false)
        val card = c.card().apply { pad(c, 0, 6, 0, 6) }
        card.addView(c.text(c.getString(R.string.car_will_apply), 14f, 600, c.clr(R.color.acc)).apply { pad(c, 16, 10, 16, 4) })
        for (label in LABELS) {
            val v = c.text("", 16f, 500).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            values += v
            card.addView(c.vbox(1).apply {
                gravity = Gravity.CENTER_VERTICAL
                pad(c, 16, 6, 16, 6)
                addView(c.text(c.getString(label), 13f, 400, c.clr(R.color.on2)))
                addView(v, lp(MATCH, WRAP))
            }, c.lpDp(MATCH, 52))
        }
        view = c.vbox(12).apply {
            addView(field, c.lpDp(MATCH, 64))
            addView(card, lp(MATCH, WRAP))
        }
        show(null, has = false)
    }

    /** Показать выбор (без вызова [onPick]). */
    fun show(p: CarPreset?, has: Boolean = true) {
        val c = ctx
        hasChoice = has
        choice = if (has) p else null
        fieldValue.text = when {
            !has -> c.getString(R.string.car_pick_hint)
            p == null -> c.getString(R.string.car_other_title)
            else -> c.getString(p.titleRes)
        }
        fieldValue.setTextColor(c.clr(if (has) R.color.on else R.color.on2))
        val texts = when {
            !has -> List(LABELS.size) { "—" }
            p == null -> List(LABELS.size) { c.getString(R.string.car_value_keep) }
            else -> c.settingNames(p.settings)
        }
        for ((v, t) in values.zip(texts)) v.text = t
    }

    /** Раскрытое поле: рамка цвета акцента той же толщины (ничего не сдвигается), шеврон вверх. */
    private fun setOpen(open: Boolean) {
        val c = ctx
        field.background = c.rippleBg(c.shape(c.clr(R.color.surf), 16, c.clr(if (open) R.color.acc else R.color.line), 1.5f), 16)
        chevron.rotation = c.chevronRotation(open)
    }

    /** Список под полем: шириной с поле, до ~6 строк, дальше прокрутка; нажатие мимо закрывает. */
    private fun openList() {
        val c = ctx
        val popup = ListPopupWindow(c)
        popup.anchorView = field
        popup.width = field.width
        popup.height = minOf(opts.size * c.dp(ITEM_DP) + c.dp(12), c.dp(ITEM_DP * 6 + 12))
        popup.verticalOffset = c.dp(4)
        popup.isModal = true
        popup.setBackgroundDrawable(c.shape(c.clr(R.color.surf), 16))
        popup.setAdapter(Adapter())
        popup.setOnItemClickListener { _, _, i, _ ->
            popup.dismiss()
            show(opts[i])
            onPick(opts[i])
        }
        popup.setOnDismissListener { setOpen(false) }
        setOpen(true)
        popup.show()
        popup.listView?.apply {
            divider = null
            setPadding(0, c.dp(6), 0, c.dp(6))
            clipToPadding = false
            if (hasChoice) setSelection(opts.indexOf(choice).coerceAtLeast(0))
        }
    }

    /** Строка списка: выбранная залита pric, начертание у всех одно (не прыгает). */
    private inner class Adapter : BaseAdapter() {
        override fun getCount() = opts.size

        override fun getItem(i: Int) = opts[i]

        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
            val c = ctx
            val tv = convertView as? TextView ?: c.text("", 16f, 400).apply {
                gravity = Gravity.CENTER_VERTICAL
                pad(c, 16, 0, 16, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(ITEM_DP))
            }
            val p = opts[i]
            val on = hasChoice && p == choice
            tv.text = c.getString(p?.titleRes ?: R.string.car_other_title)
            tv.setTextColor(c.clr(if (on) R.color.onpric else R.color.on))
            tv.background = c.rippleBg(if (on) c.shape(c.clr(R.color.pric), 0) else null, 0)
            return tv
        }
    }

    private companion object {
        const val ITEM_DP = 48

        /** Подписи строк «Будет применено», в порядке [settingNames]. */
        val LABELS = listOf(R.string.car_label_mode, R.string.set_resolution, R.string.set_aa_density, R.string.set_fps, R.string.set_music_rate)
    }
}

/** Названия настроек, как в разделах «Видео» и «Звук»: режим, разрешение, масштаб AA, кадры, музыка. */
private fun Context.settingNames(s: CarSettings): List<String> = listOf(
    getString(if (s.transcode) R.string.set_transcode_title else R.string.set_direct_title),
    getString(s.videoMode.effective(s.transcode).titleRes),
    if (s.aaDensity == AaDensity.AUTO) getString(R.string.car_auto) else getString(R.string.set_sum_density, s.aaDensity),
    getString(s.fps.titleRes),
    getString(s.musicRate.titleRes),
)
