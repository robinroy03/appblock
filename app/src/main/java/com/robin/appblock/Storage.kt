package com.robin.appblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// A blocking rule: "allow `allowMin` minutes of use per rolling `windowMin` minutes".
data class Rule(val allowMin: Int, val windowMin: Int)

object Storage {

    // ---- rolling-window math (pure functions, unit-tested in StorageTest) ----

    /** Milliseconds of usage falling inside the window of `windowMin` minutes ending at `now`. */
    fun usedMs(intervals: List<Pair<Long, Long>>, windowMin: Int, now: Long): Long {
        val windowStart = now - windowMin * 60_000L
        var used = 0L
        for ((start, end) in intervals) {
            val s = maxOf(start, windowStart)
            val e = minOf(end, now)
            if (e > s) used += e - s
        }
        return used
    }

    /**
     * How long until the rolling window frees up enough budget to use the app again
     * (0 if it's usable right now). Walks forward minute by minute until
     * usage-in-window drops below the allowance.
     */
    fun msUntilUnblocked(intervals: List<Pair<Long, Long>>, rule: Rule, now: Long): Long {
        val allowMs = rule.allowMin * 60_000L
        var t = now
        while (t <= now + rule.windowMin * 60_000L) {
            if (usedMs(intervals, rule.windowMin, t) < allowMs) return t - now
            t += 60_000L
        }
        return rule.windowMin * 60_000L
    }

    /** Whole minutes for display, any partial minute rounding up. */
    fun ceilMin(ms: Long): Long = (ms + 59_999) / 60_000

    // Usage-warning thresholds, as percent of the allowance.
    private val WARN_THRESHOLDS = listOf(50, 90)

    /**
     * Minutes for the warning notification, one decimal, truncated ("2.5",
     * "3") so it never contradicts the (also truncated) percent beside it.
     */
    fun fmtMin(ms: Long): String {
        val tenths = ms / 6_000
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    /** What the home screen's list area shows. */
    enum class HomeList { CARDS, EMPTY_HINT, PAUSED_NOTE, SETUP_HINT }

    /**
     * CARDS: service on, apps blocked. EMPTY_HINT ("no apps yet"): service on,
     * nothing blocked. PAUSED_NOTE: service off but rules exist — they're kept,
     * blocking just isn't enforced. SETUP_HINT: fresh state, service off and no
     * rules — explain why the service is required.
     */
    fun homeList(serviceOn: Boolean, ruleCount: Int): HomeList = when {
        serviceOn && ruleCount == 0 -> HomeList.EMPTY_HINT
        serviceOn -> HomeList.CARDS
        ruleCount > 0 -> HomeList.PAUSED_NOTE
        else -> HomeList.SETUP_HINT
    }

    /** Screen-time durations for the app picker: "0m", "45m", "2h", "2h 12m". */
    fun fmtDuration(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Percent of the allowance used, for display, capped at 100. */
    fun usedPct(usedMs: Long, allowMin: Int): Int =
        if (allowMin <= 0) 100
        else minOf(100L, usedMs * 100 / (allowMin * 60_000L)).toInt()

    /**
     * Highest warning threshold (50/90) that current usage has reached, or 0.
     * The caller notifies when this rises above the previously stored level and
     * then stores it; because the stored level follows usage back DOWN as old
     * intervals age out of the rolling window, each threshold re-notifies on
     * the next climb past it.
     */
    fun crossedWarnLevel(usedMs: Long, allowMin: Int): Int {
        if (allowMin <= 0) return 0
        val pct = usedMs * 100 / (allowMin * 60_000L)
        return WARN_THRESHOLDS.lastOrNull { it <= pct } ?: 0
    }

    /**
     * Whether tapping an app's usage warning should send the user home: only
     * when that app is still the one in the foreground, so backgrounding it
     * actually stops the usage clock. Tapped after the user has already moved
     * on, the notification just dismisses rather than interrupting them.
     */
    fun tapGoesHome(notifPkg: String?, foregroundPkg: String?): Boolean =
        notifPkg != null && notifPkg == foregroundPkg

    /**
     * Minutes to show in the "X/Y min used" pill: partial minutes round up
     * (any use shows at least 1), capped at the allowance so slight overshoot
     * never displays as "6/5".
     */
    fun displayedUsedMin(usedMs: Long, allowMin: Int): Long =
        minOf(ceilMin(usedMs), allowMin.toLong())

    // ---- persistence: two JSON blobs in SharedPreferences ----
    //
    //   rules: {"com.instagram.android": {"allow": 5, "window": 120}, ...}
    //   usage: {"com.instagram.android": [[startMs, endMs], ...], ...}

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("appblock", Context.MODE_PRIVATE)

    fun onboardingSeen(ctx: Context) = prefs(ctx).getBoolean("onboarded", false)

    // The one-time system notification prompt fires on the first home-screen
    // visit AFTER onboarding, so a brand-new user isn't greeted with a popup.
    fun notifPromptAsked(ctx: Context) = prefs(ctx).getBoolean("notifPromptAsked", false)

    fun setNotifPromptAsked(ctx: Context) {
        prefs(ctx).edit().putBoolean("notifPromptAsked", true).apply()
    }

    // User tapped ✕ on the "notifications are off" reminder: never show it again.
    fun notifReminderDismissed(ctx: Context) =
        prefs(ctx).getBoolean("notifReminderDismissed", false)

    fun setNotifReminderDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("notifReminderDismissed", true).apply()
    }

    fun setOnboardingSeen(ctx: Context) {
        prefs(ctx).edit().putBoolean("onboarded", true).apply()
    }

    fun loadRules(ctx: Context): Map<String, Rule> {
        val json = JSONObject(prefs(ctx).getString("rules", "{}")!!)
        val out = mutableMapOf<String, Rule>()
        for (pkg in json.keys()) {
            val r = json.getJSONObject(pkg)
            out[pkg] = Rule(r.getInt("allow"), r.getInt("window"))
        }
        return out
    }

    fun saveRules(ctx: Context, rules: Map<String, Rule>) {
        val json = JSONObject()
        for ((pkg, r) in rules) {
            json.put(pkg, JSONObject().put("allow", r.allowMin).put("window", r.windowMin))
        }
        prefs(ctx).edit().putString("rules", json.toString()).apply()
    }

    private fun loadUsage(ctx: Context) =
        JSONObject(prefs(ctx).getString("usage", "{}")!!)

    /** The recorded usage intervals for `pkg`, as [start, end] millisecond pairs. */
    fun loadIntervals(ctx: Context, pkg: String): List<Pair<Long, Long>> {
        val arr = loadUsage(ctx).optJSONArray(pkg) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val iv = arr.getJSONArray(i)
            iv.getLong(0) to iv.getLong(1)
        }
    }

