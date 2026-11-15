package app.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * One non-touchable line under the status bar. The window flags are what let
 * taps fall through; the view itself never asks for focus.
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
    private var appearance = StripAppearance()

    fun isShowing(): Boolean = view != null

    fun show(text: String, appearance: StripAppearance, onFinished: () -> Unit): Boolean {
        this.appearance = appearance
        if (!Settings.canDrawOverlays(app)) return false
        val display = activeDisplay() ?: return false
        if (view == null || display.displayId != displayId) {
            remove()
            if (!add(display)) return false
        }
        applyAppearance(appearance)
        val strip = view ?: return false
        strip.show(text, onFinished)
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
        val display = activeDisplay()
        if (display != null && display.displayId != displayId) {
            val strip = view ?: return
            val text = strip.currentText
            val finished = strip.finishCallback
            remove()
            if (text.isNotEmpty() && finished != null && add(display)) {
                view?.applyAppearance(appearance)
                view?.show(text, finished)
            } else {
                finished?.invoke()
            }
        } else {
            updateOffset()
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
        if (current != null && manager != null) {
            try {
                manager.removeView(current)
            } catch (_: IllegalArgumentException) {
                // The window was already gone.
            }
        }
    }

    private fun add(display: Display): Boolean {
        return try {
            val context = windowContextFor(display)
            val manager = context.getSystemService(WindowManager::class.java) ?: return false
            val density = context.resources.displayMetrics.density
            val height = stripHeight(density, appearance.textSizeSp)
            val layout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = statusBarInset(manager)
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                title = "Ribbon Notifs"
            }
            val strip = StripView(context)
            strip.applyAppearance(appearance)
            strip.setOnApplyWindowInsetsListener { _, insets ->
                val top = statusBarTop(insets)
                val current = params
                if (top > 0 && current != null && current.y != top) {
                    current.y = top
                    try {
                        manager.updateViewLayout(strip, current)
                    } catch (_: IllegalArgumentException) {
                        // Removed between the inset and the update.
                    }
                }
                insets
            }
            manager.addView(strip, layout)
            windowContext = context
            windowManager = manager
            view = strip
            params = layout
            displayId = display.displayId
            true
        } catch (error: RuntimeException) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "overlay add failed: ${error.javaClass.simpleName}")
            }
            remove()
            false
        }
    }

    private fun updateOffset() {
        val manager = windowManager ?: return
        val strip = view ?: return
        val layout = params ?: return
        val top = statusBarInset(manager)
        if (layout.y != top) {
            layout.y = top
            try {
                manager.updateViewLayout(strip, layout)
            } catch (_: IllegalArgumentException) {
                remove()
            }
        }
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

    private fun statusBarInset(manager: WindowManager): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val top = manager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
            if (top > 0) return top
        }
        val resId = app.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) app.resources.getDimensionPixelSize(resId) else 0
    }

    private fun stripHeight(density: Float, textSp: Int): Int {
        val base = (32 * density).toInt()
        val needed = (textSp * density + 12 * density).toInt()
        return maxOf(base, needed).coerceAtLeast(1)
    }

    private fun statusBarTop(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop
        }
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

    val currentText: String get() = line

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

    fun show(text: String, onFinished: () -> Unit) {
        line = text
        finishCallback = onFinished
        offset = 0f
        lastFrame = SystemClock.uptimeMillis()
        contentDescription = text
        start()
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
        postOnAnimation(tick)
    }

    private fun stop() {
        posted = false
        removeCallbacks(tick)
    }

    private fun frame() {
        posted = false
        if (!isAttachedToWindow || finishCallback == null) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 50L)
        lastFrame = now
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
}
