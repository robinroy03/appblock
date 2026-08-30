package com.robin.appblock

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

    private lateinit var enableButton: Button
    private lateinit var list: LinearLayout
    // package name -> (allow field, window field)
    private val rows = mutableMapOf<String, Pair<EditText, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableButton = Button(this).apply {
            text = "Enable accessibility service (required)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val addButton = Button(this).apply {
            text = "+ Block an app"
            setOnClickListener {
                // Blocking is only enforced by the accessibility service; adding
                // apps before it's on would silently do nothing.
                if (!serviceEnabled()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setTitle("Accessibility service is off")
                        .setMessage("AppBlock can't block anything until its " +
                            "accessibility service is enabled. Turn it on first, " +
                            "then pick the apps to block.")
                        .setPositiveButton("Open settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@setOnClickListener
                }
                save()
                startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
            }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            // Absorbs focus when a budget field's cursor is dismissed via ✓.
            isFocusableInTouchMode = true
            addView(enableButton)
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
                "Version $version<br><br>" +
                "This app is open source:<br>" +
                "<a href=\"https://github.com/robinroy03/appblock\">github.com/robinroy03/appblock</a>" +
                "<br><br>Made with ❤️ by <a href=\"https://x.com/_RobinRoy\">robin</a>",
                Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            textSize = 16f
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("About AppBlock")
            .setView(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the settings page or the app picker: refresh both.
        enableButton.visibility = if (serviceEnabled()) View.GONE else View.VISIBLE
        rebuild()
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
        val entries = rules.entries.sortedBy { labelFor(it.key).lowercase() }

        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "\nNo apps blocked yet."
                gravity = Gravity.CENTER
            })
            return
        }

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
