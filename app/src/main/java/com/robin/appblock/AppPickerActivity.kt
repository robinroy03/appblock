package com.robin.appblock

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView

/**
 * Picker screen: every launchable app (not yet blocked), with checkboxes.
 * Selected apps are added with the default budget (5 min per 120 min),
 * which is then editable on the home screen.
 *
 * Uses a ListView so rows are recycled — scrolling stays smooth even with
 * hundreds of installed apps.
 */
class AppPickerActivity : Activity() {

    private class Entry(val pkg: String, val label: String)

    private val selected = mutableSetOf<String>()
    private val icons = mutableMapOf<String, Drawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = packageManager
        val alreadyBlocked = Storage.loadRules(this).keys
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = pm.queryIntentActivities(launcher, 0)
            .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName && it.pkg !in alreadyBlocked }
            .sortedBy { it.label.lowercase() }
        val shown = allApps.toMutableList()   // what the list currently displays

        val addButton = Button(this).apply {
            visibility = View.GONE   // only shown once something is selected
            setOnClickListener {
                val rules = Storage.loadRules(this@AppPickerActivity).toMutableMap()
                for (pkg in selected) rules[pkg] = Rule(5, 120)
                Storage.saveRules(this@AppPickerActivity, rules)
                finish()
            }
        }
        fun refreshAddButton() {
            addButton.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            addButton.text = if (selected.size == 1) "Block 1 selected app"
                             else "Block ${selected.size} selected apps"
        }

        val iconPx = (40 * resources.displayMetrics.density).toInt()
        val listView = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()

            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView ?: LinearLayout(this@AppPickerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(24, 16, 24, 16)
                    addView(ImageView(context), LinearLayout.LayoutParams(iconPx, iconPx))
                    addView(CheckBox(context).apply {
                        // The row handles taps; a clickable checkbox would swallow them.
                        isClickable = false
                        isFocusable = false
                        setPadding(24, 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }) as LinearLayout
                val app = shown[i]
                (row.getChildAt(0) as ImageView).setImageDrawable(iconFor(app.pkg))
                (row.getChildAt(1) as CheckBox).apply {
                    text = app.label
                    isChecked = app.pkg in selected
                }
                return row
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, view, i, _ ->
            val pkg = shown[i].pkg
            val nowSelected = pkg !in selected
            if (nowSelected) selected.add(pkg) else selected.remove(pkg)
            ((view as LinearLayout).getChildAt(1) as CheckBox).isChecked = nowSelected
            refreshAddButton()
        }

        val searchBox = EditText(this).apply {
            hint = "Search apps…"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    val query = s.toString().trim().lowercase()
                    shown.clear()
                    shown.addAll(if (query.isEmpty()) allApps
                                 else allApps.filter { query in it.label.lowercase() })
                    adapter.notifyDataSetChanged()
                }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(addButton)
            addView(searchBox)
            addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun iconFor(pkg: String): Drawable = icons.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) }
        catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
    }
}
