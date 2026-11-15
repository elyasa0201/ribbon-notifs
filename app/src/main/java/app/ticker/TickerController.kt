package app.ticker

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.service.notification.Condition
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.service.notification.ZenPolicy
import android.util.Log
import android.app.AutomaticZenRule
import android.net.Uri
import java.util.ArrayDeque

/**
 * Arms one app-owned Do Not Disturb rule while a chosen app is in front and
 * feeds ordinary high-importance alerts to the strip. The original notification
 * is never cancelled or snoozed.
 *
 * Failure handling lives here: a missing grant, a null usage result before the
 * first unlock after boot, listener disconnect, a rule deleted in system
 * settings, a user snooze of the mode, and a process start that finds the rule
 * still true.
 */
internal class TickerController(private val app: Context) {
    fun interface Listener {
        fun onChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = TickerPrefs(app)
    private val overlay = StripOverlay(app)
    private val labels = HashMap<String, String>()
    private val listeners = LinkedHashSet<Listener>()
    private val queue = ArrayDeque<Line>()

    private var listenerConnected = false
    private var armed = false
    private var ruleActive = false
    private var suppressRearm = false
    private var updatesDisabled = false
    private var currentForeground: String? = null
    private var showing: Line? = null
    private var ruleError = false
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    val masterEnabled: Boolean get() = prefs.masterEnabled
    val ruleSaved: Boolean get() = prefs.ruleId != null
    val isRuleActive: Boolean get() = ruleActive
    val ruleCreateFailed: Boolean get() = ruleError
    fun isSelected(packageName: String): Boolean = packageName in prefs.selectedPackages

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun onProcessStart() {
        registerReceivers()
        // A process death after STATE_TRUE can leave the mode on. Clear it
        // before the foreground check is allowed to arm again.
        if (Grants.policy(app)) ensureRule()
        setRuleState(active = false, reason = "process start", force = true)
        armed = false
        ruleActive = false
        suppressRearm = false
        refreshInstalledApps()
    }

    fun onSetupVisible() {
        if (Grants.policy(app)) ensureRule()
        if (prefs.masterEnabled && !Grants.core(app)) {
            setMasterEnabled(false)
        } else {
            changed()
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        if (enabled && !Grants.core(app)) {
            prefs.masterEnabled = false
            changed()
            return
        }
        prefs.masterEnabled = enabled
        if (!enabled) {
            armed = false
            suppressRearm = false
            setRuleState(active = false, reason = "master off", force = false)
            clearStrip()
        }
        changed()
    }

    fun setPackageSelected(packageName: String, selected: Boolean) {
        prefs.setPackageSelected(packageName, selected)
    }

    fun stripTextSizeSp(): Int = prefs.textSizeSp

    fun stripScrollSpeedDp(): Float = prefs.scrollSpeedDp

    fun stripBackground(): Int = prefs.stripBackground

    fun setStripTextSizeSp(value: Int) {
        prefs.textSizeSp = value
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripScrollSpeedDp(value: Float) {
        prefs.scrollSpeedDp = value
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripBackground(color: Int) {
        prefs.stripBackground = color
        overlay.applyAppearance(currentAppearance())
    }

    private fun currentAppearance(): StripAppearance {
        return StripAppearance(prefs.textSizeSp, prefs.scrollSpeedDp, prefs.stripBackground)
    }

    fun noteInstalled(apps: List<LaunchableApp>) {
        labels.clear()
        for (item in apps) labels[item.packageName] = item.label
        prefs.retainInstalled(apps.map { it.packageName }.toSet())
    }

    fun turnRuleOffNow() {
        armed = false
        suppressRearm = false
        setRuleState(active = false, reason = "manual off", force = true)
        dropAlertLines()
        changed()
    }

    fun previewStrip(): Boolean {
        if (!Grants.overlay(app)) return false
        enqueue(Line(app.getString(R.string.preview_line), preview = true))
        return true
    }

    fun onListenerConnected() {
        listenerConnected = true
        if (Grants.policy(app)) ensureRule()
        suppressRearm = false
        updatesDisabled = false
        setRuleState(active = false, reason = "listener connected", force = true)
        armed = false
        ruleActive = false
        startPolling()
        changed()
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        stopPolling()
        armed = false
        clearStrip()
        setRuleState(active = false, reason = "listener disconnected", force = true)
        changed()
    }

    fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!armed) return
        if (sbn.packageName == app.packageName) return
        if (sbn.packageName == currentForeground) return
        val notification = sbn.notification ?: return
        val flags = notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (isMediaStyle(notification)) return
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return
        if (ranking.importance < NotificationManager.IMPORTANCE_HIGH) return
        if (BuildConfig.DEBUG) {
            val peek = (ranking.suppressedVisualEffects and
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0
            Log.i(
                TAG,
                "alert pkg=${sbn.packageName} importance=${ranking.importance} peekSuppressed=$peek"
            )
        }
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = messageBody(extras)
        enqueue(Line(TickerText.line(appLabel(sbn.packageName), title, body), preview = false))
    }

    private fun registerReceivers() {
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val zen = IntentFilter(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED)
            app.registerReceiver(zenReceiver, zen, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(screenReceiver, screen)
            app.registerReceiver(zenReceiver, zen)
        }
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                main.post { overlay.relayout() }
            }

            override fun onLowMemory() = Unit
        })
        val displays = app.getSystemService(DisplayManager::class.java)
        displays?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                main.post { overlay.relayout() }
            }

