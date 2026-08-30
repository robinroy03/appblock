package com.robin.appblock

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The enforcer. Android calls onAccessibilityEvent() whenever the foreground
 * window changes (app opened, app switched, etc). While a rule-listed app is
 * in the foreground we also tick every few seconds, so a session gets cut off
 * the moment its budget runs out.
 *
 * The block wall is an overlay window drawn by this service directly over the
 * blocked app (TYPE_ACCESSIBILITY_OVERLAY) — NOT an Activity. Activities
 * launched from a background service get silently dropped or queued by
 * Android 10+ background-launch restrictions; an overlay appears instantly
 * and reliably.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null   // rule-listed app currently in foreground
    private var sessionStart = 0L
    private val tickMs = 5_000L

    private var overlay: LinearLayout? = null
    private var overlayPkg: String? = null   // which app the wall is covering

    private val tick = object : Runnable {
        override fun run() {
            val pkg = currentPkg ?: return
            val rule = Storage.loadRules(this@BlockerService)[pkg] ?: return
            // Flush the session so far into the usage log, then re-check the budget.
            val now = System.currentTimeMillis()
            Storage.addUsage(this@BlockerService, pkg, sessionStart, now, rule.windowMin)
            sessionStart = now
            if (Storage.usedMsInWindow(this@BlockerService, pkg, rule.windowMin) >= rule.allowMin * 60_000L) {
                currentPkg = null
                block(pkg, rule)
            } else {
                maybeWarn(pkg, rule)
                handler.postDelayed(this, tickMs)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore our own windows and system surfaces (notification shade etc).
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Foreground moved off the walled app -> take the wall down.
        if (overlay != null && pkg != overlayPkg) hideOverlay()

        if (pkg == currentPkg) return
        endSession()

        val rule = Storage.loadRules(this)[pkg] ?: return
        if (Storage.usedMsInWindow(this, pkg, rule.windowMin) >= rule.allowMin * 60_000L) {
            block(pkg, rule)
        } else {
            currentPkg = pkg
            sessionStart = System.currentTimeMillis()
            // Warn right away if earlier sessions already put usage past a threshold.
            maybeWarn(pkg, rule)
            handler.postDelayed(tick, tickMs)
        }
    }

    /**
     * Post the 50%/90% usage warnings, once per climb past each threshold.
     * The stored level tracks usage back down as the rolling window forgets
     * old sessions, so each threshold fires again on the next climb.
     */
    private fun maybeWarn(pkg: String, rule: Rule) {
        val usedMs = Storage.usedMsInWindow(this, pkg, rule.windowMin)
        val level = Storage.crossedWarnLevel(usedMs, rule.allowMin)
        val last = Storage.warnLevel(this, pkg)
        if (level > last) {
            val pct = Storage.usedPct(usedMs, rule.allowMin)
            val min = Storage.displayedUsedMin(usedMs, rule.allowMin)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                "usage", "Usage warnings", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(pkg.hashCode(), Notification.Builder(this, "usage")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("AppBlock")
                .setContentText("You've used $pct% ($min min) of your usage for ${labelFor(pkg)}")
                .setAutoCancel(true)
                .build())
        }
        if (level != last) Storage.setWarnLevel(this, pkg, level)
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** Foreground moved elsewhere: log the finished session, stop ticking. */
    private fun endSession() {
        handler.removeCallbacks(tick)
        val pkg = currentPkg ?: return
        currentPkg = null
        val rule = Storage.loadRules(this)[pkg] ?: return
        Storage.addUsage(this, pkg, sessionStart, System.currentTimeMillis(), rule.windowMin)
    }

    /** Cover the screen with the block wall. */
    private fun block(pkg: String, rule: Rule) {
        if (overlay != null) return
        val waitMin = Storage.ceilMin(Storage.msUntilUnblocked(this, pkg, rule))
        val label = labelFor(pkg)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val wall = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 0, 64, 0)
            setBackgroundColor(if (night) 0xFF121212.toInt() else 0xFFFAFAFA.toInt())
            addView(TextView(context).apply {
                text = "$label is blocked.\n\nTry again in $waitMin min."
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (night) 0xFFEEEEEE.toInt() else 0xFF111111.toInt())
            })
            addView(Button(context).apply {
                text = "Go to home screen"
                setOnClickListener {
                    hideOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 64; gravity = Gravity.CENTER_HORIZONTAL })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE)
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(wall, lp)
        overlay = wall
        overlayPkg = pkg
    }

    private fun hideOverlay() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
        overlayPkg = null
    }

    override fun onInterrupt() = hideOverlay()

    override fun onDestroy() {
        hideOverlay()
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
