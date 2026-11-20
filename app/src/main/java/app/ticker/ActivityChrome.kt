package app.ticker

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout

internal fun Activity.installSystemBarPadding(root: View) {
    applySystemBarContrast()
    if (Build.VERSION.SDK_INT < 30) return
    @Suppress("DEPRECATION")
    window.setDecorFitsSystemWindows(false)
    applySystemBarContrast()
    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    root.requestApplyInsets()
}

@Suppress("DEPRECATION")
private fun Activity.applySystemBarContrast() {
    val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val lightUi = night == Configuration.UI_MODE_NIGHT_NO
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (lightUi) mask else 0, mask)
        return
    }
    val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val decor = window.decorView
    decor.systemUiVisibility = if (lightUi) {
        decor.systemUiVisibility or light
    } else {
        decor.systemUiVisibility and light.inv()
    }
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
