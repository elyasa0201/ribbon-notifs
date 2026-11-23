package app.ticker

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

/** Every launchable app, on or off. A row tap flips the switch. */
class AppPickerActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var empty: TextView
    private lateinit var adapter: AppAdapter
    private var all = emptyList<LaunchableApp>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        val root = findViewById<View>(R.id.picker_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        val list = findViewById<ListView>(R.id.app_list)
        empty = findViewById(R.id.empty)
        adapter = AppAdapter()
        list.adapter = adapter
        list.emptyView = empty
        Thread {
            val load = loadLaunchableApps(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (load) {
                    is AppLoad.Ok -> {
                        all = load.apps
                        controller.noteInstalled(load.apps)
                        empty.setText(
                            if (all.isEmpty()) R.string.no_launchable_apps else R.string.loading_apps
                        )
                        adapter.submit(all)
                    }
                    is AppLoad.Failed -> {
                        empty.setText(R.string.apps_load_failed)
                        adapter.submit(emptyList())
                    }
                }
            }
        }.start()
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
            view.setOnClickListener { toggle.toggle() }
            return view
        }
    }
}
