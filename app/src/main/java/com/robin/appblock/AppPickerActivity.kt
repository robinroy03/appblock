package com.robin.appblock

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/** True when the user has granted "Usage access" (a special app op with its own
 *  Settings page — there is no runtime prompt for it). */
fun usageAccessGranted(ctx: Context): Boolean {
    @Suppress("DEPRECATION")
    return ctx.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

/**
 * Picker screen: every launchable app (not yet blocked), with checkboxes and,
 * when Usage access is granted, each app's screen time today / last 7 days.
 * Sortable by name or usage via the chip row; default is this-week descending,
 * so the biggest time sinks float to the top.
 *
 * Uses a ListView so rows are recycled — scrolling stays smooth even with
 * hundreds of installed apps.
 */
class AppPickerActivity : Activity() {

    private class Entry(val pkg: String, val label: String) {
        var todayMs = 0L
        var weekMs = 0L
    }

    private enum class SortKey { NAME, TODAY, WEEK }

    private val selected = mutableSetOf<String>()
    private val icons = mutableMapOf<String, Drawable>()
    private val allApps = mutableListOf<Entry>()
    private val shown = mutableListOf<Entry>()   // what the list currently displays

    private var sortKey = SortKey.WEEK
    private var descending = true
    private var userSorted = false   // until a chip is tapped, defaults apply
    private var query = ""
    private var haveUsage = false

    private lateinit var adapter: BaseAdapter
    private lateinit var usageCard: LinearLayout
    private lateinit var chips: Map<SortKey, Pair<TextView, String>>
    private var night = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val pm = packageManager
        val alreadyBlocked = Storage.loadRules(this).keys
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps.addAll(pm.queryIntentActivities(launcher, 0)
            .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName && it.pkg !in alreadyBlocked })

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

        // Same info-card style as the home screen's notification reminder.
        usageCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(if (night) 0xFF33301F.toInt() else 0xFFFFF8E1.toInt())
                setStroke(3, 0xFFFFB300.toInt())
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_dialog_info)
            })
            addView(TextView(context).apply {
                text = "Grant usage access to see how much you use each app " +
                    "(and sort by it). Tap to enable."
                textSize = 14f
                setPadding(24, 12, 0, 12)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val iconPx = (40 * resources.displayMetrics.density).toInt()
        val listView = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()

            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView ?: LinearLayout(this@AppPickerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 16, 24, 16)
                    addView(ImageView(context), LinearLayout.LayoutParams(iconPx, iconPx))
                    addView(CheckBox(context).apply {
                        // The row handles taps; a clickable checkbox would swallow them.
                        isClickable = false
                        isFocusable = false
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(8, 0, 0, 0)
                        addView(TextView(context).apply { textSize = 16f; maxLines = 1 })
                        addView(TextView(context).apply { textSize = 13f; alpha = 0.7f })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }) as LinearLayout
                val app = shown[i]
                (row.getChildAt(0) as ImageView).setImageDrawable(iconFor(app.pkg))
                (row.getChildAt(1) as CheckBox).isChecked = app.pkg in selected
                val col = row.getChildAt(2) as LinearLayout
                (col.getChildAt(0) as TextView).text = app.label
                (col.getChildAt(1) as TextView).apply {
                    visibility = if (haveUsage) View.VISIBLE else View.GONE
                    text = if (app.weekMs == 0L && app.todayMs == 0L)
                        "Not used in the last 7 days"
                        else "${Storage.fmtDuration(app.todayMs)} today · " +
                             "${Storage.fmtDuration(app.weekMs)} last 7 days"
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
                    query = s.toString().trim().lowercase()
                    refreshShown()
                }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            })
        }

        chips = mapOf(
            SortKey.NAME to (chip("Name") to "Name"),
            SortKey.TODAY to (chip("Today") to "Today"),
            SortKey.WEEK to (chip("7 days") to "7 days"))
        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 8)
            addView(TextView(context).apply { text = "Sort:"; setPadding(0, 0, 16, 0) })
            for ((view, _) in chips.values) addView(view)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(addButton)
            addView(usageCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 16; rightMargin = 16; topMargin = 8 })
            addView(searchBox)
            addView(sortRow)
            addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the usage-access settings page (or first landing).
        haveUsage = usageAccessGranted(this)
        usageCard.visibility = if (haveUsage) View.GONE else View.VISIBLE
        if (haveUsage) loadUsage()
        // Usage sorts need usage data; fall back to name until access is
        // granted, then to the 7-days default unless the user chose a sort.
        if (!haveUsage && sortKey != SortKey.NAME) {
            sortKey = SortKey.NAME; descending = false
        } else if (haveUsage && !userSorted) {
            sortKey = SortKey.WEEK; descending = true
        }
        refreshShown()
    }

    /** Screen time per package: today (since midnight) and the last 7 days. */
    private fun loadUsage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = usm.queryAndAggregateUsageStats(midnight, now)
        val week = usm.queryAndAggregateUsageStats(now - 7 * 24 * 3_600_000L, now)
        for (app in allApps) {
            app.todayMs = day[app.pkg]?.totalTimeInForeground ?: 0L
            app.weekMs = week[app.pkg]?.totalTimeInForeground ?: 0L
        }
    }

    /** Re-apply the search filter and the active sort, then repaint. */
    private fun refreshShown() {
        val base: Comparator<Entry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.label.lowercase() }
            SortKey.TODAY -> compareBy { it.todayMs }
            SortKey.WEEK -> compareBy { it.weekMs }
        }
        val cmp = (if (descending) base.reversed() else base)
            .thenBy { it.label.lowercase() }
        shown.clear()
        shown.addAll(allApps
            .filter { query.isEmpty() || query in it.label.lowercase() }
            .sortedWith(cmp))
        adapter.notifyDataSetChanged()
        for ((key, pair) in chips) {
            val (view, label) = pair
            val active = key == sortKey
            view.text = if (!active) label
                else label + if (descending) " ▼" else " ▲"
            view.background = if (!active) null else GradientDrawable().apply {
                cornerRadius = 40f
                setColor(if (night) 0xFF3A3A3A.toInt() else 0xFFE0E0E0.toInt())
            }
            // Usage sorts are dead without usage access: visible but greyed.
            view.alpha = if (key != SortKey.NAME && !haveUsage) 0.4f else 1f
        }
    }

    /** Sort chip: tap to sort by it, tap again to flip direction. */
    private fun chip(label: String) = TextView(this).apply {
        text = label
        setPadding(28, 12, 28, 12)
        setOnClickListener {
            val key = chips.entries.first { it.value.first == this }.key
            if (key != SortKey.NAME && !haveUsage) return@setOnClickListener
            userSorted = true
            if (sortKey == key) descending = !descending
            else {
                sortKey = key
                descending = key != SortKey.NAME   // usage starts big-first
            }
            refreshShown()
        }
    }

    private fun iconFor(pkg: String): Drawable = icons.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) }
        catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
    }
}
