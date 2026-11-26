package app.ticker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Parcel
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * An older build lowered some channel importance and stored the original.
 * This puts that importance back so alerts behave as the sending app set
 * them. The sound is left untouched. New alerts are not rewritten.
 */
internal class HeadsUpQuiet(private val service: NotificationListenerService) {
    private val prefs by lazy { service.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val denied = HashSet<String>()
    private val cached = HashMap<String, NotificationChannel>()

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
     * This phone rejects the write for a normal listener, and the channel
     * stays as it was. Call this only while the listener is bound; an unbound
     * call returns without writing and without throwing.
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
    )

    private companion object {
        const val PREFS = "ticker_heads_up"
        const val KEY = "lowered"
    }
}
