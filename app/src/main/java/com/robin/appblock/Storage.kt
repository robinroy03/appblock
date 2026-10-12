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
     * How long until the rolling window frees up enough budget to use the app
     * again (0 if it's usable right now), in whole minutes. With no new use
     * after `now`, usage in the window only falls as time passes, so this
     * binary-searches for the first minute it's under the allowance: a few
     * dozen steps even for a window of weeks.
     */
    fun msUntilUnblocked(intervals: List<Pair<Long, Long>>, rule: Rule, now: Long): Long {
        val allowMs = rule.allowMin * 60_000L
        fun freeAfter(min: Long) = usedMs(intervals, rule.windowMin, now + min * 60_000L) < allowMs
        var lo = 0L
        var hi = rule.windowMin.toLong()
        if (!freeAfter(hi)) return hi * 60_000L
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (freeAfter(mid)) hi = mid else lo = mid + 1
        }
        return lo * 60_000L
    }

    /** Whole minutes for display, any partial minute rounding up. */
    fun ceilMin(ms: Long): Long = (ms + 59_999) / 60_000

    /** Allowance left right now, never negative. */
    fun remainingMs(usedMs: Long, allowMin: Int): Long =
        maxOf(0L, allowMin * 60_000L - usedMs)

    /**
     * Whether the session-timer notification needs re-posting for a freshly
     * computed deadline and warning level. The deadline is recomputed every
     * tick and jitters by a few ms; it only truly moves when old usage ages
     * out of the rolling window and hands time back. Re-posting for jitter
     * would make the countdown flicker. The 50%/90% warnings live in the
     * timer's text, so crossing a threshold (either way) re-posts too.
     */
    fun timerNeedsRepost(shownDeadline: Long?, deadline: Long, shownLevel: Int, level: Int): Boolean =
        shownDeadline == null || Math.abs(deadline - shownDeadline) >= 1_000 || level != shownLevel

    /** One "activity resumed"/"activity paused" entry from the system usage-event log. */
    data class UsageEvent(val resumed: Boolean, val pkg: String)

    /**
     * Which app is in front after `events` (in time order), starting from
     * `previous`. A resume puts its app in front. A pause only clears the
     * front app if it's for that same app — on an app switch Android logs
     * the old app's pause and the new app's resume in either order, and a
     * pause of the app that just lost the foreground must not blank out the
     * one that gained it. A pause with no resume after it (screen off, lock
     * screen) leaves nothing in front.
     */
    fun foregroundFrom(events: List<UsageEvent>, previous: String?): String? {
        var fg = previous
        for (e in events) {
            if (e.resumed) fg = e.pkg
            else if (fg == e.pkg) fg = null
        }
        return fg
    }

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
    enum class HomeList { CARDS, EMPTY_HINT, PAUSED_NOTE }

    /**
     * CARDS: every required setting on, apps blocked. EMPTY_HINT ("no apps
     * yet"): nothing blocked — on a fresh install the required-setup
     * explanations live under the home-screen buttons, so the list area
     * needs no setup hint of its own. PAUSED_NOTE: a requirement is off but
     * rules exist — they're kept, blocking just isn't enforced.
     */
    fun homeList(blockingOn: Boolean, ruleCount: Int): HomeList = when {
        ruleCount == 0 -> HomeList.EMPTY_HINT
        blockingOn -> HomeList.CARDS
        else -> HomeList.PAUSED_NOTE
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
     * It follows usage back DOWN as old intervals age out of the rolling
     * window, so the timer's warning text does too.
     */
    fun crossedWarnLevel(usedMs: Long, allowMin: Int): Int {
        if (allowMin <= 0) return 0
        val pct = usedMs * 100 / (allowMin * 60_000L)
        return WARN_THRESHOLDS.lastOrNull { it <= pct } ?: 0
    }

    /**
     * Everything AppBlock can't work without, as data. Each entry carries its
     * own user-facing copy, and every surface generates itself from this
     * list: the home screen's "(required)" buttons with their why-blurbs, the
     * "+ Block an app" guard popup, the About permissions rows. Declaration
     * order is fix-first priority (seeing the foreground app before drawing
     * over it — the core mechanism first). A future requirement is one entry
     * here plus a branch in requirementMet() (Perms.kt) and MainActivity's
     * requirementFix(); those `when`s are exhaustive, so forgetting one is a
     * compile error, not a stale screen.
     */
    enum class Requirement(
        val title: String,     // row title in the About permissions dialog
        val button: String,    // home-screen button label
        val whyWord: String,   // "Why <word>?" heading on the home-screen blurb
        val why: String,       // friendly explanation: home blurb + guard popup
        val permsNote: String, // one-liner under the About permissions row
    ) {
        USAGE_ACCESS(
            "Usage access",
            "Allow usage access (required)",
            "usage access",
            "That's how AppBlock sees which app you're using, so it knows " +
                "when your time in one is up. It also shows your screen " +
                "time next to each app in the picker.",
            "Required. Sees which app is open."),
        OVERLAY(
            "Display over other apps",
            "Allow display over other apps (required)",
            "display over other apps",
            "The block wall is drawn on top of the app you've run out of " +
                "time in. Without this, AppBlock can see you're over budget " +
                "but can't show you the wall.",
            "Required. Draws the block wall over the blocked app."),
        BATTERY(
            "Unrestricted battery",
            "Allow background battery use (required)",
            "battery",
            "Your phone's battery saver puts background apps to sleep, and " +
                "a sleeping blocker can't block. This lets AppBlock stay " +
                "awake, at a tiny battery cost. So if your phone ever " +
                "complains that AppBlock is running in the background, " +
                "that's not a bug. That's the watchman staying on duty.",
            "Required. Stops the phone's battery saver from killing the blocker."),
    }

    /**
     * Body of the "+ Block an app" guard popup, composed on the fly: one
     * segment per missing requirement, so the popup always matches exactly
     * what's off — one requirement or several, always a single popup.
     */
    fun requirementsMessage(missing: List<Requirement>): String =
        missing.joinToString("\n\n") { "${it.title}: ${it.why}" } +
            "\n\nThe buttons on the home screen will get you set up."

    /**
     * A span in words, for the home list, the limit dialog and the block
     * wall: "45 min", "1 hr", "2 hrs", "1 hr 30 min", "7 days", "1 day 6 hrs".
     */
    fun fmtSpan(min: Int): String {
        val d = min / 1440
        val h = min / 60 % 24
        val m = min % 60
        val parts = listOfNotNull(
            if (d == 0) null else if (d == 1) "1 day" else "$d days",
            if (h == 0) null else if (h == 1) "1 hr" else "$h hrs",
            if (m == 0) null else "$m min")
        return if (parts.isEmpty()) "0 min" else parts.joinToString(" ")
    }

    /** Today's screen time under an app's name on the home list. */
    fun fmtToday(ms: Long): String = when {
        ms == 0L -> "Not used today"
        ms < 60_000 -> "Less than a minute today"
        else -> fmtSpan((ms / 60_000).toInt()) + " today"
    }

    // ---- the limit sheet: "allow X in any Y", each a number and a unit ----

    /** Units the limit sheet offers; the number beside a unit is what you type or step. */
    enum class SpanUnit(val minutes: Int, val label: String) {
        MIN(1, "min"), HR(60, "hr"), DAY(1440, "day")
    }

    /** One-tap starting points. The sheet highlights whichever one the fields currently match. */
    val PRESETS = listOf(Rule(5, 120), Rule(10, 60), Rule(15, 120), Rule(30, 240), Rule(60, 1440))

    /** Typed numbers stop here: far past a week in any unit, and small enough never to overflow an Int of minutes. */
    private const val MAX_AMOUNT = 99_999

    /**
     * Whether a rule can actually limit anything: some allowance, shorter
     * than its window (allowance >= window would never block). Gates the
     * limit sheet's OK button.
     */
    fun validRule(rule: Rule): Boolean =
        rule.allowMin >= 1 && rule.allowMin < rule.windowMin

    /** The largest of `units` that divides `minutes` evenly: 120 -> HR, 90 -> MIN, 1440 -> DAY. */
    fun unitFor(minutes: Int, units: List<SpanUnit>): SpanUnit =
        units.lastOrNull { minutes % it.minutes == 0 } ?: units.first()

    /** A typed number: digits only, 1 up to the cap. Null while blank or nonsense. */
    fun parseAmount(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_AMOUNT }

    /**
     * The rule in the limit sheet's two fields. Null until both hold numbers
     * and the rule is valid (which is what keeps OK disabled).
     */
    fun limitRule(allow: String, allowUnit: SpanUnit, window: String, windowUnit: SpanUnit): Rule? {
        val a = parseAmount(allow) ?: return null
        val w = parseAmount(window) ?: return null
        return Rule(a * allowUnit.minutes, w * windowUnit.minutes).takeIf(::validRule)
    }

    /**
     * What's wrong with the fields, in words, or null. Only judges once both
     * are numbers, so half-typed input isn't flagged.
     */
    fun limitError(allow: String, allowUnit: SpanUnit, window: String, windowUnit: SpanUnit): String? {
        val a = parseAmount(allow) ?: return null
        val w = parseAmount(window) ?: return null
        return if (a * allowUnit.minutes >= w * windowUnit.minutes)
            "The allowance has to be shorter than the window." else null
    }

    /**
     * One press of a stepper button. Small minute counts move by 1; from 10
     * up, minutes move in fives and land on multiples of five (12 -> 15,
     * 12 down -> 10). Hours and days always move by 1. Never below 1.
     */
    fun stepAmount(value: Int, unit: SpanUnit, up: Boolean): Int = when {
        up -> if (unit == SpanUnit.MIN && value >= 10) (value / 5 + 1) * 5 else value + 1
        else -> if (unit == SpanUnit.MIN && value > 10) (value - 1) / 5 * 5 else value - 1
    }.coerceIn(1, MAX_AMOUNT)

    /**
     * Minutes to show as "N min used" on a home-list card: partial minutes round up
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

    // User tapped ✕ on the "notifications are off" reminder: never show it again.
    fun notifReminderDismissed(ctx: Context) =
        prefs(ctx).getBoolean("notifReminderDismissed", false)

    fun setNotifReminderDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("notifReminderDismissed", true).apply()
    }

    // Same, for the app picker's "grant usage access" hint.
    fun usageReminderDismissed(ctx: Context) =
        prefs(ctx).getBoolean("usageReminderDismissed", false)

    fun setUsageReminderDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("usageReminderDismissed", true).apply()
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

    /**
     * The usage log after recording [start, end]: entries that ended before
     * `cutoff` dropped, and the new one merged into the last when it picks up
     * where that one stopped. The service flushes a running session every
     * few seconds, so without merging a week-long window would hold
     * thousands of entries.
     */
    fun appendUsage(log: List<Pair<Long, Long>>, start: Long, end: Long, cutoff: Long):
        List<Pair<Long, Long>> {
        val kept = log.filter { it.second > cutoff }.toMutableList()
        val last = kept.lastOrNull()
        if (last != null && start <= last.second) kept[kept.lastIndex] = last.first to maxOf(last.second, end)
        else kept += start to end
        return kept
    }

    /** Record that `pkg` was used from `start` to `end`, dropping entries too old to matter. */
    fun addUsage(ctx: Context, pkg: String, start: Long, end: Long, windowMin: Int) {
        if (end <= start) return
        val all = loadUsage(ctx)
        val cutoff = System.currentTimeMillis() - windowMin * 60_000L
        val log = appendUsage(loadIntervals(ctx, pkg), start, end, cutoff)
        all.put(pkg, JSONArray().apply {
            for ((s, e) in log) put(JSONArray().put(s).put(e))
        })
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
    }

    // Convenience wrappers joining persistence with the pure math above.

    fun usedMsInWindow(ctx: Context, pkg: String, windowMin: Int): Long =
        usedMs(loadIntervals(ctx, pkg), windowMin, System.currentTimeMillis())

    fun msUntilUnblocked(ctx: Context, pkg: String, rule: Rule): Long =
        msUntilUnblocked(loadIntervals(ctx, pkg), rule, System.currentTimeMillis())
}
