package app.ticker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

internal fun openNotificationAccess(activity: Activity) {
    open(activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

internal fun openOverlayAccess(activity: Activity) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${activity.packageName}")
    )
    open(activity, intent)
}

internal fun openUsageAccess(activity: Activity) {
    open(activity, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

internal fun openShadeAccess(activity: Activity) {
    // The detail screen is a hidden system intent. One UI finds it and then
    // throws SecurityException, which closed App Settings. The public list
    // is the screen that holds the Shade access toggle.
    open(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun open(activity: Activity, intent: Intent) {
    try {
        activity.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
