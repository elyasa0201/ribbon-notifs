package app.ticker

import android.content.Context

internal class TickerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MASTER, value).apply()
        }

    var ruleId: String?
        get() = prefs.getString(KEY_RULE, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_RULE, value).apply()
        }

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_PACKAGES, HashSet(value)).apply()
        }

    fun setPackageSelected(packageName: String, selected: Boolean) {
        val next = selectedPackages.toMutableSet()
        if (selected) next.add(packageName) else next.remove(packageName)
        selectedPackages = next
    }

    fun retainInstalled(installed: Set<String>) {
        val kept = selectedPackages.intersect(installed)
        if (kept != selectedPackages) selectedPackages = kept
    }

    private companion object {
        const val NAME = "ticker"
        const val KEY_MASTER = "master_enabled"
        const val KEY_RULE = "zen_rule_id"
        const val KEY_PACKAGES = "selected_packages"
    }
}
