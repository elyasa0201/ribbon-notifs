package app.ticker

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class TickerListenerService : NotificationListenerService() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    override fun onListenerConnected() {
        controller.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        controller.onListenerDisconnected()
        requestRebind(ComponentName(this, TickerListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        controller.onNotificationPosted(sbn, rankingMap)
    }
}
