package com.robin.appblock

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Home screen: the list of currently blocked apps, each with editable
 * "allow X min per Y min" fields (auto-saved when you leave the screen),
 * plus a button to pick more apps to block.
 */
class MainActivity : Activity() {

    // One "(required)" button + why-blurb per Storage.Requirement, generated
    // from the enum: a future requirement shows up here with no extra wiring.
    private val reqViews = mutableMapOf<Storage.Requirement, List<View>>()
    private lateinit var notifReminder: LinearLayout
    private lateinit var list: LinearLayout
    // package name -> (allow field, window field)
    private val rows = mutableMapOf<String, Pair<EditText, EditText>>()

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
                save()
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
            // Absorbs focus when a budget field's cursor is dismissed via ✓.
            isFocusableInTouchMode = true
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
        val allGood = missingRequirements().isEmpty() && notificationsEnabled()
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
            val vis = if (requirementMet(req)) View.GONE else View.VISIBLE
            for (v in views) v.visibility = vis
        }
        notifReminder.visibility =
            if (!notificationsEnabled() && !Storage.notifReminderDismissed(this))
                View.VISIBLE else View.GONE
        rebuild()
        refreshPermRows?.invoke()
    }

    private fun notificationsEnabled() =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // Live state + fix path per requirement. Both `when`s are exhaustive, so
    // adding a Storage.Requirement entry won't compile until it's wired here —
    // and everything else (buttons, blurbs, popup, About rows) follows free.

    private fun requirementMet(req: Storage.Requirement): Boolean = when (req) {
        Storage.Requirement.ACCESSIBILITY -> serviceEnabled()
        Storage.Requirement.BATTERY -> batteryExempt()
    }

    private fun requirementFix(req: Storage.Requirement) {
        when (req) {
            Storage.Requirement.ACCESSIBILITY ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            // The direct "allow?" dialog can only grant. Once granted, tapping
            // the row goes to the battery-optimization list instead, where the
            // exemption can be reviewed and undone (find AppBlock there).
            Storage.Requirement.BATTERY ->
                if (batteryExempt())
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else requestBatteryExemption()
        }
    }

    private fun missingRequirements(): List<Storage.Requirement> =
        Storage.Requirement.entries.filter { !requirementMet(it) }

    /** System dialog asking to exempt AppBlock from battery optimization. */
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
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
            row(req.title, req.permsNote, { requirementMet(req) }, { requirementFix(req) })
        } + listOf(
            row("Notifications",
                "Optional. Warns you at 50% and 90% of an app's allowance.",
                ::notificationsEnabled, ::openNotificationSettings),
            row("Usage access",
                "Optional. Shows screen time next to each app in the picker.",
                { usageAccessGranted(this) },
                { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }))
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

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BlockerService::class.java)
        return enabled.split(':').any {
            it == me.flattenToString() || it == me.flattenToShortString()
        }
    }

    /** One card per blocked app: [icon] Label [✕] / allow [5] min per [120] min. */
    private fun rebuild() {
        list.removeAllViews()
        rows.clear()
        val rules = Storage.loadRules(this)

        // While accessibility is off the app cards hide (they'd promise
        // something the app can't deliver) but the rules stay saved, so
        // re-enabling brings everything straight back.
        when (Storage.homeList(serviceEnabled(), rules.size)) {
            Storage.HomeList.PAUSED_NOTE -> {
                list.addView(TextView(this).apply {
                    text = "\nBlocking is paused because the accessibility " +
                        "service is off. Your ${rules.size} blocked " +
                        (if (rules.size == 1) "app is" else "apps are") +
                        " saved and will reappear once you turn it back on."
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

        val iconPx = (40 * resources.displayMetrics.density).toInt()
        for ((pkg, rule) in entries) {
            val allow = numberField(rule.allowMin)
            val window = numberField(rule.windowMin)
            rows[pkg] = allow to window

            // Small pill right after the name showing budget already spent.
            val usedMs = Storage.usedMs(
                Storage.loadIntervals(this, pkg), rule.windowMin, System.currentTimeMillis())
            val usedMin = Storage.displayedUsedMin(usedMs, rule.allowMin)
            val usedBubble = TextView(this).apply {
                text = "$usedMin/${rule.allowMin} min used"
                textSize = 12f
                setPadding(20, 8, 20, 8)
                val night = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(if (night) 0xFF3A3A3A.toInt() else 0xFFE0E0E0.toInt())
                }
            }

            val titleLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageDrawable(iconFor(pkg)) },
                    LinearLayout.LayoutParams(iconPx, iconPx))
                addView(TextView(context).apply {
                    text = labelFor(pkg)
                    textSize = 18f
                    maxLines = 1
                    setPadding(24, 0, 16, 0)
                })
                addView(usedBubble)
                addView(View(context),
                    LinearLayout.LayoutParams(0, 0, 1f))   // spacer pushes ✕ to the edge
                addView(Button(context).apply {
                    text = "✕"
                    setOnClickListener { removeApp(pkg) }
                })
            }
            // ✓ appears while either budget field is being edited; tapping it
            // saves, hides the keyboard, and drops the cursor.
            val confirm = Button(this).apply {
                text = "✓"
                visibility = View.GONE
                setOnClickListener {
                    save()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(it.windowToken, 0)
                    // Rebuild so the "X/Y min used" pill reflects the new budget.
                    rebuild()
                }
            }
            val focusWatcher = View.OnFocusChangeListener { _, _ ->
                confirm.visibility =
                    if (allow.isFocused || window.isFocused) View.VISIBLE else View.GONE
            }
            allow.onFocusChangeListener = focusWatcher
            window.onFocusChangeListener = focusWatcher

            val budgetLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = "allow" })
                addView(allow)
                addView(TextView(context).apply { text = "min per" })
                addView(window)
                addView(TextView(context).apply { text = "min" })
                addView(confirm)
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 0)
                addView(titleLine)
                addView(budgetLine)
            })
        }
    }

    // Confirmation guards against accidental taps on the ✕ button.
    private fun removeApp(pkg: String) {
        AlertDialog.Builder(this)
            .setIcon(iconFor(pkg))
            .setTitle("Remove ${labelFor(pkg)}?")
            .setMessage("Are you sure you want to remove the limits for this app?")
            .setPositiveButton("Yes") { _, _ ->
                save()
                Storage.removeApp(this, pkg)
                rebuild()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun save() {
        // No rows means the list wasn't built (service off, blocking paused):
        // saving would rewrite the rules blob as empty and wipe every app.
        if (rows.isEmpty()) return
        val old = Storage.loadRules(this)
        val rules = mutableMapOf<String, Rule>()
        for ((pkg, fields) in rows) {
            // A field left empty keeps its previous value.
            val allowMin = fields.first.text.toString().toIntOrNull() ?: old[pkg]?.allowMin ?: 5
            val windowMin = fields.second.text.toString().toIntOrNull() ?: old[pkg]?.windowMin ?: 120
            rules[pkg] = Rule(allowMin, windowMin)
        }
        Storage.saveRules(this, rules)
    }

    private fun numberField(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        minEms = 3
        gravity = Gravity.CENTER
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
}
