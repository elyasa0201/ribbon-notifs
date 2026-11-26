package app.ticker

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class TickerListenerService : NotificationListenerService() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private val headsUpQuiet = HeadsUpQuiet(this)

    override fun onListenerConnected() {
        controller.activeNotifications = {
            try {
                this@TickerListenerService.activeNotifications?.filterNotNull().orEmpty()
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
        controller.restoreQuietChannels = { headsUpQuiet.restoreAll() }
        val restored = headsUpQuiet.restoreAll()
        val migrated = requestAlertTypes()
        val filter = if (migrated) "alerting+silent+conversations" else "unchanged"
        DecisionLog.note(this, "listener connected filter=$filter")
        controller.onListenerConnected()
        for (line in restored) DecisionLog.note(this, line)
    }

    override fun onListenerDisconnected() {
        controller.restoreQuietChannels = null
        controller.activeNotifications = null
        controller.onListenerDisconnected()
        requestRebind(ComponentName(this, TickerListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        controller.onNotificationPosted(sbn, currentRankingOrNull())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        controller.onNotificationPosted(sbn, rankingMap)
    }

    private fun currentRankingOrNull(): RankingMap? {
        return try {
            currentRanking
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun requestAlertTypes(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val types = FLAG_FILTER_TYPE_CONVERSATIONS or
            FLAG_FILTER_TYPE_ALERTING or
            FLAG_FILTER_TYPE_SILENT
        return try {
            migrateNotificationFilter(types, emptyList())
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}
