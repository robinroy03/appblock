package xyz.robinroy.appblock

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Bottom sheet in place of the system dialog: slides up on the drawer
 * curve, leaves faster than it arrives, dismisses on a tap outside or a swipe down.
 * Build it, add content, add the action row, then show().
 */
class Sheet(host: Activity) : Dialog(host, R.style.SheetDialog) {
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val r = Ui.dpf(context, 30f)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.surface))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val side = Ui.dp(context, 24)
        setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 24))
    }

    init {
        column.addView(View(context).apply {
            background = Ui.shape(context, R.color.line, 99f)
        }, LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(context, 20)
        })
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
        setContentView(DragDismissLayout(context, this) { scroll }.apply { addView(scroll) })
        setCanceledOnTouchOutside(true)
        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.setPadding(0, 0, 0, 0)
            navigationBarColor = context.getColor(R.color.surface)   // sheet reaches the screen edge
        }
    }

    /** Title (and optional subtitle), with an optional leading view such as an app icon. */
    fun header(title: String, subtitle: String? = null, leading: View? = null): Sheet {
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.display(context, title, 26f))
            if (subtitle != null) addView(Ui.muted(context, subtitle, 15f).apply {
                setPadding(0, Ui.dp(context, 6), 0, 0)
            })
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (leading != null) {
                addView(leading)
                (leading.layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 14)
            }
            addView(texts, Ui.lp(0).apply { weight = 1f })
        })
        return this
    }

    fun message(text: CharSequence): Sheet {
        column.addView(Ui.body(context, text, 15f).apply { setTextColor(context.getColor(R.color.muted)) },
            Ui.lp().apply { topMargin = Ui.dp(context, 12) })
        return this
    }

    fun add(view: View, topDp: Int = 16): Sheet {
        val params = (view.layoutParams as? LinearLayout.LayoutParams) ?: Ui.lp()
        params.topMargin = Ui.dp(context, topDp)
        column.addView(view, params)
        return this
    }

    /** The action row. Buttons sit right; `leading` (e.g. Remove) sits left. */
    fun actions(vararg buttons: View, leading: View? = null): Sheet {
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (leading != null) addView(leading)
            addView(View(context), Ui.lp(0).apply { weight = 1f })
            buttons.forEachIndexed { i, b ->
                addView(b, Ui.lp(Ui.WRAP).apply {
                    if (i > 0) marginStart = Ui.dp(context, 8)
                })
            }
        }, Ui.lp().apply { topMargin = Ui.dp(context, 24) })
        return this
    }

    fun primary(label: String, onClick: () -> Unit = {}) =
        Ui.primaryButton(context, label) { onClick() }

    fun secondary(label: String, onClick: () -> Unit = { dismiss() }) =
        Ui.secondaryButton(context, label) { onClick() }

    companion object {
        /** One question, two answers. */
        fun confirm(
            host: Activity, title: String, message: String,
            yes: String, no: String, icon: View? = null, onYes: () -> Unit
        ) {
            lateinit var sheet: Sheet
            sheet = Sheet(host).header(title, leading = icon).message(message).apply {
                actions(
                    secondary(no) { sheet.dismiss() },
                    primary(yes) { sheet.dismiss(); onYes() })
            }
            sheet.show()
        }

        /** Information with one way out. */
        fun info(host: Activity, title: String, message: String, ok: String = "OK") {
            lateinit var sheet: Sheet
            sheet = Sheet(host).header(title).message(message).apply {
                actions(primary(ok) { sheet.dismiss() })
            }
            sheet.show()
        }
    }
}
