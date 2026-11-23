package app.ticker

import android.content.Context
import android.content.Intent
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
    private lateinit var masterRow: View
    private lateinit var master: Switch
    private lateinit var blocked: TextView
    private lateinit var ribbon: StripView
    private lateinit var adapter: SelectedAdapter
    private var all = emptyList<LaunchableApp>()
    private var loaded = false
    private var failed = false
    private var rendering = false
    private var screenVisible = false

    private val listener = TickerController.Listener { renderMaster() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val root = findViewById<View>(R.id.apps_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPS)
        val tile = findViewById<View>(R.id.master_tile)
        tile.clipToOutline = true
        ribbon = findViewById(R.id.demo_ribbon)
        val list = findViewById<ListView>(R.id.app_list)
        empty = findViewById(R.id.empty)
        masterRow = findViewById(R.id.master_row)
        master = findViewById(R.id.master_switch)
        blocked = findViewById(R.id.master_blocked)
        master.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            controller.setMasterEnabled(checked)
            renderMaster()
        }
        masterRow.setOnClickListener {
            if (!master.isEnabled) return@setOnClickListener
            master.toggle()
        }
        val open = View.OnClickListener { openPicker() }
        findViewById<View>(R.id.selected_header).setOnClickListener(open)
        empty.setOnClickListener(open)
        adapter = SelectedAdapter()
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, _, _ -> openPicker() }
        Thread {
            val load = loadLaunchableApps(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loaded = true
                when (load) {
                    is AppLoad.Ok -> {
                        all = load.apps
                        controller.noteInstalled(load.apps)
                        showSelected()
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
        screenVisible = true
        controller.addListener(listener)
        syncRibbon()
    }

    override fun onResume() {
        super.onResume()
        controller.onSetupVisible()
        renderMaster()
        if (loaded && !failed) showSelected()
    }

    override fun onStop() {
        screenVisible = false
        ribbon.stopDemo()
        ribbon.visibility = View.GONE
        controller.removeListener(listener)
        super.onStop()
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun renderMaster() {
        val core = Grants.core(this)
        rendering = true
        master.isEnabled = core
        masterRow.isEnabled = core
        master.isChecked = controller.masterEnabled && core
        rendering = false
        blocked.visibility = if (core) View.GONE else View.VISIBLE
        syncRibbon()
    }

    private fun syncRibbon() {
        val show = screenVisible && master.isChecked
        if (!show) {
            ribbon.stopDemo()
            ribbon.visibility = View.GONE
            return
        }
        ribbon.visibility = View.VISIBLE
        ribbon.playDemo(controller.stripAppearance(), getString(R.string.preview_line))
    }

    private fun showSelected() {
        val chosen = all.filter { controller.isSelected(it.packageName) }
        empty.setText(R.string.no_chosen_apps)
        adapter.submit(chosen)
    }

    private inner class SelectedAdapter : BaseAdapter() {
        private var rows = emptyList<LaunchableApp>()

        fun submit(next: List<LaunchableApp>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): LaunchableApp = rows[position]
        override fun getItemId(position: Int): Long = rows[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_selected, parent, false)
            val app = rows[position]
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_label).text = app.label
            return view
        }
    }
}
