package app.ticker

import android.app.Activity
import android.os.Bundle

/**
 * Shows the one-time system confirmation that lets Ribbon Notifs change a
 * sending app's channel popup setting. The screen itself draws nothing.
 */
class PopupAssociationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PopupGrant.request(this) {
            (application as TickerApp).controller.onPopupGrantChanged()
            if (!isFinishing) finish()
        }
    }
}
