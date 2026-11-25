package app.ticker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Parcel
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * SystemUI keeps a heads-up only while the notification ranks at high
 * importance. Default importance still plays the channel sound and keeps
 * the shade row, and a ranking update at that level removes the popup.
 *
 * [NotificationListenerService.updateNotificationChannel] is the listener
 * write that drops the channel. The platform allows it for a companion
 * listener or the notification assistant and rejects it otherwise. A
 * rejection leaves the popup where it is. The importance we found is put
 * back when the selected app is no longer in front.
 */
internal class HeadsUpQuiet(private val service: NotificationListenerService) {
    private val prefs by lazy { service.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val denied = HashSet<String>()
    private val cached = HashMap<String, NotificationChannel>()

    fun quiet(sbn: StatusBarNotification, ranked: NotificationChannel?): String? {
        val channelId = sbn.notification?.channelId
        val user = sbn.user
        if (channelId.isNullOrEmpty() || user == null) {
            return left(sbn.packageName, channelId, "no-channel")
        }
        synchronized(this) {
            val key = key(sbn.packageName, user, channelId)
            if (stored().any { it.same(sbn.packageName, user, channelId) }) return null
            val channel = if (ranked?.id == channelId) {
                ranked
            } else {
                load(sbn.packageName, user, channelId)
            }
                ?: return if (!denied.add(key)) {
                    null
                } else {
                    left(sbn.packageName, channelId, "no-channel")
                }
            val original = channel.importance
            if (original < NotificationManager.IMPORTANCE_HIGH) return null
            if (denied.contains(key)) return null
            channel.importance = NotificationManager.IMPORTANCE_DEFAULT
            if (!writeChannel(sbn.packageName, user, channel)) {
                channel.importance = original
                denied.add(key)
                return left(sbn.packageName, channel.id, "listener")
            }
            cached[key] = channel
            val next = stored().toMutableList()
            next.add(Lowered(sbn.packageName, user, channel.id, original))
            write(next)
            return "heads-up quiet pkg=${sbn.packageName} channel=${channel.id} " +
                "importance=$original->${NotificationManager.IMPORTANCE_DEFAULT}"
        }
    }

    fun restoreAll(): List<String> {
        synchronized(this) {
            val pending = stored()
            if (pending.isEmpty()) return emptyList()
            val notes = ArrayList<String>()
            val keep = ArrayList<Lowered>()
            for (item in pending) {
                val key = key(item.pkg, item.user, item.channelId)
                val channel = cached[key] ?: load(item.pkg, item.user, item.channelId)
                if (channel == null) {
                    keep.add(item)
                    if (denied.add("restore:$key")) {
                        notes.add(
                            "heads-up restore pkg=${item.pkg} channel=${item.channelId} reason=listener"
                        )
                    }
                    continue
                }
                if (channel.importance != NotificationManager.IMPORTANCE_DEFAULT) {
                    cached.remove(key)
                    continue
                }
                channel.importance = item.importance
                if (!writeChannel(item.pkg, item.user, channel)) {
                    channel.importance = NotificationManager.IMPORTANCE_DEFAULT
                    keep.add(item)
                    notes.add(
                        "heads-up restore pkg=${item.pkg} channel=${item.channelId} reason=listener"
                    )
                    continue
                }
                cached.remove(key)
                notes.add(
                    "heads-up restore pkg=${item.pkg} channel=${item.channelId} " +
                        "importance=${NotificationManager.IMPORTANCE_DEFAULT}->${item.importance}"
                )
            }
            write(keep)
            return notes
        }
    }

    /**
     * A companion listener or the notification assistant is allowed to write
     * the channel. Any other listener is rejected and the channel stays as it
     * was. Call this only while the listener is bound; an unbound call returns
     * without writing and without throwing.
     */
    private fun writeChannel(pkg: String, user: UserHandle, channel: NotificationChannel): Boolean {
        return try {
            service.updateNotificationChannel(pkg, user, channel)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun load(pkg: String, user: UserHandle, channelId: String): NotificationChannel? {
        val listed = try {
            service.getNotificationChannels(pkg, user)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return listed.firstOrNull { it.id == channelId }
    }

    private fun left(pkg: String, channelId: String?, reason: String): String {
        val channel = if (channelId.isNullOrEmpty()) "" else " channel=$channelId"
        return "heads-up left pkg=$pkg$channel reason=$reason"
    }

    private fun stored(): List<Lowered> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: RuntimeException) {
            return emptyList()
        }
        val items = ArrayList<Lowered>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val pkg = obj.optString("pkg")
            val channel = obj.optString("channel")
            val user = userFrom(obj.optString("user")) ?: continue
            if (pkg.isEmpty() || channel.isEmpty()) continue
            items.add(
                Lowered(
                    pkg,
                    user,
                    channel,
                    obj.optInt("importance", NotificationManager.IMPORTANCE_HIGH)
                )
            )
        }
        return items
    }

    private fun write(items: List<Lowered>) {
        if (items.isEmpty()) {
            prefs.edit().remove(KEY).apply()
            return
        }
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("pkg", item.pkg)
            obj.put("user", userTo(item.user))
            obj.put("channel", item.channelId)
            obj.put("importance", item.importance)
            array.put(obj)
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun userTo(user: UserHandle): String {
        val parcel = Parcel.obtain()
        return try {
            user.writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun userFrom(encoded: String): UserHandle? {
        if (encoded.isEmpty()) return null
        val bytes = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            UserHandle.CREATOR.createFromParcel(parcel)
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun key(pkg: String, user: UserHandle, channelId: String): String {
        return "$pkg\u0000${user.hashCode()}\u0000$channelId"
    }

    private data class Lowered(
        val pkg: String,
        val user: UserHandle,
        val channelId: String,
        val importance: Int
    ) {
        fun same(pkg: String, user: UserHandle, channelId: String): Boolean {
            return this.pkg == pkg && this.user == user && this.channelId == channelId
        }
    }

    private companion object {
        const val PREFS = "ticker_heads_up"
        const val KEY = "lowered"
    }
}
