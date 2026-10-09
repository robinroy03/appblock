package com.robin.appblock

import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.robin.appblock.E2e.CLOCK
import com.robin.appblock.E2e.appears
import com.robin.appblock.E2e.ctx
import com.robin.appblock.E2e.device
import com.robin.appblock.E2e.freshState
import com.robin.appblock.E2e.min
import com.robin.appblock.E2e.openApp
import com.robin.appblock.E2e.openHome
import com.robin.appblock.E2e.pressOk
import com.robin.appblock.E2e.seedUsage
import com.robin.appblock.E2e.selected
import com.robin.appblock.E2e.shell
import com.robin.appblock.E2e.step
import com.robin.appblock.E2e.text
import com.robin.appblock.E2e.textContains
import com.robin.appblock.E2e.turnTo
import com.robin.appblock.E2e.waitFor
import com.robin.appblock.E2e.wheels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home screen and the limit dialog: what you set there is what gets
 * saved and what the blocker enforces.
 */
@RunWith(AndroidJUnit4::class)
class HomeE2eTest {

    @get:Rule
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    @After
    fun leave() {
        device.findObject(text("Go to home screen"))?.click()
        // A test failing mid-way must not leave usage access off for the next one.
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        device.pressHome()
    }

    private fun openLimitDialog(label: String) {
        waitFor(By.desc("Set limit for $label")).click()
        waitFor(text("Set limit"))
    }

    @Test
    fun wheelsSetTheLimit_homeShowsIt_andTheBlockerEnforcesIt() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        val (allow, window) = wheels()
        allow.turnTo("10 min", up = false)
        window.turnTo("1 hr", up = true)
        pressOk()

        waitFor(text("10 min in any 1 hr · 0 min used"))
        assertEquals(Rule(10, 60), Storage.loadRules(ctx)[CLOCK])

        // 10 min used 20-30 min ago: spent under the new rule (under the old
        // 5-in-2-hrs it would be spent too, so check the wait reflects 1 hr).
        seedUsage(CLOCK, startAgo = 30.min, endAgo = 20.min, windowMin = 60)
        openApp(CLOCK)
        val message = waitFor(textContains("Clock is blocked")).text
        assertTrue(message, Regex("(?s).*Try again in (30|31) min\\..*").matches(message))
    }

    @Test
    fun allowWheel_neverOffersAnAllowanceAsLongAsTheWindow() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        val (allow, window) = wheels()
        window.turnTo("15 min", up = true)
        // 30 min no longer fits a 15-min window: the wheel dropped to the largest that does.
        assertEquals("10 min", allow.selected())
        allow.step()   // try to go past it
        assertEquals("10 min", allow.selected())
        pressOk()

        waitFor(text("10 min in any 15 min · 0 min used"))
    }

    @Test
    fun typedLimit_okOnlyForAValidRule() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")
        waitFor(text("Type exact times")).click()

        val (allowField, hours, minutes) = device.findObjects(By.clazz(EditText::class.java))
            .sortedBy { it.visibleBounds.left }
        hours.text = "1"
        minutes.text = "0"
        allowField.text = "60"   // as long as the window: would never block
        assertFalse(waitFor(text("OK")).isEnabled)

        allowField.text = "59"
        assertTrue(device.wait(Until.hasObject(text("OK").enabled(true)), E2e.WAIT))
        pressOk()

        waitFor(text("59 min in any 1 hr · 0 min used"))
        assertEquals(Rule(59, 60), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun cancel_changesNothing() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        wheels()[0].turnTo("20 min", up = false)
        waitFor(text("Cancel")).click()

        waitFor(text("5 min in any 2 hrs · 0 min used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun remove_asksFirst_thenTheAppIsGone() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        waitFor(text("Remove")).click()
        waitFor(text("Remove Clock?"))
        waitFor(text("Yes")).click()

        waitFor(textContains("No apps blocked yet"))
        assertTrue(Storage.loadRules(ctx).isEmpty())
    }

    @Test
    fun pickingAnApp_addsItWithTheDefaultLimit() {
        freshState()
        openHome()

        waitFor(text("+ Block an app")).click()
        waitFor(By.clazz(EditText::class.java)).text = "Clock"   // the search box
        waitFor(text("Clock").clazz(TextView::class.java)).click()   // the row, not the search box
        waitFor(text("Block 1 selected app")).click()

        waitFor(text("5 min in any 2 hrs · 0 min used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun losingUsageAccess_pausesBlocking_andKeepsTheRules() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")
        openHome()

        waitFor(textContains("Blocking is paused"))
        assertFalse(appears(By.desc("Set limit for Clock"), timeout = 1_000))

        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        openHome()

        waitFor(text("5 min in any 2 hrs · 0 min used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }
}
