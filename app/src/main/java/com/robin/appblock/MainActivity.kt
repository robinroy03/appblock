package com.robin.appblock

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/**
 * Home screen: the list of currently blocked apps, each showing today's
 * screen time and its limit, with an hourglass that opens the limit wheels,
 * plus a button to pick more apps to block.
 */
class MainActivity : Activity() {

    // One "(required)" button + why-blurb per Storage.Requirement, generated
    // from the enum: a future requirement shows up here with no extra wiring.
    private val reqViews = mutableMapOf<Storage.Requirement, List<View>>()
    private lateinit var notifReminder: LinearLayout
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Storage.onboardingSeen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Ask nicely: each required setting gets its button plus a "Why …?"
        // blurb right under it saying what it's for. Granted ones disappear
        // (in onResume), so whatever's left naturally moves to the top.
        for (req in Storage.Requirement.entries) {
            val button = Button(this).apply {
                text = req.button
                setOnClickListener { requirementFix(req) }
            }
            val blurb = TextView(this).apply {
                text = "Why ${req.whyWord}? ${req.why}"
                textSize = 13f
                alpha = 0.7f
                setPadding(16, 0, 16, 16)
            }
            reqViews[req] = listOf(button, blurb)
        }
        val addButton = Button(this).apply {
            text = "+ Block an app"
            setOnClickListener {
                // Blocking only works with every required setting on; adding
                // apps before then would silently do nothing. One popup names
                // everything still missing; the home-screen buttons fix them.
                val missing = missingRequirements()
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setTitle("Finish setting up first")
                        .setMessage(Storage.requirementsMessage(missing))
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }
                startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
            }
        }
        // Info card shown while notifications are off (unless permanently ✕-ed).
        notifReminder = ReminderCard.make(
            this,
            message = "Notifications are off, so AppBlock can't warn you " +
                "when you're close to using up an app's allowance. " +
                "Tap to turn them on.",
            confirmTitle = "Skip usage warnings?",
            confirmBody = "Without notifications, AppBlock can't let you know " +
                "when you're about to use up an app's allowance. The first " +
                "you'll hear of it is the block wall.\n\nHide this reminder " +
                "anyway? (You can still enable notifications later from the " +
                "About page.)",
            onFix = ::openNotificationSettings,
            onHideForever = { Storage.setNotifReminderDismissed(this) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            for (views in reqViews.values) for (v in views) addView(v)
            addView(notifReminder, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12; bottomMargin = 12 })
            addView(addButton)
            addView(list)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // (i) icon in the top-right of the action bar -> About dialog.
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("About").apply {
            setIcon(android.R.drawable.ic_menu_info_details)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { showAbout(); true }
        }
        return true
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "unknown" }
        val message = TextView(this).apply {
            text = Html.fromHtml(
                "One morning I woke up at 5am and scrolled Instagram, X and " +
                "YouTube for an hour straight. My entire daily quota, gone " +
                "before sunrise :) That's when it clicked: daily limits fail " +
                "because one binge empties them, and then you override. " +
                "AppBlock gives you a small allowance every time window " +
                "instead. Enough to check in, never enough to binge." +
                "<br><br>This app is " +
                "<a href=\"https://github.com/robinroy03/appblock\">open sourced</a>" +
                " under MIT license." +
                "<br><br>Feedback? Please email me " +
                "<a href=\"mailto:robinroy.work@gmail.com\">here</a>" +
                "<br><br>Made with ❤️ by <a href=\"https://x.com/_RobinRoy\">robin</a>" +
                "<br><br>Version $version",
                Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            textSize = 16f
            setPadding(48, 32, 48, 16)
        }
        // Always-available path to the permissions checklist: the amber alert
        // while something is off (no ✕, it goes away by being fixed), a calm
        // "review" link once everything is on.
        val allGood = allRequirementsMet(this) && notificationsEnabled()
        val permsAlert = ReminderCard.make(
            this,
            message = "Some permissions are not enabled. Tap to review and " +
                "fix them.",
            onFix = ::showPermissionsDialog)
        permsAlert.visibility = if (allGood) View.GONE else View.VISIBLE
        val reviewLink = TextView(this).apply {
            text = Html.fromHtml("<u>Review permissions</u>",
                Html.FROM_HTML_MODE_LEGACY)
            textSize = 16f
            setTextColor(message.linkTextColors)
            setPadding(48, 0, 48, 24)
            visibility = if (allGood) View.VISIBLE else View.GONE
            setOnClickListener { showPermissionsDialog() }
        }
        // Plain link (not a button) that reopens the onboarding screen.
        val manifesto = TextView(this).apply {
            text = Html.fromHtml("<u>Read the manifesto again</u>",
                Html.FROM_HTML_MODE_LEGACY)
            textSize = 16f
            setTextColor(message.linkTextColors)
            setPadding(48, 0, 48, 32)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("About AppBlock")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(message)
                addView(permsAlert, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 32; rightMargin = 32; bottomMargin = 24 })
                addView(reviewLink)
                addView(manifesto)
            })
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the settings page or the app picker: refresh all.
        for ((req, views) in reqViews) {
            val vis = if (requirementMet(this, req)) View.GONE else View.VISIBLE
            for (v in views) v.visibility = vis
        }
        // Everything granted: make sure the blocker is up (no-op if it is).
        BlockerService.start(this)
        notifReminder.visibility =
            if (!notificationsEnabled() && !Storage.notifReminderDismissed(this))
                View.VISIBLE else View.GONE
        rebuild()
        refreshPermRows?.invoke()
    }

    private fun notificationsEnabled() =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    // Fix path per requirement (live state lives in Perms.kt). The `when` is
    // exhaustive, so adding a Storage.Requirement entry won't compile until
    // it's wired here — and everything else (buttons, blurbs, popup, About
    // rows) follows free.

    private fun requirementFix(req: Storage.Requirement) {
        when (req) {
            Storage.Requirement.USAGE_ACCESS ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Storage.Requirement.OVERLAY ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(this)))
            // The direct "allow?" dialog can only grant. Once granted, tapping
            // the row goes to the battery-optimization list instead, where the
            // exemption can be reviewed and undone (find AppBlock there).
            Storage.Requirement.BATTERY ->
                if (batteryExempt(this))
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else requestBatteryExemption()
        }
    }

    private fun missingRequirements(): List<Storage.Requirement> = missingRequirements(this)

    /** System dialog asking to exempt AppBlock from battery optimization. */
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(this)))
    }

    /** The app's own notification-settings page: works even after a permanent
     *  "don't allow" on the runtime prompt. */
    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    // Lets onResume refresh the permissions dialog's ✓/✗ marks while it's open,
    // so fixing a setting and coming back shows the tick without reopening.
    private var refreshPermRows: (() -> Unit)? = null

    /** One row per permission: ✓/✗, name, what it's for; tap to go fix it. */
    private fun showPermissionsDialog() {
        fun row(title: String, why: String, enabled: () -> Boolean, fix: () -> Unit):
            Pair<LinearLayout, () -> Unit> {
            val mark = TextView(this).apply { textSize = 22f }
            val update = {
                mark.text = if (enabled()) "✓" else "✗"
                mark.setTextColor(if (enabled()) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            }
            update()
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 24, 48, 24)
                setOnClickListener { fix() }
                addView(mark, LinearLayout.LayoutParams(64, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 0, 0, 0)
                    addView(TextView(context).apply { text = title; textSize = 16f })
                    addView(TextView(context).apply { text = why; textSize = 13f; alpha = 0.7f })
                })
            }
            return view to update
        }
        // Required rows come straight from the Requirement enum; optional
        // extras follow. Each entry is (view, refresh-the-✓/✗ closure).
        val perms = Storage.Requirement.entries.map { req ->
            row(req.title, req.permsNote, { requirementMet(this, req) }, { requirementFix(req) })
        } + listOf(
            row("Notifications",
                "Optional. Warns you at 50% and 90% of an app's allowance.",
                ::notificationsEnabled, ::openNotificationSettings))
        refreshPermRows = { for ((_, update) in perms) update() }
        AlertDialog.Builder(this)
            .setTitle("Permissions")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                for ((view, _) in perms) addView(view)
            })
            .setPositiveButton("Done", null)
            .setOnDismissListener { refreshPermRows = null }
            .show()
    }

    /**
     * One row per blocked app, laid out like Digital Wellbeing's list:
     * [icon] Label / today's screen time / its limit  |  [hourglass].
     */
    private fun rebuild() {
        list.removeAllViews()
        val rules = Storage.loadRules(this)

        // While a required setting is off the app cards hide (they'd promise
        // something the app can't deliver) but the rules stay saved, so
        // fixing it brings everything straight back.
        when (Storage.homeList(allRequirementsMet(this), rules.size)) {
            Storage.HomeList.PAUSED_NOTE -> {
                list.addView(TextView(this).apply {
                    text = "\nBlocking is paused because a required setting " +
                        "above is off. Your ${rules.size} blocked " +
                        (if (rules.size == 1) "app is" else "apps are") +
                        " saved and will reappear once it's back on."
                    gravity = Gravity.CENTER
                })
                return
            }
            Storage.HomeList.EMPTY_HINT -> {
                list.addView(TextView(this).apply {
                    text = "\nNo apps blocked yet."
                    gravity = Gravity.CENTER
                })
                return
            }
            Storage.HomeList.CARDS -> {}
        }
        val entries = rules.entries.sortedBy { labelFor(it.key).lowercase() }
        val today = todayUsageMs()

        for ((pkg, rule) in entries) {
            val usedMs = Storage.usedMs(
                Storage.loadIntervals(this, pkg), rule.windowMin, System.currentTimeMillis())
            val usedMin = Storage.displayedUsedMin(usedMs, rule.allowMin)
            val label = TextView(this).apply {
                text = labelFor(pkg)
                textSize = 18f
                maxLines = 1
            }
            val text = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                addView(label)
                addView(TextView(context).apply {
                    text = Storage.fmtToday(today[pkg] ?: 0L)
                    textSize = 14f
                    alpha = 0.7f
                })
                addView(TextView(context).apply {
                    text = "${Storage.fmtSpan(rule.allowMin)} in any " +
                        "${Storage.fmtSpan(rule.windowMin)} · $usedMin min used"
                    textSize = 14f
                    alpha = 0.7f
                })
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(ImageView(context).apply { setImageDrawable(iconFor(pkg)) },
                    LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(text, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                // Thin divider setting the limit button apart, as in the
                // system's own screen-time list.
                addView(View(context).apply {
                    setBackgroundColor(label.currentTextColor)
                    alpha = 0.25f
                }, LinearLayout.LayoutParams(dp(1), dp(40)))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_hourglass)
                    contentDescription = "Set limit for ${labelFor(pkg)}"
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = getDrawable(TypedValue().also {
                        theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, it, true)
                    }.resourceId)
                    setOnClickListener { showLimitDialog(pkg, rule) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(12) })
            })
        }
    }

    /**
     * Two scroll wheels, the allowance and the rolling window it's measured
     * over, each ticking the vibration motor as it turns. "Type exact times"
     * swaps them for number fields, for limits between the wheel stops.
     * OK (enabled only for a valid rule) saves straight away; Remove (the
     * old ✕) lives here too.
     */
    private fun showLimitDialog(pkg: String, rule: Rule) {
        val name = labelFor(pkg)
        val message = TextView(this).apply {
            textSize = 16f
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun wheel(standard: List<Int>, current: Int): Pair<NumberPicker, List<Int>> {
            val values = Storage.wheelChoices(standard, current)
            return NumberPicker(this).apply {
                minValue = 0
                maxValue = values.size - 1
                displayedValues = values.map(Storage::fmtSpan).toTypedArray()
                value = values.indexOf(current)
                wrapSelectorWheel = false
                // Wheel only: no tap-to-type keyboard on the selected value.
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            } to values
        }
        val (allow, allowValues) = wheel(Storage.ALLOW_CHOICES, rule.allowMin)
        val (window, windowValues) = wheel(Storage.WINDOW_CHOICES, rule.windowMin)
        val allowField = numberField()
        val windowHField = numberField()
        val windowMField = numberField()
        var typing = false
        fun picked(): Rule? =
            if (typing) Storage.exactRule(allowField.text.toString(),
                windowHField.text.toString(), windowMField.text.toString())
            else Rule(allowValues[allow.value], windowValues[window.value])
                .takeIf(Storage::validRule)

        lateinit var dialog: AlertDialog
        fun describe() {
            val r = picked()
            message.text = if (r == null)
                "The allowance has to be shorter than the window, and the " +
                    "window can be at most ${Storage.fmtSpan(Storage.MAX_WINDOW_MIN)}."
            else "$name gets ${Storage.fmtSpan(r.allowMin)} in any " +
                "${Storage.fmtSpan(r.windowMin)}. Time comes back as old use " +
                "rolls out of the window, so there's no midnight reset."
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = r != null
        }
        for (p in listOf(allow, window)) {
            p.setOnValueChangedListener { picker, _, _ ->
                tick()
                describe()
            }
        }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) = describe()
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
        }
        for (f in listOf(allowField, windowHField, windowMField)) f.addTextChangedListener(watcher)

        fun column(heading: String, vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(TextView(context).apply { text = heading; alpha = 0.7f })
            if (views.size == 1) addView(views[0])
            else addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                for (v in views) addView(v)
            })
        }
        fun unit(text: String) = TextView(this).apply {
            this.text = text
            setPadding(dp(4), 0, dp(8), 0)
        }
        fun pair(vararg columns: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            for (c in columns) addView(c)
        }
        val wheels = pair(column("Allow", allow), column("in any", window))
        val fields = pair(
            column("Allow", allowField, unit("min")),
            column("in any", windowHField, unit("h"), windowMField, unit("min")))
            .apply { visibility = View.GONE }
        val typeLink = TextView(this).apply {
            text = Html.fromHtml("<u>Type exact times</u>", Html.FROM_HTML_MODE_LEGACY)
            setTextColor(message.linkTextColors)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener {
                // Start the fields from whatever the wheels show now.
                val r = Rule(allowValues[allow.value], windowValues[window.value])
                allowField.setText(r.allowMin.toString())
                windowHField.setText((r.windowMin / 60).toString())
                windowMField.setText((r.windowMin % 60).toString())
                typing = true
                wheels.visibility = View.GONE
                fields.visibility = View.VISIBLE
                visibility = View.GONE
                describe()
                allowField.requestFocus()
                allowField.selectAll()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(allowField, 0)
            }
        }
        dialog = AlertDialog.Builder(this)
            .setIcon(iconFor(pkg))
            .setTitle("Set limit")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(message)
                addView(wheels)
                addView(fields)
                addView(typeLink)
            })
            .setPositiveButton("OK") { _, _ ->
                val r = picked() ?: return@setPositiveButton
                val rules = Storage.loadRules(this).toMutableMap()
                rules[pkg] = r
                Storage.saveRules(this, rules)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove") { _, _ -> removeApp(pkg) }
            .create()
        dialog.setOnShowListener { describe() }
        dialog.show()
    }

    // Confirmation guards against accidental taps on Remove.
    private fun removeApp(pkg: String) {
        AlertDialog.Builder(this)
            .setIcon(iconFor(pkg))
            .setTitle("Remove ${labelFor(pkg)}?")
            .setMessage("Are you sure you want to remove the limits for this app?")
            .setPositiveButton("Yes") { _, _ ->
                Storage.removeApp(this, pkg)
                rebuild()
            }
            .setNegativeButton("No", null)
            .show()
    }

    /** Screen time per package since midnight (needs Usage access, which the cards do too). */
    private fun todayUsageMs(): Map<String, Long> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return usm.queryAndAggregateUsageStats(midnight, System.currentTimeMillis())
            .mapValues { it.value.totalTimeInForeground }
    }

    private fun numberField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(4))
        minEms = 2
        gravity = Gravity.CENTER
    }

    /**
     * One short click of the vibration motor per wheel step. Straight to the
     * Vibrator rather than performHapticFeedback: that one goes silent when
     * the phone's "touch feedback" setting is off, and the tick is the point.
     */
    private fun tick() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
}
