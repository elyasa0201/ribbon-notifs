package app.ticker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.Adjustment
import android.service.notification.NotificationAssistantService
import android.service.notification.StatusBarNotification

/**
 * Sets a high-importance alert to default before it is posted, while a chosen
 * app is in front. Default does not pop and the shade row stays. The original
 * importance is put back when that app leaves. Calls, alarms, and full-screen
 * intents are not changed. This does not cancel, snooze, or turn on Do Not
 * Disturb, and it does not open the shade.
 */
class TickerAssistantService : NotificationAssistantService() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private val lowered = LinkedHashMap<String, Held>()

    override fun onListenerConnected() {
        controller.demoteHeadsUp = { sbn, channel -> demote(sbn, channel) }
        controller.restoreDemoted = { restore() }
        DecisionLog.note(this, "assistant connected")
        controller.onAssistantConnected()
    }

    override fun onListenerDisconnected() {
        controller.demoteHeadsUp = null
        controller.restoreDemoted = null
        DecisionLog.note(this, "assistant disconnected")
    }

    override fun onNotificationSnoozedUntilContext(
        sbn: StatusBarNotification,
        snoozeCriterionId: String
    ) {
    }

    override fun onNotificationEnqueued(sbn: StatusBarNotification): Adjustment? {
        return demotion(sbn, rankedImportance(sbn))
    }

    override fun onNotificationEnqueued(
        sbn: StatusBarNotification,
        channel: NotificationChannel,
        rankingMap: RankingMap
    ): Adjustment? {
        val adjustment = demotion(sbn, channel.importance) ?: return null
        DecisionLog.note(this, quietLine(sbn, channel.importance))
        return adjustment
    }

    private fun demote(sbn: StatusBarNotification, channel: NotificationChannel?): String? {
        val importance = channel?.importance ?: rankedImportance(sbn) ?: return null
        val adjustment = demotion(sbn, importance) ?: return null
        return try {
            adjustNotification(adjustment)
            quietLine(sbn, importance)
        } catch (_: RuntimeException) {
            forget(sbn.key)
            leftLine(sbn)
        }
    }

    private fun demotion(sbn: StatusBarNotification, importance: Int?): Adjustment? {
        if (!controller.suppressesPopup(sbn)) return null
        val user = sbn.user ?: return null
        if (importance == null || importance < NotificationManager.IMPORTANCE_HIGH) return null
        val original = importance
        synchronized(lowered) {
            if (lowered.containsKey(sbn.key)) return null
            lowered[sbn.key] = Held(
                pkg = sbn.packageName,
                key = sbn.key,
                user = user,
                channelId = sbn.notification?.channelId,
                importance = original
            )
        }
        return adjustment(sbn.packageName, sbn.key, user, NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun restore(): List<String> {
        val pending = synchronized(lowered) {
            val copy = lowered.values.toList()
            lowered.clear()
            copy
        }
        if (pending.isEmpty()) return emptyList()
        val adjustments = pending.map {
            adjustment(it.pkg, it.key, it.user, it.importance)
        }
        return try {
            adjustNotifications(adjustments)
            pending.map { held ->
                "heads-up restore pkg=${held.pkg} channel=${held.channelId ?: "none"} " +
                    "importance=${NotificationManager.IMPORTANCE_DEFAULT}->${held.importance} " +
                    "reason=assistant"
            }
        } catch (_: RuntimeException) {
            pending.map { held ->
                "heads-up restore pkg=${held.pkg} channel=${held.channelId ?: "none"} reason=assistant"
            }
        }
    }

    private fun rankedImportance(sbn: StatusBarNotification): Int? {
        val ranking = Ranking()
        val ranked = try {
            currentRanking?.getRanking(sbn.key, ranking) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!ranked) return null
        return ranking.importance
    }

    private fun forget(key: String) {
        synchronized(lowered) { lowered.remove(key) }
    }

    private fun adjustment(pkg: String, key: String, user: UserHandle, importance: Int): Adjustment {
        val signals = Bundle()
        signals.putInt(Adjustment.KEY_IMPORTANCE, importance)
        return Adjustment(pkg, key, signals, "popup", user)
    }

    private fun quietLine(sbn: StatusBarNotification, importance: Int): String {
        val channelId = sbn.notification?.channelId ?: "none"
        return "heads-up quiet pkg=${sbn.packageName} channel=$channelId " +
            "importance=$importance->${NotificationManager.IMPORTANCE_DEFAULT} reason=assistant"
    }

    private fun leftLine(sbn: StatusBarNotification): String {
        val channelId = sbn.notification?.channelId ?: "none"
        return "heads-up left pkg=${sbn.packageName} channel=$channelId reason=assistant"
    }

    private class Held(
        val pkg: String,
        val key: String,
        val user: UserHandle,
        val channelId: String?,
        val importance: Int
    )
}
