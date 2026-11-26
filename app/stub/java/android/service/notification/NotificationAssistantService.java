package android.service.notification;

import android.app.NotificationChannel;

/**
 * Compile-only stand-in for the hidden framework class. The APK does not
 * package this file. The device loads the real class, which has the same
 * abstract methods and the same adjust entry points.
 */
public abstract class NotificationAssistantService extends NotificationListenerService {
    public static final String SERVICE_INTERFACE =
            "android.service.notification.NotificationAssistantService";

    public abstract void onNotificationSnoozedUntilContext(StatusBarNotification sbn,
            String snoozeCriterionId);

    public abstract Adjustment onNotificationEnqueued(StatusBarNotification sbn);

    public Adjustment onNotificationEnqueued(StatusBarNotification sbn,
            NotificationChannel channel, RankingMap rankingMap) {
        return onNotificationEnqueued(sbn);
    }

    public final void adjustNotification(Adjustment adjustment) {
    }

    public final void adjustNotifications(java.util.List<Adjustment> adjustments) {
    }
}
