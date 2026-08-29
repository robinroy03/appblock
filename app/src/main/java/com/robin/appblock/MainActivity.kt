package com.robin.appblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings screen: one row per launchable app —
 *   [x] Instagram   allow [ 5 ] min per [ 120 ] min
 * plus a Save button and a shortcut to enable the accessibility service.
 */
class MainActivity : Activity() {

    // package name -> (checkbox, allow field, window field)
    private val rows = mutableMapOf<String, Triple<CheckBox, EditText, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rules = Storage.loadRules(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        list.addView(Button(this).apply {
            text = "1. Enable accessibility service (required)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        val saveButton = Button(this).apply {
            text = "2. Save rules"
            setOnClickListener { save() }
        }
        list.addView(saveButton)

        // Every app that shows up in the launcher, except ourselves.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }

        for ((pkg, label) in apps) {
            val rule = rules[pkg]

            val check = CheckBox(this).apply {
                text = label
                isChecked = rule != null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val allow = numberField(rule?.allowMin, "5")
            val window = numberField(rule?.windowMin, "120")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(check)
                addView(allow)
                addView(TextView(context).apply { text = "min per" })
                addView(window)
                addView(TextView(context).apply { text = "min" })
            }
            rows[pkg] = Triple(check, allow, window)
            list.addView(row)
        }

        setContentView(ScrollView(this).apply { addView(list) })
    }

    private fun numberField(value: Int?, hint: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        this.hint = hint
        if (value != null) setText(value.toString())
        minEms = 3
    }

    private fun save() {
        val rules = mutableMapOf<String, Rule>()
        for ((pkg, row) in rows) {
            val (check, allow, window) = row
            if (!check.isChecked) continue
            // Empty fields fall back to the hint defaults (5 min per 120 min).
            val allowMin = allow.text.toString().toIntOrNull() ?: 5
            val windowMin = window.text.toString().toIntOrNull() ?: 120
            rules[pkg] = Rule(allowMin, windowMin)
        }
        Storage.saveRules(this, rules)
        Toast.makeText(this, "Saved ${rules.size} rule(s)", Toast.LENGTH_SHORT).show()
    }
}
