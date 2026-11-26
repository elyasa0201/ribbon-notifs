package app.ticker

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Opens the notification shade, then collapses it. The notifications action
 * only expands. A second one does not close the shade, and on One UI it
 * lands while the shade is still opening and leaves the shade on screen.
 * Collapse is a separate action, and the system only offers it once the
 * shade is up. Wait for that, let the heads-up be taken in, then collapse
 * until the shade is gone.
 */
class ShadePulseService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var again = false
    private var closing = false
    private var dismissed = false
    private var startedAt = 0L
    private var shadeSeenAt = 0L

    override fun onServiceConnected() {
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        running = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun startPulse(): ShadePulse {
        if (active !== this) return ShadePulse.Unavailable
        if (running) {
            // The shade is already open, so this heads-up is taken in with
            // the first one. Once the collapse has started, a later heads-up
            // needs its own open after this one has closed.
            if (closing) again = true
            return ShadePulse.Busy
        }
        if (!performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) return ShadePulse.Unavailable
        running = true
        again = false
        closing = false
        dismissed = false
        shadeSeenAt = 0L
        startedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(tick)
        main.postDelayed(tick, POLL_MS)
        return ShadePulse.Started
    }

    private val tick = Runnable { onTick() }

    private fun onTick() {
        if (!running || active !== this) return
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt >= GIVE_UP_MS) {
            finishPulse()
            return
        }
        val open = shadeIsOpen()
        if (open && shadeSeenAt == 0L) shadeSeenAt = now
        val closeAt = when {
            shadeSeenAt != 0L -> maxOf(shadeSeenAt + AFTER_OPEN_MS, startedAt + MIN_OPEN_MS)
            now - startedAt >= MIN_OPEN_MS -> now
            else -> 0L
        }
        if (closeAt == 0L || now < closeAt) {
            main.postDelayed(tick, POLL_MS)
            return
        }
        closing = true
        if (open || !dismissed) {
            if (performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) dismissed = true
            main.postDelayed(tick, POLL_MS)
            return
        }
        finishPulse()
    }

    private fun finishPulse() {
        running = false
        closing = false
        main.removeCallbacks(tick)
        if (again) {
            again = false
            startPulse()
        }
    }

    private fun shadeIsOpen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            getSystemActions().any { it.id == GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE }
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        @Volatile
        private var active: ShadePulseService? = null

        internal fun pulse(): ShadePulse {
            val service = active ?: return ShadePulse.Unavailable
            if (Looper.myLooper() != Looper.getMainLooper()) {
                service.main.post { service.startPulse() }
                return ShadePulse.Started
            }
            return service.startPulse()
        }

        private const val POLL_MS = 100L
        private const val MIN_OPEN_MS = 600L
        private const val AFTER_OPEN_MS = 400L
        private const val GIVE_UP_MS = 2500L
    }
}

internal enum class ShadePulse {
    Started,
    Busy,
    Unavailable,
}
