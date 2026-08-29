package com.robin.appblock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * The enforcer. Android calls onAccessibilityEvent() whenever the foreground
 * window changes (app opened, app switched, etc). While a rule-listed app is
 * in the foreground we also tick every few seconds, so a session gets cut off
 * the moment its budget runs out.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null   // rule-listed app currently in foreground
    private var sessionStart = 0L
    private val tickMs = 5_000L

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
                handler.postDelayed(this, tickMs)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == currentPkg) return

        endSession()

        val rule = Storage.loadRules(this)[pkg] ?: return
        if (Storage.usedMsInWindow(this, pkg, rule.windowMin) >= rule.allowMin * 60_000L) {
            block(pkg, rule)
        } else {
            currentPkg = pkg
            sessionStart = System.currentTimeMillis()
            handler.postDelayed(tick, tickMs)
        }
    }

    /** Foreground moved elsewhere: log the finished session, stop ticking. */
    private fun endSession() {
        handler.removeCallbacks(tick)
        val pkg = currentPkg ?: return
        currentPkg = null
        val rule = Storage.loadRules(this)[pkg] ?: return
        Storage.addUsage(this, pkg, sessionStart, System.currentTimeMillis(), rule.windowMin)
    }

    private fun block(pkg: String, rule: Rule) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("pkg", pkg)
            putExtra("waitMs", Storage.msUntilUnblocked(this@BlockerService, pkg, rule))
        })
    }

    override fun onInterrupt() {}
}
