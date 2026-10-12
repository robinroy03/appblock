package com.robin.appblock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The app's mark: a time-budget ring, mostly full, with a dot sitting in
 * the gap. Used as the empty-state hero and on the first-run screen. It
 * draws itself in once, then holds still.
 */
class RingView(context: Context, private val strokeDp: Float = 10f) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.surface_alt)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.ink)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
    }
    private val box = RectF()

    /** 0..1 of the ring that is filled. */
    var progress = 0.75f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    fun drawIn(to: Float = 0.75f, delayMs: Long = 150L) {
        if (!Ui.motionOn(context)) { progress = to; return }
        progress = 0f
        ValueAnimator.ofFloat(0f, to).apply {
            startDelay = delayMs
            duration = 900L
            interpolator = Ui.settle
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = Ui.dpf(context, strokeDp)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2f + Ui.dpf(context, 2f)
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        // Fill runs counter-clockwise from 12 o'clock, leaving the gap top-right.
        canvas.drawArc(box, -90f, -progress * 360f, false, arc)
        if (progress >= 0.7f) {
            val cx = box.centerX() + (box.width() / 2f) * Math.cos(Math.toRadians(-45.0)).toFloat()
            val cy = box.centerY() + (box.height() / 2f) * Math.sin(Math.toRadians(-45.0)).toFloat()
            canvas.drawCircle(cx, cy, stroke * 0.5f, dot)
        }
    }
}
