package app.ticker

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var debugLog: TextView
    private val states = ArrayList<TextView>()
    private val themeTitles = ArrayList<TextView>()
    private val themeStates = ArrayList<TextView>()
    private val themeValues = intArrayOf(AppTheme.LIGHT, AppTheme.DARK, AppTheme.SYSTEM)

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
            Perm(R.string.perm_usage, R.string.perm_usage_why) { openUsageAccess(this) },
            Perm(R.string.perm_shade, R.string.perm_shade_why) { openShadeAccess(this) }
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
        val labels = intArrayOf(R.string.theme_light, R.string.theme_dark, R.string.theme_system)
        val container = findViewById<LinearLayout>(R.id.theme_rows)
        val inflater = layoutInflater
        for (index in labels.indices) {
            val view = inflater.inflate(R.layout.row_choice, container, false)
            val title = view.findViewById<TextView>(R.id.choice_title)
            val state = view.findViewById<TextView>(R.id.choice_state)
            title.setText(labels[index])
            val value = themeValues[index]
            view.findViewById<View>(R.id.choice_row).setOnClickListener {
                if (TickerPrefs(this).appTheme == value) return@setOnClickListener
                TickerPrefs(this).appTheme = value
                recreate()
            }
            themeTitles.add(title)
            themeStates.add(state)
            container.addView(view)
        }
        paintTheme()
    }

    private fun paintTheme() {
        val selected = TickerPrefs(this).appTheme
        for (index in themeValues.indices) {
            val on = themeValues[index] == selected
            themeTitles[index].setTextColor(getColor(if (on) R.color.accent else R.color.ink))
            themeStates[index].text = if (on) getString(R.string.choice_on) else ""
            themeStates[index].setTextColor(getColor(R.color.accent))
        }
    }

    private fun render() {
        val granted = listOf(
            Grants.notificationAccess(this),
            Grants.overlay(this),
            Grants.usage(this),
            Grants.shadeAccess(this)
        )
        for (index in states.indices) {
            val ok = granted[index]
            states[index].setText(if (ok) R.string.state_granted else R.string.state_missing)
            states[index].setTextColor(getColor(if (ok) R.color.accent else R.color.missing))
        }
        debugLog.text = DecisionLog.recent(this, 12) ?: getString(R.string.debug_empty)
        paintTheme()
    }

    private data class Perm(
        val title: Int,
        val why: Int,
        val open: () -> Unit
    )
}
