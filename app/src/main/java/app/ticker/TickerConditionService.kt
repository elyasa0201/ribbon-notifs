@file:Suppress("DEPRECATION")

package app.ticker

import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ConditionProviderService

/**
 * An older install may still have a zen rule that subscribes here. The answer
 * is always off. This app does not turn Do Not Disturb on.
 */
class TickerConditionService : ConditionProviderService() {
    override fun onConnected() = Unit

    override fun onSubscribe(conditionId: Uri) {
        try {
            notifyCondition(Condition(conditionId, "Off", Condition.STATE_FALSE))
        } catch (_: RuntimeException) {
            // Not subscribed yet, or the system rejected this id.
        }
    }

    override fun onUnsubscribe(conditionId: Uri) = Unit
}
