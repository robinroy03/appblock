package com.robin.appblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rolling-window budget math. Times are built around a fixed
 * `now`; `now - 30.min` reads as "30 minutes ago".
 */
class StorageTest {

    private val Int.min get() = this * 60_000L
    private val now = 1_000_000_000_000L
    private val rule = Rule(allowMin = 5, windowMin = 120)

    @Test
    fun `no usage - nothing counted, no wait`() {
        assertEquals(0L, Storage.usedMs(emptyList(), rule.windowMin, now))
        assertEquals(0L, Storage.msUntilUnblocked(emptyList(), rule, now))
    }

    @Test
    fun `usage inside the window is summed`() {
        val intervals = listOf(
            (now - 30.min) to (now - 27.min),  // 3 min
            (now - 10.min) to (now - 9.min),   // 1 min
        )
        assertEquals(4.min, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `usage older than the window is ignored`() {
        val intervals = listOf((now - 200.min) to (now - 190.min))
        assertEquals(0L, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `usage straddling the window edge is clipped`() {
        // 10-minute session, but only its last 5 minutes fall inside the 120-min window.
        val intervals = listOf((now - 125.min) to (now - 115.min))
        assertEquals(5.min, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `under budget - usable immediately`() {
        val intervals = listOf((now - 4.min) to now)  // 4 of 5 allowed minutes used
        assertEquals(0L, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `budget spent in one burst just now - wait until it starts aging out`() {
        // Used all 5 minutes ending right now. The burst leaves the 120-min window
        // starting at now+115min; one minute later enough has aged out.
        val intervals = listOf((now - 5.min) to now)
        assertEquals(116.min, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `budget spent in two chunks - oldest chunk aging out unblocks sooner`() {
        val intervals = listOf(
            (now - 119.min) to (now - 116.min),  // 3 min, almost aged out
            (now - 2.min) to now,                // 2 min, fresh
        )
        // Blocked at `now` (3+2 = 5 min used). Two minutes later the old chunk has
        // partially left the window (only 2 of its 3 min remain), freeing budget.
        assertEquals(5.min, Storage.usedMs(intervals, rule.windowMin, now))
        assertEquals(2.min, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `ceilMin - partial minutes round up`() {
        assertEquals(0L, Storage.ceilMin(0))
        assertEquals(1L, Storage.ceilMin(1))
        assertEquals(1L, Storage.ceilMin(60_000))
        assertEquals(2L, Storage.ceilMin(60_001))
    }

    @Test
    fun `minutes used - zero use shows zero`() {
        assertEquals(0L, Storage.displayedUsedMin(0, 5))
    }

    @Test
    fun `minutes used - partial minutes round up`() {
        assertEquals(1L, Storage.displayedUsedMin(1_000, 5))       // 1s -> "1"
        assertEquals(1L, Storage.displayedUsedMin(60_000, 5))      // exactly 1 min
        assertEquals(2L, Storage.displayedUsedMin(60_001, 5))      // just over 1 min
    }

    @Test
    fun `minutes used - display capped at the allowance`() {
        assertEquals(5L, Storage.displayedUsedMin(9.min, 5))       // overshoot -> "5/5"
    }

    @Test
    fun `warn level - 0 below 50, then 50, then 90`() {
        // 5-min allowance: 50% = 2.5 min, 90% = 4.5 min.
        assertEquals(0, Storage.crossedWarnLevel(0L, 5))
        assertEquals(0, Storage.crossedWarnLevel(149_999, 5))      // 49.99%
        assertEquals(50, Storage.crossedWarnLevel(150_000, 5))     // exactly 50%
        assertEquals(50, Storage.crossedWarnLevel(4.min, 5))       // 80%
        assertEquals(90, Storage.crossedWarnLevel(270_000, 5))     // exactly 90%
        assertEquals(90, Storage.crossedWarnLevel(9.min, 5))       // way over
    }

    @Test
    fun `warn level - zero allowance never divides by zero`() {
        assertEquals(0, Storage.crossedWarnLevel(1.min, 0))
    }

    @Test
    fun `warning minutes - one truncated decimal, matching the percent`() {
        assertEquals("2.5", Storage.fmtMin(150_000))   // 50% of 5 min
        assertEquals("2.5", Storage.fmtMin(155_999))   // truncates, like the pct
        assertEquals("2.6", Storage.fmtMin(156_000))
        assertEquals("3", Storage.fmtMin(3.min))       // whole -> no decimal
        assertEquals("0", Storage.fmtMin(0))
    }

    @Test
    fun `home list - three states`() {
        // Nothing blocked -> the empty hint, service on or off: on a fresh
        // install the setup explanations live under the required buttons.
        assertEquals(Storage.HomeList.EMPTY_HINT, Storage.homeList(false, 0))
        assertEquals(Storage.HomeList.EMPTY_HINT, Storage.homeList(true, 0))
        // Service off with rules -> "blocking is paused" note, rules kept.
        assertEquals(Storage.HomeList.PAUSED_NOTE, Storage.homeList(false, 3))
        // Normal operation -> the app cards.
        assertEquals(Storage.HomeList.CARDS, Storage.homeList(true, 3))
    }

    @Test
    fun `block-an-app guard - one popup naming every missing requirement`() {
        // All missing: each requirement contributes its own named segment,
        // in declaration order (= fix-first priority: see the app, then
        // draw over it, then stay alive)...
        val all = Storage.requirementsMessage(Storage.Requirement.entries)
        assertTrue(all.contains("Usage access"))
        assertTrue(all.contains("Display over other apps"))
        assertTrue(all.contains("Unrestricted battery"))
        assertTrue(all.indexOf("Usage access") < all.indexOf("Display over other apps"))
        assertTrue(all.indexOf("Display over other apps") < all.indexOf("Unrestricted battery"))
        // ...and the fix is pointed at the home-screen buttons, not a
        // settings shortcut (the popup has only an OK button).
        assertTrue(all.contains("home screen"))
        // Only battery missing -> the others aren't mentioned.
        val one = Storage.requirementsMessage(listOf(Storage.Requirement.BATTERY))
        assertTrue(one.contains("Unrestricted battery"))
        assertFalse(one.contains("Usage access"))
        assertFalse(one.contains("Display over other apps"))
    }

    @Test
    fun `remaining - allowance minus use, floored at zero`() {
        assertEquals(5.min, Storage.remainingMs(0L, 5))
        assertEquals(2.min, Storage.remainingMs(3.min, 5))
        assertEquals(0L, Storage.remainingMs(5.min, 5))
        assertEquals(0L, Storage.remainingMs(9.min, 5))       // overshoot
        assertEquals(0L, Storage.remainingMs(0L, 0))
    }

    @Test
    fun `session timer - reposts for a real move, not for jitter`() {
        // Nothing shown yet -> post.
        assertTrue(Storage.timerNeedsRepost(null, now, 0, 0))
        // Same deadline, or a few ms of tick jitter -> leave it alone.
        assertFalse(Storage.timerNeedsRepost(now, now, 0, 0))
        assertFalse(Storage.timerNeedsRepost(now, now + 999, 50, 50))
        assertFalse(Storage.timerNeedsRepost(now, now - 999, 0, 0))
        // Old usage aged out of the window and handed a minute back -> repost.
        assertTrue(Storage.timerNeedsRepost(now, now + 1.min, 0, 0))
        assertTrue(Storage.timerNeedsRepost(now, now - 1_000, 0, 0))
    }

    @Test
    fun `session timer - reposts when a warning threshold is crossed either way`() {
        // Usage climbed past 50%, then 90% -> the text gains the warning.
        assertTrue(Storage.timerNeedsRepost(now, now, 0, 50))
        assertTrue(Storage.timerNeedsRepost(now, now, 50, 90))
        // Old usage aged out and dropped it back below 50% -> warning goes.
        assertTrue(Storage.timerNeedsRepost(now, now + 1.min, 50, 0))
    }

    // ---- foreground app from the usage-event log ----

    private fun resumed(pkg: String) = Storage.UsageEvent(resumed = true, pkg = pkg)
    private fun paused(pkg: String) = Storage.UsageEvent(resumed = false, pkg = pkg)
    private val insta = "com.instagram.android"
    private val launcher = "com.android.launcher3"

    @Test
    fun `foreground - no events keeps whatever was in front`() {
        assertEquals(null, Storage.foregroundFrom(emptyList(), null))
        assertEquals(insta, Storage.foregroundFrom(emptyList(), insta))
    }

    @Test
    fun `foreground - a resume puts that app in front`() {
        assertEquals(insta, Storage.foregroundFrom(listOf(resumed(insta)), null))
        assertEquals(insta, Storage.foregroundFrom(listOf(resumed(insta)), launcher))
    }

    @Test
    fun `foreground - app switch, whichever order Android logs the pair`() {
        // Old app pauses, new app resumes: the common order.
        assertEquals(insta,
            Storage.foregroundFrom(listOf(paused(launcher), resumed(insta)), launcher))
        // New app resumes first, then the old one pauses: the stale pause
        // must not blank out the app that just took over.
        assertEquals(insta,
            Storage.foregroundFrom(listOf(resumed(insta), paused(launcher)), launcher))
    }

    @Test
    fun `foreground - pause with nothing after it means nothing in front`() {
        // Screen off / lock screen: the app pauses and nothing resumes.
        assertEquals(null, Storage.foregroundFrom(listOf(paused(insta)), insta))
        assertEquals(null, Storage.foregroundFrom(listOf(resumed(insta), paused(insta)), null))
    }

    @Test
    fun `foreground - replaying the same events is harmless`() {
        // Polls overlap a little, so events get folded more than once.
        val batch = listOf(paused(launcher), resumed(insta))
        val once = Storage.foregroundFrom(batch, launcher)
        assertEquals(insta, Storage.foregroundFrom(batch, once))
    }

    @Test
    fun `foreground - within-app screen changes stay on the same app`() {
        // Moving between an app's own activities logs pause+resume of the
        // same package; the app never leaves the foreground.
        assertEquals(insta,
            Storage.foregroundFrom(listOf(paused(insta), resumed(insta)), insta))
    }

    @Test
    fun `picker durations - minutes, whole hours, mixed`() {
        assertEquals("0m", Storage.fmtDuration(0))
        assertEquals("0m", Storage.fmtDuration(59_999))
        assertEquals("1m", Storage.fmtDuration(60_000))
        assertEquals("45m", Storage.fmtDuration(45.min))
        assertEquals("1h", Storage.fmtDuration(60.min))
        assertEquals("1h 1m", Storage.fmtDuration(61.min))
        assertEquals("2h 12m", Storage.fmtDuration(132.min + 30_000))
    }

    @Test
    fun `rule spans - minutes, hours, days, mixed`() {
        assertEquals("5 min", Storage.fmtSpan(5))
        assertEquals("45 min", Storage.fmtSpan(45))
        assertEquals("1 hr", Storage.fmtSpan(60))
        assertEquals("1 hr 30 min", Storage.fmtSpan(90))
        assertEquals("2 hrs", Storage.fmtSpan(120))
        assertEquals("2 hrs 5 min", Storage.fmtSpan(125))
        assertEquals("1 day", Storage.fmtSpan(1440))
        assertEquals("1 day 6 hrs", Storage.fmtSpan(1800))
        assertEquals("7 days", Storage.fmtSpan(10080))
        assertEquals("2 days 30 min", Storage.fmtSpan(2910))
    }

    @Test
    fun `today's screen time - unused, under a minute, then whole minutes`() {
        assertEquals("Not used today", Storage.fmtToday(0))
        assertEquals("Less than a minute today", Storage.fmtToday(59_999))
        assertEquals("1 min today", Storage.fmtToday(60_000))
        assertEquals("1 hr 7 min today", Storage.fmtToday(67.min + 59_000))   // truncates
    }

    @Test
    fun `wheel choices - a standard value keeps the standard stops`() {
        assertEquals(Storage.ALLOW_CHOICES, Storage.wheelChoices(Storage.ALLOW_CHOICES, 5))
    }

    @Test
    fun `wheel choices - an odd old value is slotted in order`() {
        assertEquals(listOf(1, 5, 7, 10), Storage.wheelChoices(listOf(1, 5, 10), 7))
        assertEquals(listOf(15, 30, 2000), Storage.wheelChoices(listOf(15, 30), 2000))
    }

    @Test
    fun `valid rule - some allowance, shorter than the window`() {
        assertTrue(Storage.validRule(Rule(5, 120)))
        assertTrue(Storage.validRule(Rule(360, 10080)))    // 6 hrs in any week
        assertFalse(Storage.validRule(Rule(0, 120)))       // no allowance
        assertFalse(Storage.validRule(Rule(30, 30)))       // never blocks
        assertFalse(Storage.validRule(Rule(60, 15)))
    }

    @Test
    fun `exact rule - hours and minutes add up, blanks count as zero`() {
        assertEquals(Rule(6, 94), Storage.exactRule("6", "1", "34"))
        assertEquals(Rule(6, 94), Storage.exactRule(" 6 ", "", "94"))
        assertEquals(Rule(5, 120), Storage.exactRule("5", "2", ""))
        assertEquals(Rule(360, 10080), Storage.exactRule("360", "168", "0"))   // 6 hrs in any week
    }

    @Test
    fun `exact rule - nonsense is rejected`() {
        assertEquals(null, Storage.exactRule("", "2", "0"))      // no allowance
        assertEquals(null, Storage.exactRule("0", "2", "0"))
        assertEquals(null, Storage.exactRule("120", "2", "0"))   // allowance = window
        assertEquals(null, Storage.exactRule("5", "", ""))       // no window
        assertEquals(null, Storage.exactRule("5", "99999999999", "0"))   // too big to store
    }

    @Test
    fun `usage log - a flush continuing the last entry extends it`() {
        val log = listOf((now - 10.min) to (now - 5.min))
        // The service flushes a running session every few seconds: one entry, not many.
        assertEquals(listOf((now - 10.min) to now),
            Storage.appendUsage(log, now - 5.min, now, cutoff = now - 120.min))
    }

    @Test
    fun `usage log - a separate session is a new entry, aged-out ones dropped`() {
        val log = listOf((now - 200.min) to (now - 190.min), (now - 30.min) to (now - 20.min))
        assertEquals(listOf((now - 30.min) to (now - 20.min), (now - 5.min) to now),
            Storage.appendUsage(log, now - 5.min, now, cutoff = now - 120.min))
    }

    @Test
    fun `week-long window - wait spans days and lands on the right minute`() {
        val week = Rule(allowMin = 60, windowMin = 7 * 24 * 60)
        // An hour used a day ago: free again once it starts leaving the week,
        // 6 days minus an hour from now, plus the one minute that frees.
        val intervals = listOf((now - 25 * 60.min) to (now - 24 * 60.min))
        assertEquals((6 * 24 * 60 - 60 + 1).min, Storage.msUntilUnblocked(intervals, week, now))
    }

    @Test
    fun `used percent - exact, capped at 100`() {
        assertEquals(0, Storage.usedPct(0L, 5))
        assertEquals(52, Storage.usedPct(156_000, 5))              // 2.6 of 5 min
        assertEquals(90, Storage.usedPct(270_000, 5))
        assertEquals(100, Storage.usedPct(9.min, 5))               // overshoot
        assertEquals(100, Storage.usedPct(1.min, 0))
    }

    @Test
    fun `heavy overuse - wait is longer but never exceeds the window`() {
        val intervals = listOf((now - 60.min) to now)  // 60 min of use
        val wait = Storage.msUntilUnblocked(intervals, rule, now)
        // Usable once the window keeps less than 5 min of that hour: 116 min later.
        assertEquals(116.min, wait)
        assert(wait <= rule.windowMin * 60_000L)
    }
}
