package com.robin.appblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Full-screen wall shown when a blocked app is opened over budget. */
class BlockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val waitMin = (intent.getLongExtra("waitMs", 0) + 59_999) / 60_000  // round up

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (e: Exception) { pkg }

        setContentView(TextView(this).apply {
            text = "$label is blocked.\n\nTry again in $waitMin min."
            textSize = 24f
            gravity = Gravity.CENTER
        })
    }

    // Back button goes home instead of back into the blocked app.
    override fun onBackPressed() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }
}
