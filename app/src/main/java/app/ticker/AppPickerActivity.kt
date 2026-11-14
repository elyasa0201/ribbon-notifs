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
import android.widget.Switch
import android.widget.TextView

class AppPickerActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var adapter: AppAdapter
    private var all = emptyList<LaunchableApp>()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        val root = findViewById<View>(R.id.picker_root)
        installSystemBarPadding(root)
        capContentWidth(root)
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
                when (load) {
                    is AppLoad.Ok -> {
                        all = load.apps
                        controller.noteInstalled(load.apps)
                        applyFilter()
                    }
                    is AppLoad.Failed -> {
                        empty.setText(R.string.apps_load_failed)
                        adapter.submit(emptyList())
                    }
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_QUERY, query)
    }

    private fun applyFilter() {
        val needle = query.trim()
        val shown = if (needle.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
        empty.setText(
            when {
                all.isEmpty() -> R.string.no_launchable_apps
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
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_label).text = app.label
            val toggle = view.findViewById<Switch>(R.id.app_switch)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = controller.isSelected(app.packageName)
            toggle.contentDescription = app.label
            toggle.setOnCheckedChangeListener { _, checked ->
                controller.setPackageSelected(app.packageName, checked)
            }
            view.setOnClickListener { toggle.toggle() }
            return view
        }
    }

    private companion object {
        const val KEY_QUERY = "query"
    }
}
