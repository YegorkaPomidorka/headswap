package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.yegorkapomidorka.headswap.R

/** Кольцо «идёт сейчас» в «По воздуху»: три четверти pri, четверть surf2 (как в макете). Не вращается. */
class WlRing(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = context.dp(3).toFloat()
        paint.strokeWidth = w
        rect.set(w / 2, w / 2, width - w / 2, height - w / 2)
        paint.color = context.clr(R.color.surf2)
        canvas.drawArc(rect, 0f, 360f, false, paint)
        paint.color = context.clr(R.color.pri)
        canvas.drawArc(rect, 45f, 270f, false, paint)
    }
}
