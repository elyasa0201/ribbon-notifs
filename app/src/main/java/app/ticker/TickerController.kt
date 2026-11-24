package app.ticker

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.ArrayDeque

/**
 * While a chosen app is in front, ordinary alerts go on the strip and are then
 * cancelled so the heads-up cannot stay up. Cancelling removes the shade entry
 * too; that is the public dismiss call. Calls, alarms, ongoing events, and
 * media are left in place. Do Not Disturb is never turned on.
 *
 * Failure handling lives here: a missing grant, a null usage result before the
 * first unlock after boot, and listener disconnect.
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
    private var currentForeground: String? = null

    /**
     * Last selected app that was still resumed. A poll that finds nobody in
     * front is a lost read, so this stays armed. A browser or launcher on top
     * does not clear it while that app is still resumed, and a brief flicker
     * of those apps does not count as leaving.
     */
    private var heldSelected: String? = null
    private var passingSince = Long.MAX_VALUE

    /**
     * Activities resumed and not yet paused. Kept across polls. Scrolling does
     * not emit a usage event, and One UI can omit the original resume from a
     * later query, so a poll with no new event must not clear this.
     * [pausedAt] is the latest pause or stop for that activity. A resume at or
     * before that time is already paused and must not become foreground.
     */
    private val usageLock = Any()
    private val resumed = LinkedHashMap<String, OpenActivity>()
    private val pausedAt = HashMap<String, Long>()
    private val passingLock = Any()
    private var passingPackages: Set<String>? = null
    private var usageCursor = Long.MIN_VALUE
    private var usageCursorCount = 0
    private var usageCoveredFrom = Long.MAX_VALUE
    private var usageReady = false
    private var usageQueryLocked = false
    @Volatile private var usageGeneration = 0
    private var showing: Line? = null
    private var usageWarned = false
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

    val masterEnabled: Boolean get() = prefs.masterEnabled
    fun isSelected(packageName: String): Boolean = packageName in prefs.selectedPackages

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun onProcessStart() {
        registerReceivers()
        armed = false
        closeArrivalGate()
        refreshInstalledApps()
    }

    fun onSetupVisible() {
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
            heldSelected = null
            passingSince = Long.MAX_VALUE
            closeArrivalGate()
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

    fun stripAppearance(): StripAppearance = currentAppearance()

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

    fun previewStrip(): Boolean {
        if (!Grants.overlay(app)) return false
        enqueue(Line(app.getString(R.string.preview_line), preview = true, packageName = null))
        return true
    }

    fun onListenerConnected() {
        forgetUsage()
        heldSelected = null
        passingSince = Long.MAX_VALUE
        listenerConnected = true
        armed = false
        closeArrivalGate()
        startPolling()
        changed()
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        forgetUsage()
        heldSelected = null
        passingSince = Long.MAX_VALUE
        stopPolling()
        armed = false
        closeArrivalGate()
        clearStrip()
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
        showOnRibbon(
            Line(
                line,
                preview = false,
                packageName = pkg,
                contentIntent = notification.contentIntent
            )
        ) {
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
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(screenReceiver, screen)
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
            val generation = usageGeneration
            val read = readForeground()
            main.post {
                if (!listenerConnected || generation != usageGeneration) return@post
                if (prefs.masterEnabled && !Grants.core(app)) {
                    setMasterEnabled(false)
                }
                if (read is ForegroundRead.Known) onUsageForeground(read.packageName)
                syncStripVisibility()
            }
            pollHandler?.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Newest activity that is still resumed. The resume is remembered across
     * polls. One UI can omit that resume from a long queryEvents call, and
     * scrolling does not emit another one, so an empty slice is not "left".
     */
    private fun readForeground(): ForegroundRead {
        if (!Grants.usage(app)) {
            synchronized(usageLock) {
                resumed.clear()
                pausedAt.clear()
                usageReady = false
                usageCursor = Long.MIN_VALUE
                usageCursorCount = 0
                usageCoveredFrom = Long.MAX_VALUE
            }
            return ForegroundRead.Known(null)
        }
        val users = app.getSystemService(UserManager::class.java)
        if (users != null && !users.isUserUnlocked) return ForegroundRead.Unavailable
        if (app.getSystemService(UsageStatsManager::class.java) == null) {
            return ForegroundRead.Unavailable
        }
        usageQueryLocked = false
        val end = System.currentTimeMillis() + 1
        val generation = usageGeneration
        val ready = synchronized(usageLock) { usageReady && generation == usageGeneration }
        if (!ready) return loadUsage(end, generation, lookbackStart(end))
        val followed = catchUpUsage(end, generation)
        if (followed is ForegroundRead.Known && followed.packageName == null) {
            val covered = synchronized(usageLock) { usageCoveredFrom }
            // The tracked activity paused. An older one may still be resumed
            // further back than the slice loaded so far.
            if (covered > lookbackStart(end)) return loadUsage(end, generation, lookbackStart(end))
        }
        return followed
    }

    /**
     * Reads usage history in short slices from [end] back to [earliest],
     * newest first, and stops once a still-resumed activity is found.
     * The open set is kept in memory; later polls only add newer events.
     */
    private fun loadUsage(end: Long, generation: Int, earliest: Long): ForegroundRead {
        val chunks = ArrayDeque<List<ActivityEvent>>()
        var rangeStart = end
        while (true) {
            if (generation != usageGeneration) return ForegroundRead.Unavailable
            val chunkStart = maxOf(earliest, rangeStart - USAGE_CHUNK_MS)
            val chunk = queryEvents(chunkStart, rangeStart) ?: return rememberUsageFailure()
            chunks.addFirst(chunk)
            val open = LinkedHashMap<String, OpenActivity>()
            val paused = HashMap<String, Long>()
            var cursor = Long.MIN_VALUE
            var count = 0
            for (events in chunks) {
                for (event in events) {
                    if (event.time == cursor) {
                        count += 1
                    } else {
                        cursor = event.time
                        count = 1
                    }
                }
                for (event in chronological(events)) {
                    applyUsage(event, open, paused)
                }
            }
            if (open.isNotEmpty() || chunkStart <= earliest) {
                val cursorTime = if (cursor == Long.MIN_VALUE) end else cursor
                val cursorCount = if (cursor == Long.MIN_VALUE) 0 else count
                if (!commitUsage(generation, open, paused, cursorTime, cursorCount, chunkStart)) {
                    return ForegroundRead.Unavailable
                }
                usageWarned = false
                return ForegroundRead.Known(foregroundPackage(open))
            }
            rangeStart = chunkStart
        }
    }

    private fun catchUpUsage(end: Long, generation: Int): ForegroundRead {
        val cursor: Long
        val count: Int
        val covered: Long
        val open: LinkedHashMap<String, OpenActivity>
        val paused: HashMap<String, Long>
        synchronized(usageLock) {
            if (generation != usageGeneration || !usageReady) return ForegroundRead.Unavailable
            cursor = usageCursor
            count = usageCursorCount
            covered = usageCoveredFrom
            open = LinkedHashMap(resumed)
            paused = HashMap(pausedAt)
        }
        val events = queryEvents(cursor.coerceAtLeast(0L), end) ?: return rememberUsageFailure()
        var seenAtCursor = 0
        var newCursor = cursor
        var newCount = count
        val fresh = ArrayList<ActivityEvent>()
        for (event in events) {
            if (event.time < cursor) continue
            if (event.time == cursor) {
                seenAtCursor += 1
                if (seenAtCursor <= count) continue
            } else {
                seenAtCursor = 1
            }
            fresh.add(event)
            newCursor = event.time
            newCount = seenAtCursor
        }
        for (event in chronological(fresh)) {
            applyUsage(event, open, paused)
        }
        if (open.isEmpty() && covered > lookbackStart(end)) {
            // A pause closed the activity we had. Do not commit that empty
            // set over history we have not loaded yet.
            return ForegroundRead.Known(null)
        }
        if (!commitUsage(generation, open, paused, newCursor, newCount, covered)) {
            return ForegroundRead.Unavailable
        }
        usageWarned = false
        return ForegroundRead.Known(foregroundPackage(open))
    }

    private fun applyUsage(
        event: ActivityEvent,
        open: MutableMap<String, OpenActivity>,
        paused: MutableMap<String, Long>
    ) {
        when (event.type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                val key = activityKey(event.packageName, event.className)
                if (alreadyPaused(event, paused)) return
                paused.remove(key)
                open[key] = OpenActivity(event.packageName, event.time)
            }
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED -> {
                if (event.className.isEmpty()) {
                    val mark = maxOf(paused[event.packageName] ?: 0L, event.time)
                    paused[event.packageName] = mark
                    val iterator = open.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.value.packageName != event.packageName) continue
                        if (event.time < entry.value.time) continue
                        paused[entry.key] = event.time
                        iterator.remove()
                    }
                } else {
                    val key = activityKey(event.packageName, event.className)
                    val existing = open[key]
                    if (existing != null && event.time < existing.time) return
                    paused[key] = event.time
                    open.remove(key)
                }
            }
        }
    }

    /**
     * A resume that shows up after the activity was already paused, at or
     * before that pause, is a fold replay. It must not become foreground.
     */
    private fun alreadyPaused(event: ActivityEvent, paused: Map<String, Long>): Boolean {
        val key = activityKey(event.packageName, event.className)
        val atKey = paused[key]
        if (atKey != null && event.time <= atKey) return true
        val atPackage = paused[event.packageName]
        return atPackage != null && event.time <= atPackage
    }

    private fun activityKey(packageName: String, className: String): String {
        return if (className.isEmpty()) packageName else "$packageName/$className"
    }

    private fun chronological(events: List<ActivityEvent>): List<ActivityEvent> {
        if (events.size < 2) return events
        return events.sortedWith(
            compareBy<ActivityEvent> { it.time }.thenBy { resumeRank(it.type) }
        )
    }

    private fun resumeRank(type: Int): Int {
        return if (type == UsageEvents.Event.ACTIVITY_RESUMED) 1 else 0
    }

    /**
     * Newest still-resumed activity. A browser or launcher does not take the
     * foreground while a selected app is still resumed.
     */
    private fun foregroundPackage(open: Map<String, OpenActivity>): String? {
        val newest = open.values.maxByOrNull { it.time } ?: return null
        val selected = prefs.selectedPackages
        val own = app.packageName
        val selectedStill = open.values
            .filter { it.packageName != own && it.packageName in selected }
            .maxByOrNull { it.time }
        if (
            selectedStill != null &&
            selectedStill.packageName != newest.packageName &&
            isPassingApp(newest.packageName)
        ) {
            return selectedStill.packageName
        }
        return newest.packageName
    }

    private fun onUsageForeground(raw: String?) {
        val selected = prefs.selectedPackages
        val own = app.packageName
        if (heldSelected != null && (heldSelected !in selected || heldSelected == own)) {
            heldSelected = null
        }
        val rawSelected = raw != null && raw != own && raw in selected
        val passing = raw != null && !rawSelected && isPassingApp(raw)
        val now = SystemClock.elapsedRealtime()
        if (passing && heldSelected != null) {
            if (passingSince == Long.MAX_VALUE) passingSince = now
        } else {
            passingSince = Long.MAX_VALUE
        }
        val passingHeld = passing &&
            heldSelected != null &&
            now - passingSince < PASSING_GRACE_MS
        val chosen = when {
            rawSelected -> raw
            raw == null -> heldSelected
            passingHeld -> heldSelected
            else -> raw
        }
        applyForeground(chosen)
        when {
            rawSelected -> heldSelected = raw
            raw == null || passingHeld -> Unit
            else -> heldSelected = null
        }
    }

    private fun isPassingApp(packageName: String): Boolean {
        synchronized(passingLock) {
            val known = passingPackages ?: resolvePassingPackages().also { passingPackages = it }
            return packageName in known
        }
    }

    private fun resolvePassingPackages(): Set<String> {
        val names = HashSet<String>()
        val pm = app.packageManager
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            for (info in pm.queryIntentActivities(home, 0)) {
                names.add(info.activityInfo.packageName)
            }
        } catch (_: RuntimeException) {
            // A launcher query failure leaves the role holder, if any.
        }
        try {
            val browse = Intent(Intent.ACTION_VIEW, Uri.parse("https://localhost"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            for (info in pm.queryIntentActivities(browse, PackageManager.MATCH_ALL)) {
                names.add(info.activityInfo.packageName)
            }
        } catch (_: RuntimeException) {
            // A browser query failure leaves the role holder, if any.
        }
        names.remove(app.packageName)
        return names
    }

    private fun queryEvents(start: Long, end: Long): List<ActivityEvent>? {
        if (end <= start) return emptyList()
        val out = ArrayList<ActivityEvent>()
        var cursor = start
        while (cursor < end) {
            val chunkEnd = minOf(end, cursor + USAGE_CHUNK_MS)
            val chunk = querySlice(cursor, chunkEnd, 0) ?: return null
            out.addAll(chunk)
            cursor = chunkEnd
        }
        return out
    }

    private fun querySlice(start: Long, end: Long, depth: Int): List<ActivityEvent>? {
        if (end <= start) return emptyList()
        val page = fetchEvents(start, end) ?: return null
        val span = end - start
        if (page.size < USAGE_PAGE_CAP || span <= USAGE_MIN_SPAN_MS || depth >= 8) return page
        val mid = start + span / 2
        if (mid <= start || mid >= end) return page
        val older = querySlice(start, mid, depth + 1) ?: return null
        val newer = querySlice(mid, end, depth + 1) ?: return null
        return ArrayList<ActivityEvent>(older.size + newer.size).apply {
            addAll(older)
            addAll(newer)
        }
    }

    private fun fetchEvents(start: Long, end: Long): List<ActivityEvent>? {
        val stats = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val raw = try {
            stats.queryEvents(start, end)
        } catch (_: SecurityException) {
            return null
        }
        // Null is the direct-boot case: the user has not unlocked since boot.
        if (raw == null) {
            usageQueryLocked = true
            return null
        }
        val out = ArrayList<ActivityEvent>()
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            val type = event.eventType
            if (
                type != UsageEvents.Event.ACTIVITY_RESUMED &&
                type != UsageEvents.Event.ACTIVITY_PAUSED &&
                type != UsageEvents.Event.ACTIVITY_STOPPED
            ) {
                continue
            }
            val packageName = event.packageName ?: continue
            if (packageName.isEmpty()) continue
            val time = event.timeStamp
            if (time < start || time >= end) continue
            out.add(ActivityEvent(type, packageName, event.className.orEmpty(), time))
        }
        return out
    }

    private fun lookbackStart(now: Long): Long {
        val sinceBoot = now - SystemClock.elapsedRealtime()
        return maxOf(sinceBoot, now - USAGE_LOOKBACK_MS)
    }

    private fun commitUsage(
        generation: Int,
        open: Map<String, OpenActivity>,
        paused: Map<String, Long>,
        cursor: Long,
        cursorCount: Int,
        coveredFrom: Long
    ): Boolean {
        synchronized(usageLock) {
            if (generation != usageGeneration) return false
            resumed.clear()
            resumed.putAll(open)
            pausedAt.clear()
            pausedAt.putAll(paused)
            usageCursor = cursor
            usageCursorCount = cursorCount
            usageCoveredFrom = coveredFrom
            usageReady = true
        }
        return true
    }

    private fun rememberUsageFailure(): ForegroundRead {
        if (usageQueryLocked && !usageWarned) {
            usageWarned = true
            note("usage unavailable ${contextLine()} reason=locked")
        }
        return ForegroundRead.Unavailable
    }

    private fun forgetUsage() {
        synchronized(usageLock) {
            resumed.clear()
            pausedAt.clear()
            usageReady = false
            usageCursor = Long.MIN_VALUE
            usageCursorCount = 0
            usageCoveredFrom = Long.MAX_VALUE
            usageGeneration++
        }
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
            if (entered) note("armed ${contextLine()} baseline=$baseline")
        } else if (armed) {
            armed = false
            closeArrivalGate()
            currentForeground = packageName
            dropAlertLines()
            note("disarmed ${contextLine()} reason=left")
        } else {
            currentForeground = packageName
        }
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
            val shown = overlay.show(
                next.text,
                currentAppearance(),
                tapFor(next)
            ) { main.post { onLineFinished() } }
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

    private fun tapFor(line: Line): ((Context) -> Unit)? {
        if (line.preview) return null
        if (line.packageName.isNullOrEmpty() && line.contentIntent == null) return null
        return { context -> openSender(context, line) }
    }

    /**
     * The shade entry is already gone, so the tap cannot be a shade click.
     * The notification's own content intent still fires without that entry.
     * If it is missing or cancelled, open the app's launcher activity.
     */
    private fun openSender(context: Context, line: Line) {
        val pkg = line.packageName
        val pending = line.contentIntent
        if (pending != null && pending.opensActivity() && sendContentIntent(context, pending)) {
            note("open pkg=${pkg ?: "-"} ${contextLine()} reason=content-intent")
            return
        }
        if (pkg != null && openLauncher(context, pkg)) {
            note("open pkg=$pkg ${contextLine()} reason=launcher")
            return
        }
        note("open failed pkg=${pkg ?: "-"} ${contextLine()} reason=no-launcher")
    }

    private fun PendingIntent.opensActivity(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return isActivity
        return true
    }

    private fun sendContentIntent(context: Context, pending: PendingIntent): Boolean {
        return try {
            pending.send(context, 0, null, null, null, null, backgroundStartOptions())
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private fun openLauncher(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun backgroundStartOptions(): android.os.Bundle? {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 36) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            )
        } else if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityLaunchAllowed(true)
        }
        return options.toBundle()
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
        return "armed=$armed fg=${currentForeground ?: "-"} listener=$listenerConnected"
    }

    private fun ordinaryAlert(rank: Int, channel: Int?, peekSuppressed: Boolean): Boolean {
        if (rank >= NotificationManager.IMPORTANCE_HIGH) return true
        if (channel != null && channel >= NotificationManager.IMPORTANCE_HIGH) return true
        return channel == null && peekSuppressed
    }

    private class Line(
        val text: String,
        val preview: Boolean,
        val packageName: String?,
        val contentIntent: PendingIntent? = null
    ) {
        var blockLogged = false
    }

    private data class OpenActivity(val packageName: String, val time: Long)

    private data class ActivityEvent(
        val type: Int,
        val packageName: String,
        val className: String,
        val time: Long
    )

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
        const val USAGE_CHUNK_MS = 30L * 60L * 1000L
        const val USAGE_PAGE_CAP = 250
        const val USAGE_MIN_SPAN_MS = 60_000L
        const val PASSING_GRACE_MS = 8_000L
    }
}
