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
    private var attempted = false
    private var failure: String? = null

    /** True after this session has tried to turn the heads-up switch off. */
    fun hideRan(): Boolean = attempted

    /**
     * Exception class and the call that failed, or null when the last try
     * succeeded or no try has been made.
     */
    fun failure(): String? = failure

    /** True when the heads-up switch is off, so a new pop-up stays down. */
    fun switchOff(): Boolean {
        return try {
            Settings.Global.getString(context.contentResolver, KEY) == "0"
        } catch (_: RuntimeException) {
            false
        }
    }

    fun recover() {
        if (!prefs.contains(PRIOR)) return
        val prior = prefs.getInt(PRIOR, ON)
        if (write(prior).ok) {
            prefs.edit().remove(PRIOR).apply()
        }
        held = false
    }

    fun arm() {
        if (held) return
        attempted = true
        val prior = read()
        val outcome = write(OFF)
        if (!outcome.ok) {
            failure = outcome.failure
            DecisionLog.note(context, "hide ran call=Settings.Global.putInt $KEY fail=$failure")
            return
        }
        failure = null
        prefs.edit().putInt(PRIOR, prior).apply()
        held = true
        DecisionLog.note(context, "hide ran call=Settings.Global.putInt $KEY fail=none")
    }

    fun disarm() {
        if (!held && !prefs.contains(PRIOR)) return
        val prior = if (prefs.contains(PRIOR)) prefs.getInt(PRIOR, ON) else read()
        val outcome = write(prior)
        if (!outcome.ok) {
            DecisionLog.note(
                context,
                "hide restore call=Settings.Global.putInt $KEY fail=${outcome.failure}"
            )
            return
        }
        prefs.edit().remove(PRIOR).apply()
        held = false
        failure = null
        DecisionLog.note(context, "hide restore call=Settings.Global.putInt $KEY fail=none")
    }

    private fun read(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY, ON)
        } catch (_: Settings.SettingNotFoundException) {
            ON
        }
    }

    private fun write(value: Int): Write {
        return try {
            Settings.Global.putInt(context.contentResolver, KEY, value)
            Write(ok = true, failure = null)
        } catch (error: Exception) {
            Write(
                ok = false,
                failure = "${error.javaClass.simpleName} Settings.Global.putInt $KEY"
            )
        }
    }

    private class Write(val ok: Boolean, val failure: String?)

    private companion object {
        const val PREFS = "ticker_popup_hold"
        const val PRIOR = "prior"
        const val KEY = "heads_up_notifications_enabled"
        const val ON = 1
        const val OFF = 0
    }
}
