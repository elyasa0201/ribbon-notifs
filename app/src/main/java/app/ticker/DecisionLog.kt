package app.ticker

import android.app.Activity
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Short on-device record of why an alert was shown or skipped.
 * Lines name packages and rule state only. They never include message text.
 */
internal object DecisionLog {
    private const val MAX = 80
    private const val FILE_NAME = "notification-debug.log"
    private val lines = ArrayDeque<String>()
    private var loaded = false

    fun note(context: android.content.Context, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp " + message.replace('\n', ' ').replace('\r', ' ')
        synchronized(lines) {
            load(context)
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            write(context)
        }
        Log.i(TAG, line)
    }

    fun recent(context: android.content.Context, count: Int): String? {
        val snapshot = synchronized(lines) {
            load(context)
            if (lines.isEmpty()) null else lines.toList().takeLast(count).joinToString("\n")
        }
        return snapshot
    }

    fun share(activity: Activity) {
        val body = synchronized(lines) {
            load(activity)
            if (lines.isEmpty()) {
                activity.getString(R.string.debug_empty)
            } else {
                lines.joinToString("\n")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.debug_share_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.debug_share)))
    }

    private fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return
        try {
            file.readLines().forEach { row ->
                if (row.isNotBlank()) lines.addLast(row)
            }
            while (lines.size > MAX) lines.removeFirst()
        } catch (_: Exception) {
            lines.clear()
        }
    }

    private fun write(context: android.content.Context) {
        try {
            File(context.filesDir, FILE_NAME).writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
            // The in-memory copy is still what Share sends.
        }
    }

    private const val TAG = "Ticker"
}
