package app.ticker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Collapses a heads-up that is already on screen. The notifications action
 * only expands the shade, so it is never used. Collapse is requested only
 * while that action is offered, which is while the pop-up is up.
 */
class PopupCollapseService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val waiting = LinkedHashSet<String>()
    private var ticking = false
    private var deadline = 0L
    private var collapsed = false

    override fun onServiceConnected() {
        active = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (active === this) active = null
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        stop()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun enqueue(pkg: String) {
        waiting.add(pkg)
        deadline = SystemClock.elapsedRealtime() + WINDOW_MS
        if (ticking) return
        ticking = true
        collapsed = false
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private val tick = Runnable { onTick() }

    private fun onTick() {
        if (!ticking || active !== this) return
        val now = SystemClock.elapsedRealtime()
        if (dismissReady()) {
            if (performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) {
                collapsed = true
            }
        }
        if (now >= deadline) {
            val pkgs = waiting.joinToString(",")
            waiting.clear()
            ticking = false
            if (pkgs.isNotEmpty()) {
                val verb = if (collapsed) "retract" else "left"
                DecisionLog.note(this, "heads-up $verb pkg=$pkgs reason=collapse")
            }
            return
        }
        main.postDelayed(tick, POLL_MS)
    }

    private fun dismissReady(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        return try {
            getSystemActions().any { it.id == GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun stop() {
        ticking = false
        waiting.clear()
        main.removeCallbacks(tick)
    }

    companion object {
        @Volatile
        private var active: PopupCollapseService? = null

        /** False when pop-up access is off. Does not open the shade. */
        internal fun request(pkg: String): Boolean {
            val service = active ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.enqueue(pkg)
            } else {
                service.main.post { service.enqueue(pkg) }
            }
            return true
        }

        private const val POLL_MS = 80L
        private const val WINDOW_MS = 1600L
    }
}
