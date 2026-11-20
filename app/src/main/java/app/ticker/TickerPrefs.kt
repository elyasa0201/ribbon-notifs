package app.ticker

import android.content.Context
import android.graphics.Color

internal class TickerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MASTER, value).apply()
        }

    var appTheme: Int
        get() = AppTheme.normalize(prefs.getInt(KEY_APP_THEME, AppTheme.DARK))
        set(value) {
            prefs.edit().putInt(KEY_APP_THEME, AppTheme.normalize(value)).apply()
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

    var textColor: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, StripAppearance.DEFAULT_TEXT_COLOR)
        set(value) {
            prefs.edit().putInt(KEY_TEXT_COLOR, value or OPAQUE).apply()
        }

    var backgroundRgb: Int
        get() {
            if (prefs.contains(KEY_BG_RGB)) {
                return prefs.getInt(KEY_BG_RGB, StripAppearance.DEFAULT_BG_RGB) or OPAQUE
            }
            return rgbOf(legacyBackground())
        }
        set(value) {
            prefs.edit().putInt(KEY_BG_RGB, value or OPAQUE).apply()
        }

    var backgroundAlpha: Int
        get() {
            if (prefs.contains(KEY_BG_ALPHA)) {
                return prefs.getInt(KEY_BG_ALPHA, StripAppearance.DEFAULT_BG_ALPHA).coerceIn(0, 255)
            }
            val legacy = legacyBackground()
            return if (Color.alpha(legacy) == 0) StripAppearance.DEFAULT_BG_ALPHA else Color.alpha(legacy)
        }
        set(value) {
            prefs.edit().putInt(KEY_BG_ALPHA, value.coerceIn(0, 255)).apply()
        }

    var backgroundNone: Boolean
        get() {
            if (prefs.contains(KEY_BG_NONE)) return prefs.getBoolean(KEY_BG_NONE, false)
            return prefs.contains(KEY_BACKGROUND) && Color.alpha(legacyBackground()) == 0
        }
        set(value) {
            prefs.edit().putBoolean(KEY_BG_NONE, value).apply()
        }

    fun composedBackground(): Int {
        if (backgroundNone) return StripAppearance.NO_BACKGROUND
        val rgb = backgroundRgb
        return Color.argb(backgroundAlpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    private fun legacyBackground(): Int {
        return prefs.getInt(KEY_BACKGROUND, StripAppearance.DEFAULT_BACKGROUND)
    }

    private fun rgbOf(color: Int): Int {
        if (Color.alpha(color) == 0) return StripAppearance.DEFAULT_BG_RGB
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        const val NAME = "ticker"
        const val KEY_MASTER = "master_enabled"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_RULE = "zen_rule_id"
        const val KEY_RULE_SHAPE = "zen_rule_shape"
        const val KEY_PACKAGES = "selected_packages"
        const val KEY_TEXT = "strip_text_sp"
        const val KEY_SPEED = "strip_speed_dp"
        const val KEY_SPEED_SCALE = "strip_speed_scale"
        const val KEY_BACKGROUND = "strip_background"
        const val KEY_TEXT_COLOR = "strip_text_color"
        const val KEY_BG_RGB = "strip_bg_rgb"
        const val KEY_BG_ALPHA = "strip_bg_alpha"
        const val KEY_BG_NONE = "strip_bg_none"
        const val OPAQUE = 0xFF000000.toInt()
        const val SPEED_SCALE_DEFAULT = 1000
    }
}
