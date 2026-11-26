package app.ticker

/**
 * A heads-up that is already on screen stays up when a channel write is
 * rejected. Opening the notification shade marks that popup seen. SystemUI
 * then removes the popup and leaves the shade entry. The notifications
 * action only expands, so the shade is collapsed with the dismiss action
 * once the popup has been taken in.
 */
internal object HeadsUpShade {
    fun hide(): String? {
        return when (ShadePulseService.pulse()) {
            ShadePulse.Started -> "heads-up shade reason=accessibility"
            ShadePulse.Busy -> null
            ShadePulse.Unavailable -> "heads-up left reason=shade"
        }
    }
}
