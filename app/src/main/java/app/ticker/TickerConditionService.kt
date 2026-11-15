@file:Suppress("DEPRECATION")

package app.ticker

import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ConditionProviderService

/**
 * One UI still requires an enabled condition provider before it will save an
 * AutomaticZenRule. The controller decides the state; this service only
 * repeats that state when the system subscribes.
 */
class TickerConditionService : ConditionProviderService() {
    override fun onConnected() {
        connected = this
    }

    override fun onDestroy() {
        if (connected == this) connected = null
        super.onDestroy()
    }

    override fun onSubscribe(conditionId: Uri) {
        notifyCurrent(conditionId)
    }

    override fun onUnsubscribe(conditionId: Uri) = Unit

    private fun notifyCurrent(conditionId: Uri) {
        val active = (application as TickerApp).controller.ruleDesired
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = if (active) "A selected app is open" else "Off"
        try {
            notifyCondition(Condition(conditionId, summary, state))
        } catch (_: RuntimeException) {
            // Not subscribed yet, or the system rejected this id.
        }
    }

    companion object {
        private var connected: TickerConditionService? = null

        fun publish() {
            val service = connected ?: return
            service.notifyCurrent(Uri.parse("condition://${service.packageName}/armed"))
        }
    }
}
