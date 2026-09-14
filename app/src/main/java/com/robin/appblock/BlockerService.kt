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

    // Tapping a usage warning backgrounds the app it's about. We can't
    // force-kill anything, but going home is what stops the usage clock (same
    // as the block wall's button). Tapped from anywhere else the notification
    // just dismisses, so a stale warning can't yank the user out of whatever
    // they've since moved on to.
    private val goHomeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Storage.tapGoesHome(intent.getStringExtra(EXTRA_PKG), currentPkg)) goHome()
        }
    }

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
        val goHome = IntentFilter(ACTION_GO_HOME)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(goHomeReceiver, goHome, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(goHomeReceiver, goHome)
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        // MIN importance: no sound, no status-bar icon, just a collapsed line
        // at the bottom of the shade (Android 13+ lets the user swipe it away).
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SERVICE, "Blocker running", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("AppBlock is on duty")
            .setContentText("Watching for apps over their allowance.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_SERVICE, notif)
        }
        if (getSystemService(PowerManager::class.java).isInteractive) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            foreground = Storage.foregroundFrom(readEvents(now - lookbackMs, now), foreground)
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
                block(pkg, rule)
            } else {
                maybeWarn(pkg, rule)
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
            val min = Storage.fmtMin(usedMs)
            val nm = getSystemService(NotificationManager::class.java)
            // HIGH importance so the warning pops up over the app it's about.
            // Channel importance is locked in at creation, so this is a fresh
            // channel id ("usage" shipped as DEFAULT); drop the old one.
            nm.deleteNotificationChannel("usage")
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_USAGE, "Usage warnings", NotificationManager.IMPORTANCE_HIGH))
            // One PendingIntent per app: distinct request codes keep each
            // notification's package extra its own.
            val goHome = PendingIntent.getBroadcast(this, pkg.hashCode(),
                Intent(ACTION_GO_HOME).setPackage(packageName).putExtra(EXTRA_PKG, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(pkg.hashCode(), Notification.Builder(this, CHANNEL_USAGE)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("AppBlock")
                .setContentText("You've used $pct% ($min min) of your usage for ${labelFor(pkg)}")
                .setAutoCancel(true)
                .setContentIntent(goHome)
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

    /**
     * Send the user to the launcher. Launching from a service is normally
     * refused on Android 10+, but an app holding "Display over other apps"
     * with a visible overlay is exempt, and so is a notification tap.
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
                text = "$label is blocked.\n\nTry again in $waitMin min."
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
        hideOverlay()
        stopPolling()
        try { unregisterReceiver(goHomeReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val ACTION_GO_HOME = "com.robin.appblock.action.GO_HOME"
        private const val EXTRA_PKG = "pkg"
        private const val CHANNEL_USAGE = "usage_hi"
        private const val CHANNEL_SERVICE = "service"
        private const val NOTIF_SERVICE = 1

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
