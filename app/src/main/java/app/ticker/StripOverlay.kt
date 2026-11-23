package app.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * One line under the status bar. The window is only as tall as the strip, and
 * it is not touch-modal, so taps outside the ribbon still reach the app
 * underneath. A tap on the strip itself is delivered to [StripView].
 *
 * The window is pinned to the current display's full width and to one
 * vertical position. Hiding the status bar must not move it or freeze the
 * line. The text scrolls on a clock, not on animation frames, so a foreground
 * app cannot leave the background showing with the glyphs stuck off-screen.
 */
internal data class StripAppearance(
    val textSizeSp: Int = DEFAULT_TEXT_SP,
    val scrollSpeedDp: Float = DEFAULT_SPEED_DP.toFloat(),
    val background: Int = DEFAULT_BACKGROUND,
    val textColor: Int = DEFAULT_TEXT_COLOR
) {
    companion object {
        const val DEFAULT_TEXT_SP = 14
        const val DEFAULT_SPEED_DP = 70
        const val DEFAULT_TEXT_COLOR = Color.WHITE
        const val DEFAULT_BG_RGB = 0xFF151515.toInt()
        const val DEFAULT_BG_ALPHA = 0xF2
        const val DEFAULT_BACKGROUND = 0xF2151515.toInt()
        const val NO_BACKGROUND = Color.TRANSPARENT
    }
}

internal object StripSteps {
    val textSp = intArrayOf(11, 12, 14, 18, 22)
    val speedScaleMilli = intArrayOf(250, 500, 1000, 1500, 2000)

    fun nearestText(value: Int): Int = textSp.minBy { kotlin.math.abs(it - value) }

    fun speedDp(index: Int): Float {
        return StripAppearance.DEFAULT_SPEED_DP * speedScaleMilli[index] / 1000f
    }

    fun nearestSpeedIndex(dp: Float): Int {
        return speedScaleMilli.indices.minBy { kotlin.math.abs(speedDp(it) - dp) }
    }

    fun nearestSpeed(dp: Float): Float = speedDp(nearestSpeedIndex(dp))
}

internal class StripOverlay(private val app: Context) {
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var view: StripView? = null
    private var params: WindowManager.LayoutParams? = null
    private var displayId: Int = Display.INVALID_DISPLAY
    private var rotation: Int = Surface.ROTATION_0
    private var appearance = StripAppearance()
    private var placeGeneration = 0
    private var alignAttempts = 0
    private var awaitingLayout = false
    private var pinnedStatusTop = -1
    private var pinnedScreenY = -1

    fun isShowing(): Boolean = view != null

    fun show(
        text: String,
        appearance: StripAppearance,
        onTap: ((Context) -> Unit)?,
        onFinished: () -> Unit
    ): Boolean {
        this.appearance = appearance
        if (!Settings.canDrawOverlays(app)) return false
        val display = activeDisplay() ?: return false
        if (view == null || display.displayId != displayId || display.rotation != rotation) {
            remove()
            if (!add(display)) return false
        }
        applyAppearance(appearance)
        val strip = view ?: return false
        strip.show(text, onTap, onFinished)
        schedulePlace()
        return true
    }

    fun applyAppearance(appearance: StripAppearance) {
        this.appearance = appearance
        val strip = view ?: return
        strip.applyAppearance(appearance)
        val layout = params ?: return
        val manager = windowManager ?: return
        val height = stripHeight(strip.resources.displayMetrics.density, appearance.textSizeSp)
        if (layout.height != height) {
            layout.height = height
            try {
                manager.updateViewLayout(strip, layout)
            } catch (_: IllegalArgumentException) {
                remove()
            }
        }
    }

