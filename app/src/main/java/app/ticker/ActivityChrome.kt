package app.ticker

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout

internal fun Activity.installSystemBarPadding(root: View) {
    if (Build.VERSION.SDK_INT < 30) return
    @Suppress("DEPRECATION")
    window.setDecorFitsSystemWindows(false)
    window.insetsController?.setSystemBarsAppearance(
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    root.requestApplyInsets()
}

internal fun Activity.capContentWidth(root: View) {
    val max = (560 * resources.displayMetrics.density).toInt()
    root.post {
        val parent = root.parent as? View ?: return@post
        if (parent.width > max) {
            val params = root.layoutParams
            params.width = max
            if (params is FrameLayout.LayoutParams) {
                params.gravity = Gravity.CENTER_HORIZONTAL
            }
            root.layoutParams = params
        }
    }
}
