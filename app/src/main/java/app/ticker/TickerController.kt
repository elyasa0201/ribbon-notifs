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
import android.util.Log
import android.net.Uri
import java.util.ArrayDeque

/**
 * While a chosen app is in front, ordinary alerts go on the strip and are then
 * cancelled so the heads-up cannot stay up. Cancelling removes the shade entry
 * too; that is the public dismiss call. Calls, alarms, ongoing events, and
 * media are left in place. Do Not Disturb is never turned on.
 *
 * Failure handling lives here: a missing grant, a null usage result before the
 * first unlock after boot, listener disconnect, and a process start that finds
 * an older zen rule still true.
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
    private var rulePending = false
    private var suppressRearm = false
    private var ownOffEchoes = 0
    private var updatesDisabled = false
    private var currentForeground: String? = null
    private var showing: Line? = null
    private var ruleError = false
    private var usageWarned = false
    private var lastCreateFailure: String? = null
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private val shownUpdateKeys = ArrayDeque<String>()
    private val recentPosts = ArrayDeque<RibbonPost>()
    private val baselineText = HashMap<String, NoticeText>()
    private val acceptedSinceArm = HashSet<String>()
    private val postedLock = Any()
    private var armedAtMillis = Long.MAX_VALUE

    /** Active shade, read from the connected listener. Used once each time we arm. */
    internal var activeNotifications: (() -> List<StatusBarNotification>)? = null

    /** Removes one posted notification. Returns false when the listener call fails. */
    internal var dismissNotification: ((String) -> Boolean)? = null

    internal var ruleDesired: Boolean = false
        private set

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
        closeArrivalGate()
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
            closeArrivalGate()
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

    fun stripTextColor(): Int = prefs.textColor

    fun stripBackgroundRgb(): Int = prefs.backgroundRgb

    fun stripBackgroundAlpha(): Int = prefs.backgroundAlpha

    fun backgroundNone(): Boolean = prefs.backgroundNone

    fun setStripTextSizeSp(value: Int) {
        prefs.textSizeSp = value
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripScrollSpeedDp(value: Float) {
        prefs.scrollSpeedDp = value
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripTextColor(color: Int) {
        prefs.textColor = color
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripBackgroundRgb(color: Int) {
        prefs.backgroundRgb = color
        prefs.backgroundNone = false
        overlay.applyAppearance(currentAppearance())
    }

    fun setStripBackgroundAlpha(alpha: Int) {
        prefs.backgroundAlpha = alpha
        prefs.backgroundNone = false
        overlay.applyAppearance(currentAppearance())
    }

    fun setBackgroundNone() {
        prefs.backgroundNone = true
        overlay.applyAppearance(currentAppearance())
    }

    private fun currentAppearance(): StripAppearance {
        return StripAppearance(
            prefs.textSizeSp,
            prefs.scrollSpeedDp,
            prefs.composedBackground(),
            prefs.textColor
        )
    }

    fun noteInstalled(apps: List<LaunchableApp>) {
        labels.clear()
        for (item in apps) labels[item.packageName] = item.label
        prefs.retainInstalled(apps.map { it.packageName }.toSet())
    }

    fun turnRuleOffNow() {
        armed = false
        closeArrivalGate()
        suppressRearm = false
        setRuleState(active = false, reason = "manual off", force = true)
        dropAlertLines()
        changed()
    }

    fun previewStrip(): Boolean {
        if (!Grants.overlay(app)) return false
        enqueue(Line(app.getString(R.string.preview_line), preview = true, packageName = null))
        return true
    }

    fun onListenerConnected() {
        listenerConnected = true
        if (Grants.policy(app)) ensureRule()
        suppressRearm = false
        updatesDisabled = false
        setRuleState(active = false, reason = "listener connected", force = true)
        armed = false
        closeArrivalGate()
        ruleActive = false
        startPolling()
        changed()
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        stopPolling()
        armed = false
        closeArrivalGate()
        clearStrip()
        setRuleState(active = false, reason = "listener disconnected", force = true)
        note("listener disconnected ${contextLine()}")
    }

    fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val pkg = sbn.packageName
        fun skip(reason: String) {
            note("skip pkg=$pkg ${contextLine()} reason=$reason")
        }

        val notification = sbn.notification
        if (notification == null) {
            skip("empty")
            return
        }
        if (!armed) {
            skip("not-armed")
            return
        }
        if (pkg == app.packageName) {
            skip("own-package")
            return
        }
        if (pkg == currentForeground) {
            skip("foreground-app")
            return
        }
        val flags = notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            skip("ongoing")
            return
        }
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            skip("foreground-service")
            return
        }
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            skip("group-summary")
            return
        }
        if (isMediaStyle(notification)) {
            skip("media")
            return
        }
        val text = noticeText(notification)
        val arrival = synchronized(postedLock) { arrivalOf(sbn.key, text, sbn.postTime) }
        if (arrival == Arrival.Replay) {
            skip("already-posted")
            return
        }
        if (arrival == Arrival.BeforeArm) {
            skip("posted-before-arm")
            return
        }
        // A conversation update is often ranked below high. So is a new message
        // that only changes the text of a notification already in the shade.
        val update = synchronized(postedLock) {
            shownUpdateKeys.contains(sbn.key) || arrival == Arrival.TextChanged
        }
        val ranking = Ranking()
        val ranked = try {
            rankingMap?.getRanking(sbn.key, ranking) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!ranked && !update) {
            skip("no-ranking")
            return
        }
        val channelImportance = if (ranked) ranking.channel?.importance else null
        val peek = ranked && (ranking.suppressedVisualEffects and
            NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0
        val alerting = ranked && ordinaryAlert(ranking.importance, channelImportance, peek)
        if (!alerting && !update) {
            val channel = channelImportance ?: -1
            val rank = if (ranked) ranking.importance else -1
            skip("below-importance rank=$rank channel=$channel peek=$peek")
            return
        }
        val line = TickerText.line(appLabel(pkg), text.title, text.body)
        val posted = RibbonPost(
            key = sbn.key,
            pkg = pkg,
            line = line,
            postSecond = sbn.postTime / 1000L
        )
        if (synchronized(postedLock) { isDuplicate(posted) }) {
            skip("duplicate")
            dismissAlert(pkg, notification, sbn.key)
            return
        }
        val channel = channelImportance ?: -1
        val rank = if (ranked) ranking.importance else -1
        val reason = if (update && !alerting) "update" else "shown"
        note(
            "accept pkg=$pkg ${contextLine()} reason=$reason " +
                "rank=$rank channel=$channel peek=$peek"
        )
        var took = false
        synchronized(postedLock) {
            if (isDuplicate(posted)) return@synchronized
            val again = arrivalOf(sbn.key, text, sbn.postTime)
            if (again == Arrival.Replay || again == Arrival.BeforeArm) return@synchronized
            rememberPost(posted)
            rememberShownKey(sbn.key)
            acceptedSinceArm.add(sbn.key)
            baselineText.remove(sbn.key)
            took = true
        }
        if (!took) return
        showOnRibbon(Line(line, preview = false, packageName = pkg)) {
            dismissAlert(pkg, notification, sbn.key)
        }
    }

    private fun showOnRibbon(line: Line, afterQueued: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showOnRibbon(line, afterQueued) }
            return
        }
        queue.addLast(line)
        if (showing == null) showNext()
        afterQueued()
    }

    private fun dismissAlert(pkg: String, notification: Notification, key: String) {
        if (keepsSystemAlert(notification)) return
        val dismiss = dismissNotification
        if (dismiss == null) {
            note("dismiss failed pkg=$pkg ${contextLine()} reason=no-listener")
            return
        }
        val removed = try {
            dismiss(key)
        } catch (_: RuntimeException) {
            false
        }
        if (removed) {
            note("dismiss pkg=$pkg ${contextLine()} reason=cancel")
        } else {
            note("dismiss failed pkg=$pkg ${contextLine()} reason=cancel")
        }
    }

    private fun keepsSystemAlert(notification: Notification): Boolean {
        val category = notification.category
        if (category == Notification.CATEGORY_CALL) return true
        if (category == Notification.CATEGORY_ALARM) return true
        if (notification.fullScreenIntent != null) return true
        return false
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
        var deactivatedWhy = ""
        when (status) {
            NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> {
                prefs.ruleId = null
                ruleActive = false
                rulePending = false
                suppressRearm = false
                updatesDisabled = false
                ruleError = false
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> {
                ruleActive = false
                rulePending = false
                // STATE_FALSE also reports the rule inactive. That echo is not a
                // snooze. A snooze is the user turning it off while a selected
                // app is still in front.
                if (ownOffEchoes > 0) {
                    ownOffEchoes--
                    deactivatedWhy = " ignored reason=own-off"
                } else if (armed && ruleDesired) {
                    suppressRearm = true
                    deactivatedWhy = " reason=user-snooze"
                } else {
                    deactivatedWhy = " ignored reason=not-in-app"
                }
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> {
                ruleActive = false
                rulePending = false
                updatesDisabled = true
                suppressRearm = true
            }
            NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> updatesDisabled = false
            NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> {
                ruleActive = true
                rulePending = false
                // An older install can still be switched on by the system. Turn it
                // back off. This app does not activate the rule itself.
                setRuleState(active = false, reason = "unexpected-on", force = true)
            }
        }
        note("zen ${zenStatusName(status)}$deactivatedWhy ${contextLine()}")
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
            if (!usageWarned) {
                usageWarned = true
                note("usage unavailable ${contextLine()} reason=locked")
            }
            return ForegroundRead.Unavailable
        }
        usageWarned = false
        val open = LinkedHashMap<String, OpenActivity>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            if (packageName.isEmpty()) continue
            val className = event.className.orEmpty()
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val key = if (className.isEmpty()) packageName else "$packageName/$className"
                    open[key] = OpenActivity(packageName, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (className.isEmpty()) {
                        val iterator = open.entries.iterator()
                        while (iterator.hasNext()) {
                            if (iterator.next().value.packageName == packageName) iterator.remove()
                        }
                    } else {
                        open.remove("$packageName/$className")
                    }
                }
            }
        }
        val best = open.values.maxByOrNull { it.time }?.packageName
        return ForegroundRead.Known(best)
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
            if (entered) {
                // postTime is wall-clock. Anything already in the shade is older
                // than this and is not a new alert.
                armedAtMillis = System.currentTimeMillis()
                synchronized(postedLock) { acceptedSinceArm.clear() }
            }
            armed = true
            currentForeground = packageName
            val baseline = if (entered) captureBaseline() else null
            if (ruleActive || ruleDesired) {
                setRuleState(active = false, reason = "foreground", force = true)
            }
            if (entered) note("armed ${contextLine()} baseline=$baseline")
        } else if (armed || ruleActive || suppressRearm) {
            armed = false
            closeArrivalGate()
            currentForeground = packageName
            suppressRearm = false
            setRuleState(active = false, reason = "left foreground", force = false)
            dropAlertLines()
            note("disarmed ${contextLine()} reason=left")
        } else {
            currentForeground = packageName
        }
    }

    /**
     * Finds a rule an older install already saved, so Turn DND off can reach it.
     * Does not create or update a rule, and does not turn one on.
     */
    private fun ensureRule() {
        if (!Grants.policy(app)) return
        val manager = notificationManager() ?: return
        val saved = prefs.ruleId
        if (saved != null) {
            val existing = try {
                manager.getAutomaticZenRule(saved)
            } catch (error: RuntimeException) {
                ruleError = true
                noteCreate("rule read failed ${contextLine()} ${failureText(error)}")
                return
            }
            if (existing != null) {
                ruleError = false
                return
            }
            prefs.ruleId = null
        }
        val adopted = findOwnedRule(manager) ?: return
        prefs.ruleId = adopted
        ruleError = false
        noteCreate("rule adopted ${contextLine()}")
    }

    private fun findOwnedRule(manager: NotificationManager): String? {
        val rules = try {
            manager.automaticZenRules
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        val condition = conditionId()
        val activity = configurationActivity()
        val provider = conditionProvider()
        return rules.entries.firstOrNull { (_, rule) ->
            rule.conditionId == condition ||
                rule.configurationActivity == activity ||
                rule.owner == provider
        }?.key
    }

    private fun configurationActivity(): ComponentName {
        return ComponentName(app, RuleExplanationActivity::class.java)
    }

    private fun conditionProvider(): ComponentName {
        return ComponentName(app, TickerConditionService::class.java)
    }

    private fun failureText(error: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 3) {
            val message = current.message?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            val name = current.javaClass.simpleName.ifEmpty { current.javaClass.name }
            parts.add(if (message.isNullOrEmpty()) name else "$name: $message")
            current = current.cause
            guard += 1
        }
        return parts.joinToString(" caused by ").take(400)
    }

    private fun noteCreate(message: String) {
        if (message == lastCreateFailure) return
        lastCreateFailure = message
        note(message)
    }

    private fun setRuleState(active: Boolean, reason: String, force: Boolean) {
        if (active) {
            ruleDesired = false
            return
        }
        if (updatesDisabled && !force) {
            if (!active) {
                ruleDesired = false
                ruleActive = false
            }
            return
        }
        ruleDesired = active
        val id = prefs.ruleId ?: run {
            ruleActive = false
            TickerConditionService.publish()
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
        if (!active) ownOffEchoes++
        val condition = Condition(conditionId(), summary, state)
        try {
            manager.setAutomaticZenRuleState(id, condition)
        } catch (error: SecurityException) {
            if (!active) ownOffEchoes = (ownOffEchoes - 1).coerceAtLeast(0)
            ruleActive = false
            note("rule denied ${contextLine()} reason=$reason ${failureText(error)}")
            return
        } catch (error: IllegalArgumentException) {
            if (!active) ownOffEchoes = (ownOffEchoes - 1).coerceAtLeast(0)
            prefs.ruleId = null
            ruleActive = false
            ruleError = true
            note("rule id rejected ${contextLine()} reason=$reason ${failureText(error)}")
            return
        }
        TickerConditionService.publish()
        if (active && Build.VERSION.SDK_INT >= 35) {
            val actual = try {
                manager.getAutomaticZenRuleState(id)
            } catch (_: SecurityException) {
                Condition.STATE_UNKNOWN
            }
            // Samsung applies the condition after this call returns. A state
            // that is not TRUE yet is not a snooze. Retry on the next poll.
            if (actual != Condition.STATE_TRUE) {
                ruleActive = false
                if (!rulePending) {
                    rulePending = true
                    note("rule pending actual=$actual ${contextLine()} reason=$reason")
                }
                return
            }
        }
        rulePending = false
        ruleActive = active
        note("rule ${if (active) "on" else "off"} ${contextLine()} reason=$reason")
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
                note("skip pkg=${next.packageName ?: "-"} ${contextLine()} reason=not-armed")
                continue
            }
            if (!canShowOnStrip()) {
                if (!next.blockLogged && !next.preview) {
                    next.blockLogged = true
                    note("skip pkg=${next.packageName ?: "-"} ${contextLine()} reason=screen-or-lock")
                }
                return
            }
            val shown = overlay.show(next.text, currentAppearance()) { main.post { onLineFinished() } }
            if (!shown) {
                if (!next.blockLogged && !next.preview) {
                    next.blockLogged = true
                    note("skip pkg=${next.packageName ?: "-"} ${contextLine()} reason=overlay")
                }
                return
            }
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

    /**
     * Notifications already posted when the selected app came to the front.
     * The listener replays this set when it connects and again when zen arms.
     * Same text is not a new alert. A changed text is.
     */
    private fun captureBaseline(): String {
        val source = activeNotifications
        if (source == null) {
            synchronized(postedLock) { baselineText.clear() }
            return "unavailable"
        }
        val active = try {
            source()
        } catch (_: RuntimeException) {
            synchronized(postedLock) { baselineText.clear() }
            return "unavailable"
        }
        val next = HashMap<String, NoticeText>(active.size)
        for (sbn in active) {
            if (sbn.packageName == app.packageName) continue
            val notification = sbn.notification ?: continue
            next[sbn.key] = noticeText(notification)
        }
        synchronized(postedLock) {
            baselineText.clear()
            baselineText.putAll(next)
        }
        return next.size.toString()
    }

    private fun closeArrivalGate() {
        armedAtMillis = Long.MAX_VALUE
        synchronized(postedLock) {
            baselineText.clear()
            acceptedSinceArm.clear()
        }
    }

    private fun arrivalOf(key: String, text: NoticeText, postTime: Long): Arrival {
        if (acceptedSinceArm.contains(key)) return Arrival.Fresh
        val baseline = baselineText[key]
        if (baseline != null) {
            if (baseline == text) return Arrival.Replay
            return Arrival.TextChanged
        }
        if (postTime < armedAtMillis) return Arrival.BeforeArm
        return Arrival.Fresh
    }

    private fun isDuplicate(posted: RibbonPost): Boolean {
        val now = SystemClock.elapsedRealtime()
        for (prev in recentPosts) {
            val sameLine = prev.pkg == posted.pkg && prev.line == posted.line
            val sameSecond = prev.postSecond == posted.postSecond ||
                now - prev.enqueuedAt <= SAME_TEXT_WINDOW_MS
            if (sameLine && sameSecond) return true
            // One ribbon per key until the visible text changes. Same-second
            // identical text from another key is still handled above.
            if (prev.key == posted.key && prev.line == posted.line) return true
        }
        return false
    }

    private fun rememberPost(posted: RibbonPost) {
        recentPosts.addLast(posted.copy(enqueuedAt = SystemClock.elapsedRealtime()))
        while (recentPosts.size > SHOWN_KEYS) recentPosts.removeFirst()
    }

    private fun rememberShownKey(key: String) {
        if (shownUpdateKeys.contains(key)) return
        shownUpdateKeys.addLast(key)
        while (shownUpdateKeys.size > SHOWN_KEYS) shownUpdateKeys.removeFirst()
    }

    private fun noticeText(notification: Notification): NoticeText {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return NoticeText(title, messageBody(extras))
    }

    private fun messageBody(extras: android.os.Bundle?): String? {
        if (extras == null) return null
        val messages = messagingMessages(extras)
        val newest = messages.lastOrNull()
        if (newest != null && newest.text.isNotEmpty()) return newest.text
        val direct = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct
        return messages.lastOrNull { it.text.isNotEmpty() }?.text
    }

    private fun messagingMessages(extras: android.os.Bundle): List<ChatMessage> {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return emptyList()
        val messages = ArrayList<ChatMessage>(raw.size)
        for (item in raw) {
            val bundle = item as? android.os.Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
            messages.add(ChatMessage(text, bundle.getLong("time")))
        }
        return messages
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

    private fun note(message: String) {
        DecisionLog.note(app, message)
        changed()
    }

    private fun contextLine(): String {
        return "armed=$armed fg=${currentForeground ?: "-"} listener=$listenerConnected rule=${ruleStateLabel()}"
    }

    private fun ruleStateLabel(): String {
        val saved = if (prefs.ruleId == null) "unsaved" else "saved"
        val active = if (ruleActive) "active" else "inactive"
        val flags = ArrayList<String>()
        if (suppressRearm) flags.add("snoozed")
        if (updatesDisabled) flags.add("disabled")
        if (ruleError) flags.add("error")
        val extra = if (flags.isEmpty()) "" else " " + flags.joinToString(",")
        return "$saved/$active$extra"
    }

    private fun zenStatusName(status: Int): String {
        return when (status) {
            NotificationManager.AUTOMATIC_RULE_STATUS_REMOVED -> "removed"
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED -> "deactivated"
            NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED -> "disabled"
            NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED -> "enabled"
            NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> "activated"
            else -> status.toString()
        }
    }

    private fun ordinaryAlert(rank: Int, channel: Int?, peekSuppressed: Boolean): Boolean {
        if (rank >= NotificationManager.IMPORTANCE_HIGH) return true
        if (channel != null && channel >= NotificationManager.IMPORTANCE_HIGH) return true
        return channel == null && peekSuppressed
    }

    private class Line(
        val text: String,
        val preview: Boolean,
        val packageName: String?
    ) {
        var blockLogged = false
    }

    private data class OpenActivity(val packageName: String, val time: Long)

    private data class ChatMessage(val text: String, val time: Long)

    private data class NoticeText(val title: String?, val body: String?)

    private enum class Arrival {
        Fresh,
        Replay,
        BeforeArm,
        TextChanged
    }

    private data class RibbonPost(
        val key: String,
        val pkg: String,
        val line: String,
        val postSecond: Long,
        val enqueuedAt: Long = 0L
    )

    private sealed class ForegroundRead {
        data class Known(val packageName: String?) : ForegroundRead()
        data object Unavailable : ForegroundRead()
    }

    private companion object {
        const val TAG = "Ticker"
        const val POLL_MS = 1000L
        const val SHOWN_KEYS = 40
        const val SAME_TEXT_WINDOW_MS = 1000L
        const val USAGE_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
