package app.ticker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Opens the notification shade and closes it again. That is the gesture
 * that takes down a heads-up already on screen without removing the shade
 * entry.
 */
class ShadePulseService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        active = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (active === this) active = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun toggleShade(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    companion object {
        @Volatile
        private var active: ShadePulseService? = null

        fun pulse(): Boolean {
            val service = active ?: return false
            if (!service.toggleShade()) return false
            service.main.postDelayed({ service.toggleShade() }, CLOSE_DELAY_MS)
            return true
        }

        private const val CLOSE_DELAY_MS = 500L
    }
}
