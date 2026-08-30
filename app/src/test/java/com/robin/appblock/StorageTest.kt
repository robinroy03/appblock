package com.robin.appblock

import org.junit.Assert.assertEquals
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
    fun `used pill - zero use shows zero`() {
        assertEquals(0L, Storage.displayedUsedMin(0, 5))
    }

    @Test
    fun `used pill - partial minutes round up`() {
        assertEquals(1L, Storage.displayedUsedMin(1_000, 5))       // 1s -> "1"
        assertEquals(1L, Storage.displayedUsedMin(60_000, 5))      // exactly 1 min
        assertEquals(2L, Storage.displayedUsedMin(60_001, 5))      // just over 1 min
    }

    @Test
    fun `used pill - display capped at the allowance`() {
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
    fun `home list - all four states`() {
        // Fresh install: service off, no rules -> explain the required service.
        assertEquals(Storage.HomeList.SETUP_HINT, Storage.homeList(false, 0))
        // Service off with rules -> "blocking is paused" note, rules kept.
        assertEquals(Storage.HomeList.PAUSED_NOTE, Storage.homeList(false, 3))
        // Service on, nothing blocked yet -> the empty hint.
        assertEquals(Storage.HomeList.EMPTY_HINT, Storage.homeList(true, 0))
        // Normal operation -> the app cards.
        assertEquals(Storage.HomeList.CARDS, Storage.homeList(true, 3))
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
