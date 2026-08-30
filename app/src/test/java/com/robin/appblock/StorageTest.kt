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
    fun `heavy overuse - wait is longer but never exceeds the window`() {
        val intervals = listOf((now - 60.min) to now)  // 60 min of use
        val wait = Storage.msUntilUnblocked(intervals, rule, now)
        // Usable once the window keeps less than 5 min of that hour: 116 min later.
        assertEquals(116.min, wait)
        assert(wait <= rule.windowMin * 60_000L)
    }
}
