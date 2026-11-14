package app.ticker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

internal data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

internal sealed class AppLoad {
    data class Ok(val apps: List<LaunchableApp>) : AppLoad()
    data class Failed(val message: String) : AppLoad()
}

internal fun loadLaunchableApps(context: Context): AppLoad {
    return try {
        val manager = context.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(probe, 0)
        }
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        for (info in resolved) {
            val packageName = info.activityInfo?.packageName ?: continue
            if (packageName == context.packageName) continue
            val current = byPackage[packageName]
            if (current == null || info.preferredOrder > current.preferredOrder) {
                byPackage[packageName] = info
            }
        }
        val apps = byPackage.values.map { info ->
            LaunchableApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(manager).toString().ifBlank { info.activityInfo.packageName },
                icon = info.loadIcon(manager)
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        AppLoad.Ok(apps)
    } catch (error: RuntimeException) {
        AppLoad.Failed(error.javaClass.simpleName)
    }
}
