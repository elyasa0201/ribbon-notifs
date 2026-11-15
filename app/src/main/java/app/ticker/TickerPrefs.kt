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

    var ruleShape: String?
        get() = prefs.getString(KEY_RULE_SHAPE, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_RULE_SHAPE, value).apply()
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

    var textSizeSp: Int
        get() = StripSteps.nearestText(prefs.getInt(KEY_TEXT, StripAppearance.DEFAULT_TEXT_SP))
        set(value) {
            prefs.edit().putInt(KEY_TEXT, StripSteps.nearestText(value)).apply()
        }

    var scrollSpeedDp: Float
        get() {
            val milli = if (prefs.contains(KEY_SPEED_SCALE)) {
                prefs.getInt(KEY_SPEED_SCALE, SPEED_SCALE_DEFAULT)
            } else {
                StripSteps.speedScaleMilli[
                    StripSteps.nearestSpeedIndex(
                        prefs.getInt(KEY_SPEED, StripAppearance.DEFAULT_SPEED_DP).toFloat()
                    )
                ]
            }
            val index = StripSteps.speedScaleMilli.indexOf(milli)
            return StripSteps.speedDp(if (index < 0) 2 else index)
        }
        set(value) {
            val index = StripSteps.nearestSpeedIndex(value)
            prefs.edit().putInt(KEY_SPEED_SCALE, StripSteps.speedScaleMilli[index]).apply()
        }

    var stripBackground: Int
        get() = prefs.getInt(KEY_BACKGROUND, StripAppearance.DEFAULT_BACKGROUND)
        set(value) {
            prefs.edit().putInt(KEY_BACKGROUND, value).apply()
        }

    private companion object {
        const val NAME = "ticker"
        const val KEY_MASTER = "master_enabled"
        const val KEY_RULE = "zen_rule_id"
        const val KEY_RULE_SHAPE = "zen_rule_shape"
        const val KEY_PACKAGES = "selected_packages"
        const val KEY_TEXT = "strip_text_sp"
        const val KEY_SPEED = "strip_speed_dp"
        const val KEY_SPEED_SCALE = "strip_speed_scale"
        const val KEY_BACKGROUND = "strip_background"
        const val SPEED_SCALE_DEFAULT = 1000
    }
}
