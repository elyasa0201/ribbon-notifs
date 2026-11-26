package app.ticker

import android.content.Context
import android.provider.Settings

/**
 * While a chosen app is in front, heads-up pop-ups are switched off.
 * SystemUI drops a pop-up that is already up and does not show a new one.
 * The shade list is left in place. The previous value is put back when that
 * app leaves, so pop-ups behave as before everywhere else. This does not
 * change Do Not Disturb and does not pull the shade down.
 */
internal class PopupHold(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var held = false
    private var loggedLeft = false

    fun recover() {
        if (!prefs.contains(PRIOR)) return
        val prior = prefs.getInt(PRIOR, ON)
        if (write(prior)) {
            prefs.edit().remove(PRIOR).apply()
        }
        held = false
    }

    fun arm() {
        if (held) return
        val prior = read()
        if (!write(OFF)) {
            if (!loggedLeft) {
                loggedLeft = true
                DecisionLog.note(context, "popup left reason=settings")
            }
            return
        }
        prefs.edit().putInt(PRIOR, prior).apply()
        held = true
        DecisionLog.note(context, "popup off")
    }

    fun disarm() {
        if (!held && !prefs.contains(PRIOR)) return
        val prior = if (prefs.contains(PRIOR)) prefs.getInt(PRIOR, ON) else read()
        if (!write(prior)) return
        prefs.edit().remove(PRIOR).apply()
        held = false
        DecisionLog.note(context, "popup on")
    }

    private fun read(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY, ON)
        } catch (_: Settings.SettingNotFoundException) {
            ON
        }
    }

    private fun write(value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, KEY, value)
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val PREFS = "ticker_popup_hold"
        const val PRIOR = "prior"
        const val KEY = "heads_up_notifications_enabled"
        const val ON = 1
        const val OFF = 0
    }
}
