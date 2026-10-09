package com.robin.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.robin.appblock.E2e.CALENDAR
import com.robin.appblock.E2e.CLOCK
import com.robin.appblock.E2e.appears
import com.robin.appblock.E2e.device
import com.robin.appblock.E2e.freshState
import com.robin.appblock.E2e.min
import com.robin.appblock.E2e.openApp
import com.robin.appblock.E2e.seedUsage
import com.robin.appblock.E2e.text
import com.robin.appblock.E2e.textContains
import com.robin.appblock.E2e.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core promise, end to end: the blocker service watching the real
 * foreground app and drawing the block wall over it. Budgets are seeded
 * into the usage log so no test waits out real minutes.
 */
@RunWith(AndroidJUnit4::class)
class BlockingE2eTest {

    @get:Rule
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val wall = textContains("Clock is blocked")

    @After
    fun leave() {
        device.findObject(text("Go to home screen"))?.click()
        device.pressHome()
    }

    @Test
    fun spentBudget_wallCoversTheApp_andSendsYouHome() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)

        openApp(CLOCK)

        val message = waitFor(wall).text
        // The minute used 10-11 min ago leaves the 2-hr window 109-110 min from now.
        assertTrue(message, Regex("(?s).*Try again in 1 hr (49|50) min\\..*").matches(message))
        waitFor(text("Go to home screen")).click()
        assertTrue(device.wait(Until.gone(wall), E2e.WAIT))
        assertTrue(appears(By.pkg(E2e.homeScreenPackage())))
    }

    @Test
    fun budgetRunningOutMidSession_blocksWithoutReopening() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 6.min, endAgo = 6.min - 50_000, windowMin = 120)   // 50 s used

        openApp(CLOCK)

        assertFalse("blocked with 10 s still left", appears(wall, timeout = 3_000))
        // The last 10 s run out, then the service's 5 s check catches it.
        assertTrue("never blocked once the budget ran out", appears(wall, timeout = 30_000))
    }

    @Test
    fun usageOlderThanTheWindow_noLongerCounts() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 130.min, endAgo = 121.min, windowMin = 120)   // 9 min, aged out

        openApp(CLOCK)

        // Opening checks at once and the session ticks every 5 s: two chances to block.
        assertFalse("blocked by usage outside the window", appears(wall, timeout = 8_000))
        assertEquals(CLOCK, device.currentPackageName)
    }

    @Test
    fun appWithoutALimit_isLeftAlone() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)   // Clock: spent

        openApp(CALENDAR)

        assertFalse(appears(textContains("is blocked"), timeout = 8_000))
        assertEquals(CALENDAR, device.currentPackageName)
    }

    @Test
    fun usingALimitedApp_showsTheSessionTimer() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))

        openApp(CLOCK)
        device.openNotification()

        waitFor(text("Clock: time left"))
        device.pressBack()
    }
}
