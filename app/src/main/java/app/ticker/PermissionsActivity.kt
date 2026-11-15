package app.ticker

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class PermissionsActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var master: Switch
    private lateinit var blocked: TextView
    private lateinit var warning: TextView
    private lateinit var status: TextView
    private val states = ArrayList<TextView>()
    private var rendering = false

    private val listener = TickerController.Listener { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        val root = findViewById<View>(R.id.permissions_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.PERMISSIONS)
        master = findViewById(R.id.master_switch)
        blocked = findViewById(R.id.master_blocked)
        warning = findViewById(R.id.policy_warning)
        status = findViewById(R.id.rule_status)
        master.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            controller.setMasterEnabled(checked)
            render()
        }
        findViewById<View>(R.id.turn_rule_off).setOnClickListener {
            controller.turnRuleOffNow()
            render()
        }
        findViewById<View>(R.id.rule_info).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.rule_off_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        val rows = listOf(
            Perm(R.string.perm_notification, R.string.perm_notification_why) { openNotificationAccess(this) },
            Perm(R.string.perm_policy, R.string.perm_policy_why) { openPolicyAccess(this) },
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

    private fun render() {
        val granted = listOf(
            Grants.notificationAccess(this),
            Grants.policy(this),
            Grants.overlay(this),
            Grants.usage(this)
        )
        for (index in states.indices) {
            val ok = granted[index]
            states[index].setText(if (ok) R.string.state_granted else R.string.state_missing)
            states[index].setTextColor(getColor(if (ok) R.color.accent else R.color.missing))
        }
        val core = Grants.core(this)
        rendering = true
        master.isEnabled = core
        master.isChecked = controller.masterEnabled && core
        rendering = false
        blocked.visibility = if (core) View.GONE else View.VISIBLE
        warning.visibility = if (Grants.policy(this)) View.GONE else View.VISIBLE
        val saved = if (controller.ruleSaved) getString(R.string.rule_saved) else getString(R.string.rule_not_saved)
        val active = if (controller.isRuleActive) getString(R.string.rule_active) else getString(R.string.rule_inactive)
        val extra = if (controller.ruleCreateFailed) "\n${getString(R.string.rule_create_failed)}" else ""
        status.text = "$saved\n$active$extra"
    }

    private data class Perm(
        val title: Int,
        val why: Int,
        val open: () -> Unit
    )
}
