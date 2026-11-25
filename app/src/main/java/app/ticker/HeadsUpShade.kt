package app.ticker

import android.os.SystemClock

/**
 * A heads-up that is already on screen stays up when a channel write is
 * rejected. Opening the notification shade marks that popup seen. SystemUI
 * then removes the popup and leaves the shade entry. Shade access closes
 * the shade again once the popup has been taken in.
 */
internal object HeadsUpShade {
    private val lock = Any()
    private var pulsedUntil = 0L

    fun hide(): String? {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now < pulsedUntil) return null
            pulsedUntil = now + PULSE_WINDOW_MS
        }
        if (ShadePulseService.pulse()) return "heads-up shade reason=accessibility"
        synchronized(lock) { pulsedUntil = 0L }
        return "heads-up left reason=shade"
    }

    private const val PULSE_WINDOW_MS = 1100L
}
