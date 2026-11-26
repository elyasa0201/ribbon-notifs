package app.ticker

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.widget.Toast

/**
 * Channel popup writes are allowed once this app has a companion association.
 * The association is self-managed: there is no Bluetooth device to pick, and
 * Android asks once before the channel setting can change.
 */
internal object PopupGrant {
    private var asked = false

    fun associated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            manager.myAssociations.isNotEmpty()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun markAsked(): Boolean {
        if (asked) return false
        asked = true
        return true
    }

    fun request(activity: Activity, done: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
            done()
            return
        }
        if (associated(activity)) {
            done()
            return
        }
        val manager = activity.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
            done()
            return
        }
        val request = AssociationRequest.Builder()
            .setSelfManaged(true)
            .setDisplayName(activity.getString(R.string.popup_association_name))
            .setForceConfirmation(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                try {
                    activity.startIntentSender(intentSender, null, 0, 0, 0, null)
                } catch (_: RuntimeException) {
                    Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
                    done()
                }
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                done()
            }

            override fun onFailure(error: CharSequence?) {
                done()
            }
        }
        try {
            manager.associate(request, activity.mainExecutor, callback)
        } catch (_: RuntimeException) {
            Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
            done()
        }
    }
}