            override fun onDisplayRemoved(displayId: Int) {
                main.post { overlay.relayout() }
            }

            override fun onDisplayChanged(displayId: Int) {
                main.post { overlay.relayout() }
            }
        }, main)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            syncStripVisibility()
        }
    }

    private val zenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) return
            val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
            if (id == null || id != prefs.ruleId) return
            val status = intent.getIntExtra(
                NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS,
                NotificationManager.AUTOMATIC_RULE_STATUS_UNKNOWN
            )
            onZenStatus(status)
        }
    }

    private fun onZenStatus(status: Int) {
        when (status) {
            NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> {
                prefs.ruleId = null
                ruleActive = false
                suppressRearm = false
                updatesDisabled = false
                ruleError = false
                if (Grants.policy(app)) ensureRule()
                debug("rule removed")
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> {
                // The user snoozed the mode. Further STATE_TRUE calls are ignored
                // until this app sends STATE_FALSE, which happens on leaving.
                ruleActive = false
                suppressRearm = true
                debug("rule snoozed")
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> {
                ruleActive = false
                updatesDisabled = true
                suppressRearm = true
                debug("rule disabled")
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> {
                updatesDisabled = false
                debug("rule enabled")
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> ruleActive = true
            else -> debug("rule status $status")
        }
        changed()
    }

    private fun startPolling() {
        if (pollThread == null) {
            val thread = HandlerThread("ticker-poll")
            thread.start()
            pollThread = thread
            pollHandler = Handler(thread.looper)
        }
        pollHandler?.removeCallbacks(pollTask)
        pollHandler?.post(pollTask)
    }

    private fun stopPolling() {
        pollHandler?.removeCallbacks(pollTask)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!listenerConnected) return
            val read = readForeground()
            main.post {
                if (!listenerConnected) return@post
                if (prefs.masterEnabled && !Grants.core(app)) {
                    setMasterEnabled(false)
                }
                if (read is ForegroundRead.Known) applyForeground(read.packageName)
                syncStripVisibility()
            }
            pollHandler?.postDelayed(this, POLL_MS)
        }
    }

    private fun readForeground(): ForegroundRead {
        if (!Grants.usage(app)) return ForegroundRead.Known(null)
        val users = app.getSystemService(UserManager::class.java)
        if (users != null && !users.isUserUnlocked) return ForegroundRead.Unavailable
        val stats = app.getSystemService(UsageStatsManager::class.java)
            ?: return ForegroundRead.Unavailable
        val now = System.currentTimeMillis()
        val sinceBoot = now - SystemClock.elapsedRealtime()
        val earliest = now - USAGE_LOOKBACK_MS
        val start = maxOf(sinceBoot, earliest)
        val events = try {
            stats.queryEvents(start, now)
        } catch (_: SecurityException) {
            return ForegroundRead.Known(null)
        }
        // Null is the direct-boot case: the user has not unlocked since boot.
        if (events == null) {
            debug("usage events null")
            return ForegroundRead.Unavailable
        }
        val event = UsageEvents.Event()
        var bestPackage: String? = null
        var bestTime = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val packageName = event.packageName ?: continue
            if (packageName.isEmpty()) continue
            if (event.timeStamp >= bestTime) {
                bestTime = event.timeStamp
                bestPackage = packageName
            }
        }
        return ForegroundRead.Known(bestPackage)
    }

    private fun applyForeground(packageName: String?) {
        val selected = prefs.selectedPackages
        val shouldArm = prefs.masterEnabled &&
            Grants.core(app) &&
            Grants.usage(app) &&
            packageName != null &&
            packageName != app.packageName &&
            packageName in selected
        if (shouldArm) {
            val entered = !armed
            armed = true
            currentForeground = packageName
            if (!ruleActive && !suppressRearm && !updatesDisabled) {
                if (prefs.ruleId == null && Grants.policy(app)) ensureRule()
                setRuleState(active = true, reason = "foreground", force = false)
            }
            if (entered) {
                debug("armed")
                changed()
            }
        } else if (armed || ruleActive || suppressRearm) {
            armed = false
            currentForeground = packageName
            suppressRearm = false
            setRuleState(active = false, reason = "left foreground", force = false)
            dropAlertLines()
            debug("disarmed")
            changed()
        } else {
            currentForeground = packageName
        }
    }

    private fun ensureRule() {
        if (!Grants.policy(app)) return
        val manager = notificationManager() ?: return
        val saved = prefs.ruleId
        if (saved != null) {
            val existing = try {
                manager.getAutomaticZenRule(saved)
            } catch (_: RuntimeException) {
                ruleError = true
                return
            }
            if (existing != null) {
                val updated = try {
                    manager.updateAutomaticZenRule(saved, buildRule())
                } catch (_: RuntimeException) {
                    false
                }
                if (!updated) debug("rule update declined")
                ruleError = false
                return
            }
            prefs.ruleId = null
        }
        val created = try {
            manager.addAutomaticZenRule(buildRule())
        } catch (_: RuntimeException) {
            null
        }
        if (created.isNullOrEmpty()) {
            ruleError = true
            debug("rule create failed")
        } else {
            prefs.ruleId = created
            ruleError = false
            debug("rule created")
        }
    }

    private fun buildRule(): AutomaticZenRule {
        return AutomaticZenRule(
            app.getString(R.string.rule_name),
            null,
            ComponentName(app, RuleExplanationActivity::class.java),
            conditionId(),
            zenPolicy(),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
    }

    private fun zenPolicy(): ZenPolicy {
        val builder = ZenPolicy.Builder()
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowReminders(false)
            .allowEvents(false)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
            .allowAlarms(true)
            .allowMedia(true)
            .allowSystem(true)
            .showPeeking(false)
            .showInNotificationList(true)
            .showStatusBarIcons(true)
            .showBadges(true)
            .showFullScreenIntent(false)
            .showLights(false)
            .showInAmbientDisplay(false)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            builder.allowPriorityChannels(false)
        }
        return builder.build()
    }

    private fun setRuleState(active: Boolean, reason: String, force: Boolean) {
        if (updatesDisabled && !force) {
            if (!active) ruleActive = false
            return
        }
        val id = prefs.ruleId ?: run {
            ruleActive = false
            return
        }
        if (!Grants.policy(app)) {
            ruleActive = false
            return
        }
        val manager = notificationManager() ?: return
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = if (active) "A selected app is open" else "Off"
        // SOURCE_USER_ACTION would override a snooze. The default source lets
        // the user's snooze win until we later send STATE_FALSE.
        val condition = Condition(conditionId(), summary, state)
        try {
            manager.setAutomaticZenRuleState(id, condition)
        } catch (_: SecurityException) {
            ruleActive = false
            debug("rule state denied ($reason)")
            return
        } catch (_: IllegalArgumentException) {
            prefs.ruleId = null
            ruleActive = false
            ruleError = true
            debug("rule id rejected ($reason)")
            return
        }
        if (active && Build.VERSION.SDK_INT >= 35) {
            val actual = try {
                manager.getAutomaticZenRuleState(id)
            } catch (_: SecurityException) {
                Condition.STATE_UNKNOWN
            }
            if (actual != Condition.STATE_TRUE) {
                ruleActive = false
                suppressRearm = true
                debug("rule on ignored ($reason)")
                return
            }
        }
        ruleActive = active
        debug("rule ${if (active) "on" else "off"} ($reason)")
    }

    private fun conditionId(): Uri = Uri.parse("condition://${app.packageName}/armed")

    private fun notificationManager(): NotificationManager? {
        return app.getSystemService(NotificationManager::class.java)
    }

    private fun enqueue(line: Line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { enqueue(line) }
            return
        }
        queue.addLast(line)
        if (showing == null) showNext()
    }

    private fun showNext() {
        if (showing != null) return
        while (queue.isNotEmpty()) {
            val next = queue.first()
            if (!next.preview && !armed) {
                queue.removeFirst()
                continue
            }
            if (!canShowOnStrip()) return
            val shown = overlay.show(next.text, currentAppearance()) { main.post { onLineFinished() } }
            if (!shown) return
            queue.removeFirst()
            showing = next
            return
        }
        overlay.remove()
    }

    private fun onLineFinished() {
        showing = null
        showNext()
    }

    private fun syncStripVisibility() {
        if (!canShowOnStrip()) {
            if (showing != null || overlay.isShowing()) hideForLock()
            return
        }
        if (showing == null && queue.isNotEmpty()) showNext()
    }

    private fun hideForLock() {
        showing?.let { queue.addFirst(it) }
        showing = null
        overlay.remove()
    }

    private fun dropAlertLines() {
        val kept = queue.filter { it.preview }
        queue.clear()
        queue.addAll(kept)
        if (showing?.preview == true) return
        showing = null
        overlay.remove()
        if (queue.isNotEmpty()) showNext()
    }

    private fun clearStrip() {
        queue.clear()
        showing = null
        overlay.remove()
    }

    private fun canShowOnStrip(): Boolean {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val screenOn = power?.isInteractive != false
        val locked = keyguard?.isKeyguardLocked == true
        return screenOn && !locked
    }

    private fun refreshInstalledApps() {
        Thread {
            when (val load = loadLaunchableApps(app)) {
                is AppLoad.Ok -> main.post {
                    labels.clear()
                    for (item in load.apps) labels[item.packageName] = item.label
                    prefs.retainInstalled(load.apps.map { it.packageName }.toSet())
                }
                is AppLoad.Failed -> debug("app list ${load.message}")
            }
        }.start()
    }

    private fun appLabel(packageName: String): String {
        labels[packageName]?.let { return it }
        return try {
            val info = app.packageManager.getApplicationInfo(packageName, 0)
            val label = app.packageManager.getApplicationLabel(info).toString()
            labels[packageName] = label
            label
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun messageBody(extras: android.os.Bundle?): String? {
        if (extras == null) return null
        val direct = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct
        return lastMessageText(extras)
    }

    private fun lastMessageText(extras: android.os.Bundle): String? {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return null
        for (index in raw.indices.reversed()) {
            val bundle = raw[index] as? android.os.Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun isMediaStyle(notification: Notification): Boolean {
        val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE) ?: return false
        return template.endsWith("MediaStyle")
    }

    private fun changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { changed() }
            return
        }
        listeners.forEach { it.onChanged() }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private data class Line(val text: String, val preview: Boolean)

    private sealed class ForegroundRead {
        data class Known(val packageName: String?) : ForegroundRead()
        data object Unavailable : ForegroundRead()
    }

    private companion object {
        const val TAG = "Ticker"
        const val POLL_MS = 1000L
        const val USAGE_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
