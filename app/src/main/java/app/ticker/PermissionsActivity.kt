package app.ticker

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class PermissionsActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var debugLog: TextView
    private val states = ArrayList<TextView>()
    private var rendering = false

    private val listener = TickerController.Listener { render() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        val root = findViewById<View>(R.id.permissions_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.PERMISSIONS)
        debugLog = findViewById(R.id.debug_log)
        findViewById<View>(R.id.share_debug).setOnClickListener {
            DecisionLog.share(this)
        }
        bindTheme()
        val rows = listOf(
            Perm(R.string.perm_notification, R.string.perm_notification_why) { openNotificationAccess(this) },
            Perm(R.string.perm_overlay, R.string.perm_overlay_why) { openOverlayAccess(this) },
            Perm(R.string.perm_usage, R.string.perm_usage_why) { openUsageAccess(this) }
        )
        val container = findViewById<LinearLayout>(R.id.permission_rows)
        val inflater = layoutInflater
        for (row in rows) {
            val view = inflater.inflate(R.layout.row_permission, container, false)
            view.findViewById<TextView>(R.id.perm_title).setText(row.title)
            view.findViewById<TextView>(R.id.perm_why).setText(row.why)
            view.findViewById<Button>(R.id.perm_open).setOnClickListener { row.open() }
            states.add(view.findViewById(R.id.perm_state))
            container.addView(view)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        controller.onSetupVisible()
        render()
    }

    override fun onStop() {
        controller.removeListener(listener)
        super.onStop()
    }

    private fun bindTheme() {
        val labels = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )
        val values = intArrayOf(AppTheme.LIGHT, AppTheme.DARK, AppTheme.SYSTEM)
        val spinner = findViewById<Spinner>(R.id.theme_spinner)
        val choices = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = choices
        rendering = true
        val current = values.indexOf(TickerPrefs(this).appTheme).coerceAtLeast(0)
        spinner.setSelection(current)
        rendering = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (rendering) return
                val next = values[position]
                if (TickerPrefs(this@PermissionsActivity).appTheme == next) return
                TickerPrefs(this@PermissionsActivity).appTheme = next
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun render() {
        val granted = listOf(
            Grants.notificationAccess(this),
            Grants.overlay(this),
            Grants.usage(this)
        )
        for (index in states.indices) {
            val ok = granted[index]
            states[index].setText(if (ok) R.string.state_granted else R.string.state_missing)
            states[index].setTextColor(getColor(if (ok) R.color.accent else R.color.missing))
        }
        debugLog.text = DecisionLog.recent(this, 12) ?: getString(R.string.debug_empty)
    }

    private data class Perm(
        val title: Int,
        val why: Int,
        val open: () -> Unit
    )
}
