package app.ticker

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

class AppsActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var adapter: AppAdapter
    private var all = emptyList<LaunchableApp>()
    private var loaded = false
    private var failed = false
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val root = findViewById<View>(R.id.apps_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPS)
        list = findViewById(R.id.app_list)
        empty = findViewById(R.id.empty)
        adapter = AppAdapter()
        list.adapter = adapter
        list.emptyView = empty
        query = savedInstanceState?.getString(KEY_QUERY).orEmpty()
        val search = findViewById<EditText>(R.id.search)
        search.setText(query)
        search.setSelection(query.length)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        Thread {
            val load = loadLaunchableApps(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loaded = true
                when (load) {
                    is AppLoad.Ok -> {
                        all = load.apps
                        controller.noteInstalled(load.apps)
                        applyFilter()
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

    override fun onResume() {
        super.onResume()
        controller.onSetupVisible()
        if (loaded) applyFilter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
    }

    private fun applyFilter() {
        if (failed) return
        val needle = query.trim()
        val shown = if (needle.isEmpty()) {
            all.filter { controller.isSelected(it.packageName) }
        } else {
            all.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
        empty.setText(
            when {
                !loaded -> R.string.loading_apps
                all.isEmpty() -> R.string.no_launchable_apps
                needle.isEmpty() && shown.isEmpty() -> R.string.no_chosen_apps
                shown.isEmpty() -> R.string.no_search_results
                else -> R.string.no_search_results
            }
        )
        adapter.submit(shown)
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
            view.findViewById<ImageView>(R.id.app_check).visibility =
                if (selected) View.VISIBLE else View.GONE
            view.contentDescription = if (selected) {
                getString(R.string.chosen_apps) + ", " + app.label
            } else {
                app.label
            }
            view.setOnClickListener {
                controller.setPackageSelected(app.packageName, !controller.isSelected(app.packageName))
                applyFilter()
            }
            return view
        }
    }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
