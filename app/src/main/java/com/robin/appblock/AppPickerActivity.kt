package com.robin.appblock

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
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
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName && it.pkg !in alreadyBlocked }
            .sortedBy { it.label.lowercase() }

        val addButton = Button(this).apply {
            text = "Block selected apps"
            setOnClickListener {
                val rules = Storage.loadRules(this@AppPickerActivity).toMutableMap()
                for (pkg in selected) rules[pkg] = Rule(5, 120)
                Storage.saveRules(this@AppPickerActivity, rules)
                finish()
            }
        }

        val iconPx = (40 * resources.displayMetrics.density).toInt()
        val listView = ListView(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(i: Int) = apps[i]
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
                val app = apps[i]
                (row.getChildAt(0) as ImageView).setImageDrawable(iconFor(app.pkg))
                (row.getChildAt(1) as CheckBox).apply {
                    text = app.label
                    isChecked = app.pkg in selected
                }
                return row
            }
        }
        listView.setOnItemClickListener { _, view, i, _ ->
            val pkg = apps[i].pkg
            val nowSelected = pkg !in selected
            if (nowSelected) selected.add(pkg) else selected.remove(pkg)
            ((view as LinearLayout).getChildAt(1) as CheckBox).isChecked = nowSelected
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(addButton)
            addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun iconFor(pkg: String): Drawable = icons.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) }
        catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
    }
}
