package com.robin.appblock

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The reading sheet behind the "i": About, Permissions, How it works and
 * Philosophy all live in this one frame. The frame is a fixed height, so it
 * never jumps between pages; the pages inside it slide over each other and
 * scroll on their own. A swipe down (from anywhere, once the page is at its
 * top) or a tap outside closes it. There's no Done button: the sheet has
 * nothing to confirm.
 */
class InfoSheet(private val host: Activity) : Dialog(host, R.style.SheetDialog) {

    private val stage = FrameLayout(context)
    private val pages = mutableListOf<View>()
    private var busy = false

    init {
        val radius = Ui.dpf(context, 30f)
        val frame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.surface))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            addView(View(context).apply {
                background = Ui.shape(context, R.color.line, 99f)
            }, LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(context, 12)
                bottomMargin = Ui.dp(context, 8)
            })
            addView(stage, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        }
        setContentView(DragDismissLayout(context, this) { pages.lastOrNull() }.apply {
            addView(frame, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        })
        setCanceledOnTouchOutside(true)
        // 86% of the screen, always: the same frame for every page.
        val height = (context.resources.displayMetrics.heightPixels * 0.86f).toInt()
        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
            decorView.setPadding(0, 0, 0, 0)
            navigationBarColor = context.getColor(R.color.surface)
        }
    }

    /**
     * A page: an optional title row (with a back button once there is a page
     * to go back to), then whatever `body` adds. It scrolls on its own and
     * fills the frame, so a short page can pin something to the bottom.
     */
    fun page(
        title: String? = null, subtitle: String? = null, body: LinearLayout.() -> Unit
    ): View {
        val canGoBack = pages.isNotEmpty()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = Ui.dp(context, 24)
            setPadding(side, Ui.dp(context, 8), side, Ui.dp(context, 28))
            if (title != null) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    if (canGoBack) addView(Ui.iconButton(context, R.drawable.ic_back, "Back", 40) {
                        pop()
                    }, LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 40)).apply {
                        marginEnd = Ui.dp(context, 14)
                    })
                    addView(Ui.display(context, title, 26f), Ui.lp(0).apply { weight = 1f })
                })
                if (subtitle != null) addView(Ui.muted(context, subtitle, 15f).apply {
                    setLineSpacing(0f, 1.45f)
                }, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
            }
            body()
        }
        return ScrollView(context).apply {
            setBackgroundColor(context.getColor(R.color.surface))
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
    }

    /** Show the sheet on its first page. */
    fun show(first: View) {
        pages += first
        stage.addView(first, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        show()
    }

    /** Slide another page in over the current one. */
    fun push(next: View) {
        if (busy || pages.isEmpty()) return
        val current = pages.last()
        pages += next
        stage.addView(next, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        slide(next, from = 1f, current, to = -0.2f) { current.visibility = View.INVISIBLE }
    }

    /** Back to the page before, or close from the first one. */
    fun pop() {
        if (busy) return
        if (pages.size < 2) { dismiss(); return }
        val leaving = pages.removeAt(pages.lastIndex)
        val back = pages.last()
        back.visibility = View.VISIBLE
        slide(back, from = -0.2f, leaving, to = 1f) { stage.removeView(leaving) }
    }

    @Deprecated("Back steps through the pages first")
    override fun onBackPressed() = pop()

    /** `coming` glides in from `from` to rest; `going` glides from rest to `to` (fractions of the width). */
    private fun slide(coming: View, from: Float, going: View, to: Float, done: () -> Unit) {
        if (!Ui.motionOn(context)) {
            coming.translationX = 0f; coming.alpha = 1f; done(); return
        }
        busy = true
        val w = stage.width.toFloat()
        coming.translationX = from * w
        coming.alpha = if (from > 0f) 1f else 0.4f
        coming.animate().translationX(0f).alpha(1f).setDuration(340L).setInterpolator(Ui.settle).start()
        going.animate().translationX(to * w).alpha(if (to > 0f) 1f else 0.4f)
            .setDuration(340L).setInterpolator(Ui.settle)
            .withEndAction {
                going.translationX = 0f; going.alpha = 1f
                busy = false
                done()
            }.start()
    }
}
