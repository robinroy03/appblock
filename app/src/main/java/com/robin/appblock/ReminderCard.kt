package com.robin.appblock

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The amber "something could be better" info card, shared by the home
 * screen's notification reminder, the picker's usage-access hint, and the
 * About page's permissions alert: tap the body to go fix the setting.
 *
 * Dismissal is opt-in: pass the confirm strings and onHideForever to get a ✕
 * that hides the card for good, behind an "are you sure" dialog whose body
 * should mention that the About page can still fix the setting later. Leave
 * them out for a card that can't be dismissed.
 */
object ReminderCard {

    fun make(
        activity: Activity,
        message: String,
        onFix: () -> Unit,
        confirmTitle: String? = null,
        confirmBody: String? = null,
        onHideForever: (() -> Unit)? = null,
    ): LinearLayout {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        lateinit var card: LinearLayout
        card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 12, 4, 12)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(if (night) 0xFF33301F.toInt() else 0xFFFFF8E1.toInt())
                setStroke(3, 0xFFFFB300.toInt())
            }
            setOnClickListener { onFix() }
            addView(ImageView(activity).apply {
                setImageResource(android.R.drawable.ic_dialog_info)
            })
            addView(TextView(activity).apply {
                text = message
                textSize = 14f
                setPadding(24, 12, 8, 12)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onHideForever != null) addView(TextView(activity).apply {
                text = "✕"
                textSize = 18f
                setPadding(28, 28, 28, 28)
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle(confirmTitle)
                        .setMessage(confirmBody)
                        .setPositiveButton("Yes, hide it") { _, _ ->
                            onHideForever()
                            card.visibility = View.GONE
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            })
        }
        return card
    }
}
