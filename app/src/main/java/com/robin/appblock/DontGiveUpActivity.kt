package com.robin.appblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Opened from the "Don't give up!" launcher shortcut (long-press the app
 * icon). Placeholder: a single line of encouragement and a way out to the
 * home screen for now.
 */
class DontGiveUpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Don't give up!"

        val message = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
            text = "You didn't come this far to quit now."
        }
        val home = Button(this).apply {
            text = "Go to home screen"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(message)
            addView(home)
        })
    }
}
