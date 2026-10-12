package com.robin.appblock

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.regex.Pattern

/**
 * Shared setup for the e2e tests: a clean app state with every required
 * setting granted, ways to open apps and find things on screen, and seeding
 * of the usage log so a test can start "5 minutes into" a budget instead of
 * waiting it out.
 *
 * The tests run inside AppBlock's own process, so they read and write its
 * storage directly; everything on screen goes through UI Automator, which
 * also sees other apps and the block wall drawn over them.
 */
object E2e {
    // Stock apps on the emulator images (local and CI) used as "blocked apps".
    const val CLOCK = "com.google.android.deskclock"
    const val CALENDAR = "com.google.android.calendar"

    const val WAIT = 10_000L

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val Int.min get() = this * 60_000L

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /**
     * Every required setting on, notifications allowed, onboarding seen, the
     * given rules and nothing else (no usage, no leftover block wall), the
     * blocker running, and the launcher in front.
     */
    fun freshState(rules: Map<String, Rule> = emptyMap()) {
        val pkg = ctx.packageName
        shell("appops set $pkg GET_USAGE_STATS allow")
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        shell("dumpsys deviceidle whitelist +$pkg")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.findObject(text("Go to home screen"))?.click()
        for (app in listOf(CLOCK, CALENDAR)) shell("am force-stop $app")
        ctx.getSharedPreferences("appblock", Context.MODE_PRIVATE).edit().clear().commit()
        Storage.setOnboardingSeen(ctx)
        Storage.saveRules(ctx, rules)
        // Opening the home screen starts the blocker (as it does for a user).
        openHome()
        device.pressHome()
        device.waitForIdle()
    }

    /** Record that `pkg` was used from `startAgo` to `endAgo` before now. */
    fun seedUsage(pkg: String, startAgo: Long, endAgo: Long, windowMin: Int) {
        val now = System.currentTimeMillis()
        Storage.addUsage(ctx, pkg, now - startAgo, now - endAgo, windowMin)
    }

    /** AppBlock's home screen, freshly built from storage. */
    fun openHome() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(text("Block an app")), WAIT)) { "home screen didn't open" }
    }

    /** Launch another app the way its launcher icon would, and wait for it to be in front. */
    fun openApp(pkg: String) {
        // From the shell (monkey silently doesn't launch from a test).
        val launcher = ctx.packageManager.getLaunchIntentForPackage(pkg)!!.component!!
        shell("am start -W -n ${launcher.flattenToShortString()}")
        check(device.wait(Until.hasObject(By.pkg(pkg)), WAIT)) { "$pkg didn't open" }
    }

    /**
     * The launcher's package. Not device.launcherPackageName: on the emulator
     * images that names Settings' fallback home (a placeholder shown only
     * while the real launcher starts).
     */
    fun homeScreenPackage(): String =
        shell("cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.HOME").trim().lines().last().substringBefore('/')

    /** Exact on-screen text, ignoring case: buttons render their labels in caps. */
    fun text(s: String): BySelector = By.text(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE))

    fun textContains(s: String): BySelector =
        By.text(Pattern.compile(".*" + Pattern.quote(s) + ".*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL))

    fun waitFor(selector: BySelector, timeout: Long = WAIT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("not on screen after ${timeout}ms: $selector")

    fun appears(selector: BySelector, timeout: Long = WAIT): Boolean =
        device.wait(Until.hasObject(selector), timeout)

    // ---- the limit dialog ----

    /** Press OK on the limit dialog and wait for it to close. */
    fun pressOk() {
        waitFor(text("OK")).click()
        check(device.wait(Until.gone(text("Set limit")), WAIT)) { "the limit dialog didn't close" }
    }

    /** The Allow and in-any number fields, top to bottom. */
    fun limitFields(): List<UiObject2> {
        waitFor(By.clazz(EditText::class.java))
        return device.findObjects(By.clazz(EditText::class.java)).sortedBy { it.visibleBounds.top }
    }

    /**
     * JUnit rule: a failing test leaves a screenshot of the screen as it
     * was, like Playwright's. Gradle pulls it into
     * app/build/outputs/connected_android_test_additional_output/, and CI
     * uploads it with the report.
     */
    class ScreenshotOnFailure : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
            val name = "${description.testClass.simpleName}.${description.methodName}.png"
            // From the shell: it can write the output directory, the app can't.
            shell("mkdir -p $dir")
            shell("screencap -p $dir/$name")
        }
    }
}
