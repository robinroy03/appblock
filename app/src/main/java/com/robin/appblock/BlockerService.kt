package com.robin.appblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The enforcer: a foreground service that polls the system's usage-event log
 * once a second (while the screen is on) to learn which app is in front.
 * "Usage access" grants that read; nothing else can tell a non-accessibility
 * app about foreground changes, so this has to keep running, and Android
 * demands a small ongoing notification for that. While a rule-listed app is
 * in the foreground we also tick every few seconds, so a session gets cut
 * off the moment its budget runs out.
 *
 * AppBlock only ever shows that one notification: "on duty" while nothing
 * tracked is in front, swapped for the tracked app's countdown (with the
 * 50%/90% warnings in its text) while one is.
 *
 * The block wall is an overlay window drawn by this service directly over
 * the blocked app ("Display over other apps", TYPE_APPLICATION_OVERLAY) —
 * NOT an Activity. Activities launched from a background service get
 * silently dropped or queued by Android 10+ background-launch restrictions;
 * an overlay appears instantly and reliably.
 */
class BlockerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null   // whatever app is in front, tracked or not
    private var currentPkg: String? = null   // rule-listed app currently in foreground
    private var sessionStart = 0L
    private val tickMs = 5_000L
    private val pollMs = 1_000L
    // The first poll after (re)start looks far back to find the app already
    // in front; every later poll replays just the last couple of seconds
    // (replaying an event twice is harmless: the fold ends in the same state).
    private var lookbackMs = 12 * 3_600_000L
    private var polling = false

    private var overlay: LinearLayout? = null
    private var overlayPkg: String? = null   // which app the wall is covering
    private var timerDeadline: Long? = null  // what the session-timer notification counts down to
    private var timerLevel = 0               // warning level (0/50/90) its text shows
    private var shown: Pair<Int, Notification>? = null  // the one notification up right now

    // No point polling a dark screen: nothing is being used. The screen-off
    // "paused" event ends the session first, then polling stops.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startPolling()
                Intent.ACTION_SCREEN_OFF -> {
                    poll.run()   // one last read picks up the pause event
                    stopPolling()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Clear leftovers from before a restart or update (an old countdown,
        // older versions' per-app warnings): only the notification posted
        // below should be up.
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        // Warnings used to be their own notifications on these channels.
        nm.deleteNotificationChannel("usage")
        nm.deleteNotificationChannel("usage_hi")
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mid-session (e.g. a restart request) keep the countdown up.
        val (id, notif) = shown ?: (NOTIF_SERVICE to onDutyNotification())
        show(id, notif)
        if (getSystemService(PowerManager::class.java).isInteractive) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onDutyNotification(): Notification {
        // MIN importance: no sound, no status-bar icon, just a collapsed line
        // at the bottom of the shade (Android 13+ lets the user swipe it away).
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Blocker running", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("AppBlock is on duty")
            .setContentText("Watching for apps over their allowance.")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /**
     * Make `notif` the service's one notification. Re-calling startForeground
     * with a different id swaps it in and takes the previous one down; the
     * explicit cancel covers the old one in case the system leaves it behind.
     * Posting also brings it back if the user had swiped it away.
     */
    private fun show(id: Int, notif: Notification) {
        val previous = shown?.first
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(id, notif)
            }
        } catch (e: Exception) { return }  // refused: keep whatever is up rather than crash
        shown = id to notif
        if (previous != null && previous != id) {
            getSystemService(NotificationManager::class.java).cancel(previous)
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(poll)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
        endSession()
        foreground = null
    }

    /** Read the usage events since the last poll and react to the app in front. */
    private val poll = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // Usage access revoked while running: the query throws, and with
            // nothing to see there's nothing to enforce. Keep polling, so
            // blocking picks up again by itself once access is back.
            val events = try { readEvents(now - lookbackMs, now) } catch (e: SecurityException) { null }
            foreground = if (events == null) null else Storage.foregroundFrom(events, foreground)
            lookbackMs = 2 * pollMs
            onForeground(foreground)
            if (polling) handler.postDelayed(this, pollMs)
        }
    }

    private fun readEvents(from: Long, to: Long): List<Storage.UsageEvent> {
        val usm = getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, to)
        val out = mutableListOf<Storage.UsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    out += Storage.UsageEvent(resumed = true, pkg = e.packageName)
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    out += Storage.UsageEvent(resumed = false, pkg = e.packageName)
            }
        }
        return out
    }

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
                hideTimer()
                block(pkg, rule)
            } else {
                showTimer(pkg, rule)
                handler.postDelayed(this, tickMs)
            }
        }
    }

    /** `pkg` is the app in front right now (null: nothing, e.g. screen off). */
    private fun onForeground(pkg: String?) {
        // Foreground moved off the walled app -> take the wall down.
        if (overlay != null && pkg != overlayPkg) hideOverlay()

        if (pkg == currentPkg) return
        endSession()

        val rule = Storage.loadRules(this)[pkg ?: return] ?: return
        if (Storage.usedMsInWindow(this, pkg, rule.windowMin) >= rule.allowMin * 60_000L) {
            block(pkg, rule)
        } else {
            // Budget freed up while the wall stayed on screen (e.g. across a
            // screen-off): let them in.
            hideOverlay()
            currentPkg = pkg
            sessionStart = System.currentTimeMillis()
            showTimer(pkg, rule)
            handler.postDelayed(tick, tickMs)
        }
    }

    /**
     * The status-bar countdown of what's left of this app's allowance, like
     * the Clock app's timer, shown in place of the "on duty" notification.
     * The system ticks the chronometer itself, so this is only (re)posted
     * when something actually changes: at session start, whenever old usage
     * ages out of the rolling window and gives time back, and when usage
     * crosses 50%/90% (the warning then replaces the text). Tapping it opens
     * AppBlock, which also stops the clock, since the tracked app leaves the
     * foreground.
     *
     * On Android 16 QPR1+ it asks to be a "Live Update": the system then
     * shows it as a status-bar chip with the countdown ticking in it, and OEM
     * skins put it in their capsule (OxygenOS "Live Alerts"). Older versions
     * ignore the request and just show the notification. Requirements met
     * here: ongoing, has a title, plain style, channel above MIN importance,
     * and no short-text override so the chip shows the chronometer.
     */
    private fun showTimer(pkg: String, rule: Rule) {
        val usedMs = Storage.usedMsInWindow(this, pkg, rule.windowMin)
        val deadline = System.currentTimeMillis() + Storage.remainingMs(usedMs, rule.allowMin)
        val level = Storage.crossedWarnLevel(usedMs, rule.allowMin)
        if (!Storage.timerNeedsRepost(timerDeadline, deadline, timerLevel, level)) return
        timerDeadline = deadline
        timerLevel = level
        // LOW: icon in the status bar, no sound or heads-up.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Session timer", NotificationManager.IMPORTANCE_LOW))
        val text = if (level == 0) "Allowance for the next ${Storage.fmtSpan(rule.windowMin)} window."
            else "You've used ${Storage.usedPct(usedMs, rule.allowMin)}% " +
                "(${Storage.fmtMin(usedMs)} min) of your usage for ${labelFor(pkg)}"
        show(NOTIF_TIMER, Notification.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${labelFor(pkg)}: time left")
            .setContentText(text)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            // Notification.Builder.setRequestPromotedOngoing(true), spelled
            // as the extra it sets so we needn't compile against SDK 36.1.
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .build())
    }

    /** Session over: back to the plain "on duty" notification. */
    private fun hideTimer() {
        if (timerDeadline == null) return
        timerDeadline = null
        timerLevel = 0
        show(NOTIF_SERVICE, onDutyNotification())
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** Foreground moved elsewhere: log the finished session, stop ticking. */
    private fun endSession() {
        handler.removeCallbacks(tick)
        hideTimer()
        val pkg = currentPkg ?: return
        currentPkg = null
        val rule = Storage.loadRules(this)[pkg] ?: return
        Storage.addUsage(this, pkg, sessionStart, System.currentTimeMillis(), rule.windowMin)
    }

    /**
     * Send the user to the launcher. Launching from a service is normally
     * refused on Android 10+, but an app holding "Display over other apps"
     * with a visible overlay is exempt.
     */
    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }

    /** Cover the screen with the block wall. */
    private fun block(pkg: String, rule: Rule) {
        if (overlay != null) return
        // Permission revoked since setup: addView would throw. The home
        // screen shows the missing requirement; nothing to do here.
        if (!Settings.canDrawOverlays(this)) return
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
                text = "$label is blocked.\n\nTry again in ${Storage.fmtSpan(waitMin.toInt())}."
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (night) 0xFFEEEEEE.toInt() else 0xFF111111.toInt())
            })
            addView(Button(context).apply {
                text = "Go to home screen"
                setOnClickListener {
                    goHome()
                    hideOverlay()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 64; gravity = Gravity.CENTER_HORIZONTAL })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE)
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(wall, lp)
        } catch (e: Exception) { return }
        overlay = wall
        overlayPkg = pkg
    }

    private fun hideOverlay() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
        overlayPkg = null
    }

    override fun onDestroy() {
        timerDeadline = null  // going away: don't swap notifications back in on the way out
        hideOverlay()
        stopPolling()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_TIMER = "timer"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_TIMER = 2
        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1)
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        /**
         * (Re)start the blocker. Safe to call repeatedly — a running service
         * just re-posts its notification. Only worth it once every required
         * setting is on; before that it couldn't see or draw anything.
         */
        fun start(ctx: Context) {
            if (!allRequirementsMet(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, BlockerService::class.java))
            } catch (e: Exception) {
                // Background start refused (shouldn't happen from an activity,
                // boot, or package-replaced): the next app open retries.
            }
        }
    }
}
