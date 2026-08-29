package com.robin.appblock

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
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
                save()
                startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
            }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(enableButton)
            addView(addButton)
            addView(list)
        }
        setContentView(ScrollView(this).apply { addView(root) })
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

            val titleLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageDrawable(iconFor(pkg)) },
                    LinearLayout.LayoutParams(iconPx, iconPx))
                addView(TextView(context).apply {
                    text = labelFor(pkg)
                    textSize = 18f
                    setPadding(24, 0, 0, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = "✕"
                    setOnClickListener { removeApp(pkg) }
                })
            }
            val budgetLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = "allow" })
                addView(allow)
                addView(TextView(context).apply { text = "min per" })
                addView(window)
                addView(TextView(context).apply { text = "min" })
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 0)
                addView(titleLine)
                addView(budgetLine)
            })
        }
    }

    private fun removeApp(pkg: String) {
        save()
        val rules = Storage.loadRules(this).toMutableMap()
        rules.remove(pkg)
        Storage.saveRules(this, rules)
        rebuild()
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
