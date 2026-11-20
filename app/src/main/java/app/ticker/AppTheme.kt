package app.ticker

import android.content.Context
import android.content.res.Configuration

/**
 * Light, dark, or the phone's setting. Dark is the default. This only changes
 * the app screens. The ribbon keeps its own colours.
 */
internal object AppTheme {
    const val DARK = 0
    const val LIGHT = 1
    const val SYSTEM = 2

    fun normalize(value: Int): Int = when (value) {
        LIGHT, SYSTEM -> value
        else -> DARK
    }

    fun wrap(base: Context): Context {
        val mode = TickerPrefs(base).appTheme
        if (mode == SYSTEM) return base
        val night = if (mode == LIGHT) {
            Configuration.UI_MODE_NIGHT_NO
        } else {
            Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(base.resources.configuration)
        val current = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (current == night) return base
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }
}
