package app.ticker

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class AppsActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var empty: TextView
    private lateinit var master: Switch
    private lateinit var blocked: TextView
    private lateinit var adapter: AppAdapter
    private var all = emptyList<LaunchableApp>()
    private var loaded = false
    private var failed = false
    private var rendering = false

    private val listener = TickerController.Listener { renderMaster() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val root = findViewById<View>(R.id.apps_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPS)
        val list = findViewById<ListView>(R.id.app_list)
        empty = findViewById(R.id.empty)
        master = findViewById(R.id.master_switch)
        blocked = findViewById(R.id.master_blocked)
        master.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            controller.setMasterEnabled(checked)
            renderMaster()
        }
        adapter = AppAdapter()
        list.adapter = adapter
        list.emptyView = empty
        Thread {
            val load = loadLaunchableApps(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loaded = true
                when (load) {
                    is AppLoad.Ok -> {
                        all = load.apps
                        controller.noteInstalled(load.apps)
                        showApps()
                    }
                    is AppLoad.Failed -> {
                        failed = true
                        empty.setText(R.string.apps_load_failed)
                        adapter.submit(emptyList())
                    }
                }
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        controller.onSetupVisible()
        renderMaster()
        if (loaded && !failed) showApps()
    }

    override fun onStop() {
        controller.removeListener(listener)
        super.onStop()
    }

    private fun renderMaster() {
        val core = Grants.core(this)
        rendering = true
        master.isEnabled = core
        master.isChecked = controller.masterEnabled && core
        rendering = false
        blocked.visibility = if (core) View.GONE else View.VISIBLE
    }

    private fun showApps() {
        empty.setText(
            if (all.isEmpty()) R.string.no_launchable_apps else R.string.loading_apps
        )
        adapter.submit(all)
    }

    private inner class AppAdapter : BaseAdapter() {
        private var rows = emptyList<LaunchableApp>()

        fun submit(next: List<LaunchableApp>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): LaunchableApp = rows[position]
        override fun getItemId(position: Int): Long = rows[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_app, parent, false)
            val app = rows[position]
            val selected = controller.isSelected(app.packageName)
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_label).text = app.label
            view.findViewById<TextView>(R.id.app_package).text = app.packageName
            val toggle = view.findViewById<Switch>(R.id.app_switch)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = selected
            toggle.contentDescription = app.label
            toggle.setOnCheckedChangeListener { _, checked ->
                controller.setPackageSelected(app.packageName, checked)
            }
            return view
        }
    }
}