    fun relayout() {
        if (view == null) return
        val display = activeDisplay() ?: return
        if (display.displayId != displayId || display.rotation != rotation) {
            val strip = view ?: return
            val text = strip.currentText
            val finished = strip.finishCallback
            val tap = strip.tapCallback
            val kept = strip.scrollOffset
            remove()
            if (text.isNotEmpty() && finished != null && add(display)) {
                view?.applyAppearance(appearance)
                view?.show(text, tap, finished)
                view?.restoreOffset(kept)
            } else {
                finished?.invoke()
            }
        } else {
            schedulePlace()
        }
    }

    fun remove() {
        val current = view
        val manager = windowManager
        view = null
        params = null
        windowManager = null
        windowContext = null
        displayId = Display.INVALID_DISPLAY
        awaitingLayout = false
        pinnedStatusTop = -1
        pinnedScreenY = -1
        if (current != null && manager != null) {
            try {
                manager.removeView(current)
            } catch (_: IllegalArgumentException) {
                // The window was already gone.
            }
        }
    }

    private fun add(display: Display): Boolean {
        if (attach(display, beyondScreen = true)) return true
        return attach(display, beyondScreen = false)
    }

    private fun attach(display: Display, beyondScreen: Boolean): Boolean {
        return try {
            val context = windowContextFor(display)
            val manager = context.getSystemService(WindowManager::class.java) ?: return false
            val span = displaySpan(display, manager)
            val density = context.resources.displayMetrics.density
            val height = stripHeight(density, appearance.textSizeSp)
            // Not focusable, so the keyboard stays with the app underneath.
            // Not touch-modal, so only the strip's own bounds take taps.
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (beyondScreen) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            val layout = WindowManager.LayoutParams(
                span.width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = statusBarInset(manager, context, span)
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    // Keep the top inset even when the status bar is hidden, so
                    // the line does not jump into that slot. Left and right
                    // insets are what collapse the window during fullscreen video.
                    setFitInsetsTypes(WindowInsets.Type.systemBars())
                    setFitInsetsSides(WindowInsets.Side.TOP or WindowInsets.Side.BOTTOM)
                    setFitInsetsIgnoringVisibility(true)
                }
                title = "Ribbon Notifs"
            }
            val strip = StripView(context)
            strip.applyAppearance(appearance)
            strip.fitsSystemWindows = false
            strip.setOnApplyWindowInsetsListener { _, insets ->
                schedulePlace()
                insets
            }
            strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                awaitingLayout = false
                schedulePlace()
            }
            manager.addView(strip, layout)
            windowContext = context
            windowManager = manager
            view = strip
            params = layout
            displayId = display.displayId
            rotation = display.rotation
            alignAttempts = 0
            awaitingLayout = false
            schedulePlace()
            true
        } catch (error: RuntimeException) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "overlay add failed: ${error.javaClass.simpleName}")
            }
            remove()
            false
        }
    }

    private fun schedulePlace() {
        val strip = view ?: return
        val generation = ++placeGeneration
        strip.post {
            if (generation != placeGeneration || view !== strip) return@post
            place()
        }
    }

    /**
     * Keeps the strip as wide as the display that is actually on, and its left
     * edge on the left of that display. A fullscreen player can report a parent
     * frame that is only a few pixels wide; matching that frame is what left
     * the line sitting on the side.
     */
    private fun place() {
        if (awaitingLayout) return
        val strip = view ?: return
        val layout = params ?: return
        val manager = windowManager ?: return
        val display = activeDisplay() ?: return
        if (display.displayId != displayId || display.rotation != rotation) {
            relayout()
            return
        }
        val context = windowContext ?: strip.context
        val span = displaySpan(display, manager)
        val height = stripHeight(strip.resources.displayMetrics.density, appearance.textSizeSp)
        val wantY = statusBarInset(manager, context, span)
        val laidOut = strip.isLaidOut && strip.width > 0
        if (!laidOut) {
            val changed = assignFrame(layout, span.width, height, layout.x, wantY)
            if (changed) updateFrame(manager, strip, layout)
            return
        }
        val loc = IntArray(2)
        strip.getLocationOnScreen(loc)
        val slack = (8 * strip.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val widthOk = strip.width + slack >= span.width
        // A full-width line may sit a few pixels in from a cutout. Only treat
        // that as the fullscreen slide when it is a real fraction of the screen.
        val xOk = if (widthOk) {
            kotlin.math.abs(loc[0]) <= span.width / 10
        } else {
            kotlin.math.abs(loc[0]) <= slack
        }
        if (pinnedScreenY < 0 && statusBarShown(manager) && loc[1] >= 0 && loc[1] <= span.height / 3) {
            pinnedScreenY = loc[1]
        }
        val yOk = pinnedScreenY < 0 || kotlin.math.abs(loc[1] - pinnedScreenY) <= slack
        if (widthOk && xOk && yOk) {
            alignAttempts = 0
            return
        }
        if (alignAttempts >= 2) return
        val nextX = if (xOk) layout.x else layout.x - loc[0]
        val nextY = if (pinnedScreenY >= 0) {
            layout.y + (pinnedScreenY - loc[1])
        } else {
            wantY
        }
        val changed = assignFrame(layout, span.width, height, nextX, nextY)
        if (!changed) {
            alignAttempts = 2
            DecisionLog.note(
                app,
                "overlay stayed short span=${span.width} measured=${strip.width} " +
                    "at=${loc[0]},${loc[1]} rotation=${display.rotation}"
            )
            return
        }
        alignAttempts++
        DecisionLog.note(
            app,
            "overlay realign span=${span.width} measured=${strip.width} " +
                "at=${loc[0]},${loc[1]} rotation=${display.rotation}"
        )
        updateFrame(manager, strip, layout)
    }

    private fun assignFrame(
        layout: WindowManager.LayoutParams,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Boolean {
        var changed = false
        if (layout.width != width) {
            layout.width = width
            changed = true
        }
        if (layout.height != height) {
            layout.height = height
            changed = true
        }
        if (layout.x != x) {
            layout.x = x
            changed = true
        }
        if (layout.y != y) {
            layout.y = y
            changed = true
        }
        layout.gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.systemBars()
            val sides = WindowInsets.Side.TOP or WindowInsets.Side.BOTTOM
            if (layout.getFitInsetsTypes() != types ||
                layout.getFitInsetsSides() != sides ||
                !layout.isFitInsetsIgnoringVisibility()
            ) {
                layout.setFitInsetsTypes(types)
                layout.setFitInsetsSides(sides)
                layout.setFitInsetsIgnoringVisibility(true)
                changed = true
            }
        }
        return changed
    }

    private fun updateFrame(
        manager: WindowManager,
        strip: StripView,
        layout: WindowManager.LayoutParams
    ) {
        awaitingLayout = true
        try {
            manager.updateViewLayout(strip, layout)
        } catch (_: IllegalArgumentException) {
            awaitingLayout = false
            remove()
            return
        }
        strip.postDelayed({
            if (awaitingLayout && view === strip) {
                awaitingLayout = false
                schedulePlace()
            }
        }, 32)
    }

    private fun activeDisplay(): Display? {
        val manager = app.getSystemService(DisplayManager::class.java) ?: return null
        val displays = manager.displays ?: return manager.getDisplay(Display.DEFAULT_DISPLAY)
        val on = displays.filter { it.state == Display.STATE_ON }
        return on.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
            ?: on.firstOrNull()
            ?: manager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun windowContextFor(display: Display): Context {
        return if (Build.VERSION.SDK_INT >= 31) {
            app.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
        } else if (Build.VERSION.SDK_INT >= 30) {
            app.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
        } else {
            app.createDisplayContext(display)
        }
    }

    private data class Span(val width: Int, val height: Int)

    private fun displaySpan(display: Display, manager: WindowManager): Span {
        val mode = display.mode
        val swap = display.rotation == Surface.ROTATION_90 ||
            display.rotation == Surface.ROTATION_270
        var width = if (swap) mode.physicalHeight else mode.physicalWidth
        var height = if (swap) mode.physicalWidth else mode.physicalHeight
        val real = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        val shortest = minOf(mode.physicalWidth, mode.physicalHeight).coerceAtLeast(1)
        // A collapsed inset frame is much smaller than the panel. Ignore it and
        // keep the rotated hardware size so fullscreen video cannot shrink the line.
        if (real.x >= shortest / 2 && real.y >= shortest / 2) {
            width = real.x
            height = real.y
        }
        if (Build.VERSION.SDK_INT >= 30 && (width <= 0 || height <= 0)) {
            val bounds = manager.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        }
        return Span(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    private fun statusBarShown(manager: WindowManager): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return manager.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars())
    }

    private fun statusBarInset(manager: WindowManager, context: Context, span: Span): Int {
        if (pinnedStatusTop > 0) return pinnedStatusTop
        val limit = (minOf(span.width, span.height) / 3).coerceAtLeast(1)
        var top = 0
        if (Build.VERSION.SDK_INT >= 30) {
            top = manager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
        }
        if (top <= 0 || top > limit) {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val fallback = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            top = if (fallback in 1..limit) fallback else 0
        }
        if (top > 0) pinnedStatusTop = top
        return top
    }

    private fun stripHeight(density: Float, textSp: Int): Int {
        val base = (32 * density).toInt()
        val needed = (textSp * density + 12 * density).toInt()
        return maxOf(base, needed).coerceAtLeast(1)
    }

    private companion object {
        const val TAG = "Ticker"
    }
}

internal class StripView(context: Context) : View(context) {
    private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            14f,
            resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private var speedDp = StripAppearance.DEFAULT_SPEED_DP.toFloat()
    private var line = ""
    private var offset = 0f
    private var lastFrame = 0L
    private var posted = false
    var finishCallback: (() -> Unit)? = null
        private set
    var tapCallback: ((Context) -> Unit)? = null
        private set

    val currentText: String get() = line
    val scrollOffset: Float get() = offset

    private val clock = Handler(Looper.getMainLooper())
    private val tick = Runnable { frame() }

    init {
        setBackgroundColor(0xF2151515.toInt())
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun applyAppearance(appearance: StripAppearance) {
        speedDp = appearance.scrollSpeedDp
        paint.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            appearance.textSizeSp.toFloat(),
            resources.displayMetrics
        )
        paint.color = appearance.textColor
        setBackgroundColor(appearance.background)
        invalidate()
    }

    fun show(text: String, onTap: ((Context) -> Unit)?, onFinished: () -> Unit) {
        line = text
        tapCallback = onTap
        finishCallback = onFinished
        offset = 0f
        lastFrame = SystemClock.uptimeMillis()
        contentDescription = text
        isClickable = onTap != null
        if (onTap == null) {
            setOnClickListener(null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            setOnClickListener { onTap(context) }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        start()
        invalidate()
    }

    fun restoreOffset(value: Float) {
        offset = value.coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (line.isEmpty()) return
        val y = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawText(line, width - offset, y, paint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (posted) return
        posted = true
        clock.postDelayed(tick, FRAME_MS)
    }

    private fun stop() {
        posted = false
        clock.removeCallbacks(tick)
    }

    private fun frame() {
        posted = false
        if (finishCallback == null || !isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 50L)
        lastFrame = now
        // A zero width means the window has not been laid out yet. Keep the
        // clock running, but do not treat that as a finished line. Once the
        // view has any real width, scroll it; do not wait for a guessed
        // display size, or the glyphs stay clipped off the right edge.
        if (width <= 0) {
            invalidate()
            start()
            return
        }
        val travel = width + paint.measureText(line)
        val speed = speedDp * resources.displayMetrics.density
        offset = (offset + speed * dt / 1000f).coerceAtMost(travel)
        if (offset >= travel - 0.5f) {
            val done = finishCallback
            finishCallback = null
            stop()
            done?.invoke()
            return
        }
        invalidate()
        start()
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
