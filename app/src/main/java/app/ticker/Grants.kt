package app.ticker

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings

internal object Grants {
    fun notificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val mine = ComponentName(context, TickerListenerService::class.java)
        return flat.split(':').any { token ->
            val parsed = ComponentName.unflattenFromString(token)
            parsed == mine || token.equals(mine.flattenToString(), ignoreCase = true)
        }
    }

    fun notificationAssistant(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_assistant"
        ) ?: return false
        val mine = ComponentName(context, TickerAssistantService::class.java)
        val parsed = ComponentName.unflattenFromString(flat)
        return parsed == mine || flat.equals(mine.flattenToString(), ignoreCase = true)
    }

    fun popupCollapse(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!enabled) return false
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = ComponentName(context, PopupCollapseService::class.java)
        return flat.split(':').any { token ->
            val parsed = ComponentName.unflattenFromString(token)
            parsed == mine || token.equals(mine.flattenToString(), ignoreCase = true)
        }
    }

    fun overlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun usage(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun core(context: Context): Boolean {
        return notificationAccess(context) && usage(context) && overlay(context)
    }
}
