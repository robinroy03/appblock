package xyz.robinroy.appblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Opened from the "Don't give up!" launcher shortcut (long-press the app
 * icon). A single line of encouragement and a way back to the launcher.
 */
class DontGiveUpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        title = "Don't give up"

        val ring = RingView(this, 10f)
        val message = Ui.display(this, "You didn't come this far to quit now.", 30f).apply {
            gravity = Gravity.CENTER
        }
        val note = Ui.muted(this, "Close the app. The limit is still there when you come back.", 16f).apply {
            gravity = Gravity.CENTER
        }
        val home = Ui.primaryButton(this, "Go to home screen") {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = Ui.dp(context, 28)
            setPadding(side, side, side, side)
            addView(ring, Ui.lp(Ui.dp(context, 96), Ui.dp(context, 96)))
            addView(message, Ui.lp().apply { topMargin = Ui.dp(context, 32) })
            addView(note, Ui.lp().apply { topMargin = Ui.dp(context, 12); bottomMargin = Ui.dp(context, 32) })
            addView(home, Ui.lp(Ui.WRAP))
            Ui.enter(message, 1); Ui.enter(note, 2); Ui.enter(home, 3)
        })
        ring.drawIn(0.78f, 100L)
    }
}
