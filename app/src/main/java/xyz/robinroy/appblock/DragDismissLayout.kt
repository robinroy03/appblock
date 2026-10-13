package xyz.robinroy.appblock

import android.app.Dialog
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Holds a sheet's content and lets a downward drag carry it off the screen,
 * the way a bottom sheet should. The drag starts from anywhere (the handle,
 * the header, the text) as long as the scrolling content underneath is
 * already at its top; otherwise the content scrolls as usual. Let go past a
 * third of the way, or with a flick, and the sheet leaves and the dialog
 * closes. Let go early and it settles back. The dim behind it fades with it.
 */
class DragDismissLayout(
    context: Context,
    private val dialog: Dialog,
    /** The scrolling view inside the sheet right now, if any. */
    private val scroller: () -> View?
) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    // Read lazily: the theme's dim only lands on the window once the content is installed.
    private val dimAmount by lazy { dialog.window?.attributes?.dimAmount ?: 0.4f }

    private val sheet: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        return true   // keep the gesture, even when it starts over bare sheet
    }

    private fun track(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y; dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging) {
                    // Content that can still scroll up owns the gesture; its
                    // top is where a drag down starts to belong to the sheet.
                    if (scroller()?.canScrollVertically(-1) == true) {
                        startX = e.x; startY = e.y
                    } else {
                        val dy = e.y - startY
                        if (dy > slop && dy > abs(e.x - startX)) {
                            dragging = true
                            startY = e.y
                        }
                    }
                } else {
                    follow(e.y - startY)
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                if (dragging) release()
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle()
                reset()
            }
        }
    }

    private fun reset() {
        dragging = false
        tracker?.recycle()
        tracker = null
    }

    private fun follow(dy: Float) {
        val s = sheet ?: return
        s.translationY = dy.coerceAtLeast(0f)
        dialog.window?.setDimAmount(dimAmount * (1f - (s.translationY / s.height).coerceIn(0f, 1f)))
    }

    private fun release() {
        val s = sheet ?: return
        tracker?.computeCurrentVelocity(1000)
        val flick = (tracker?.yVelocity ?: 0f) > 1400f
        if (flick || s.translationY > s.height * 0.3f) leave() else settle()
    }

    private fun settle() {
        val s = sheet ?: return
        s.animate().translationY(0f).setDuration(280L).setInterpolator(Ui.settle)
            .setUpdateListener { dialog.window?.setDimAmount(
                dimAmount * (1f - (s.translationY / s.height).coerceIn(0f, 1f))) }
            .start()
    }

    private fun leave() {
        val s = sheet ?: return
        s.animate().translationY(s.height.toFloat()).setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.3f))
            .setUpdateListener { dialog.window?.setDimAmount(
                dimAmount * (1f - (s.translationY / s.height).coerceIn(0f, 1f))) }
            .withEndAction {
                // Already off screen: skip the window's own exit slide.
                dialog.window?.setWindowAnimations(0)
                dialog.dismiss()
            }.start()
    }
}
