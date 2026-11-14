package app.ticker

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.ImageView
import android.widget.TextView

internal enum class AppTab {
    APPS,
    APPEARANCE,
    PERMISSIONS
}

internal fun Activity.bindTabs(selected: AppTab) {
    styleTab(R.id.tab_apps_pill, R.id.tab_apps_icon, R.id.tab_apps_label, selected == AppTab.APPS)
    styleTab(
        R.id.tab_appearance_pill,
        R.id.tab_appearance_icon,
        R.id.tab_appearance_label,
        selected == AppTab.APPEARANCE
    )
    styleTab(
        R.id.tab_permissions_pill,
        R.id.tab_permissions_icon,
        R.id.tab_permissions_label,
        selected == AppTab.PERMISSIONS
    )
    findViewById<android.view.View>(R.id.tab_apps).setOnClickListener { openTab(AppsActivity::class.java) }
    findViewById<android.view.View>(R.id.tab_appearance).setOnClickListener {
        openTab(AppearanceActivity::class.java)
    }
    findViewById<android.view.View>(R.id.tab_permissions).setOnClickListener {
        openTab(PermissionsActivity::class.java)
    }
}

private fun Activity.styleTab(pillId: Int, iconId: Int, labelId: Int, selected: Boolean) {
    val color = getColor(if (selected) R.color.ink else R.color.muted)
    findViewById<android.view.View>(pillId).setBackgroundResource(
        if (selected) R.drawable.bg_tab_selected else 0
    )
    findViewById<ImageView>(iconId).imageTintList = ColorStateList.valueOf(color)
    findViewById<TextView>(labelId).setTextColor(color)
}

private fun Activity.openTab(target: Class<out Activity>) {
    if (javaClass == target) return
    startActivity(Intent(this, target))
    finish()
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