    /** Record that `pkg` was used from `start` to `end`, dropping entries too old to matter. */
    fun addUsage(ctx: Context, pkg: String, start: Long, end: Long, windowMin: Int) {
        if (end <= start) return
        val all = loadUsage(ctx)
        val old = all.optJSONArray(pkg) ?: JSONArray()
        val cutoff = System.currentTimeMillis() - windowMin * 60_000L
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val iv = old.getJSONArray(i)
            if (iv.getLong(1) > cutoff) kept.put(iv)
        }
        kept.put(JSONArray().put(start).put(end))
        all.put(pkg, kept)
        prefs(ctx).edit().putString("usage", all.toString()).apply()
    }

    /** Remove an app's rule AND its usage log, so nothing orphaned stays behind. */
    fun removeApp(ctx: Context, pkg: String) {
        val rules = loadRules(ctx).toMutableMap()
        rules.remove(pkg)
        saveRules(ctx, rules)
        val usage = loadUsage(ctx)
        usage.remove(pkg)
        prefs(ctx).edit().putString("usage", usage.toString()).apply()
        setWarnLevel(ctx, pkg, 0)
    }

    // Last warning level notified per app: {"com.instagram.android": 50, ...}

    fun warnLevel(ctx: Context, pkg: String): Int =
        JSONObject(prefs(ctx).getString("notified", "{}")!!).optInt(pkg, 0)

    fun setWarnLevel(ctx: Context, pkg: String, level: Int) {
        val json = JSONObject(prefs(ctx).getString("notified", "{}")!!)
        if (level == 0) json.remove(pkg) else json.put(pkg, level)
        prefs(ctx).edit().putString("notified", json.toString()).apply()
    }

    // Convenience wrappers joining persistence with the pure math above.

    fun usedMsInWindow(ctx: Context, pkg: String, windowMin: Int): Long =
        usedMs(loadIntervals(ctx, pkg), windowMin, System.currentTimeMillis())

    fun msUntilUnblocked(ctx: Context, pkg: String, rule: Rule): Long =
        msUntilUnblocked(loadIntervals(ctx, pkg), rule, System.currentTimeMillis())
}
