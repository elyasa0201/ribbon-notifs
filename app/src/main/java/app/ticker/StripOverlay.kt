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
internal class StripOverlay(private val app: Context) {
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var view: StripView? = null
    private var params: WindowManager.LayoutParams? = null
    private var displayId: Int = Display.INVALID_DISPLAY

    fun isShowing(): Boolean = view != null

    fun show(text: String, onFinished: () -> Unit): Boolean {
        if (!Settings.canDrawOverlays(app)) return false
        val display = activeDisplay() ?: return false
        if (view == null || display.displayId != displayId) {
            remove()
            if (!add(display)) return false
        }
        val strip = view ?: return false
        strip.show(text, onFinished)
        return true
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
            val height = (32 * density).toInt().coerceAtLeast(1)
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
                title = "Ticker"
            }
            val strip = StripView(context)
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
    private var line = ""
    private var offset = 0f
    private var phase = PHASE_HOLD
    private var phaseStart = 0L
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

    fun show(text: String, onFinished: () -> Unit) {
        line = text
        finishCallback = onFinished
        offset = 0f
        phase = PHASE_HOLD
        phaseStart = SystemClock.uptimeMillis()
        lastFrame = phaseStart
        contentDescription = text
        start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (line.isEmpty()) return
        val y = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
        canvas.drawText(line, paddingLeft - offset, y, paint)
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
        val usable = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val overflow = (paint.measureText(line) - usable).coerceAtLeast(0f)
        when (phase) {
            PHASE_HOLD -> {
                if (now - phaseStart >= 800L) {
                    phase = if (overflow > 0f) PHASE_SCROLL else PHASE_END
                    phaseStart = now
                }
            }
            PHASE_SCROLL -> {
                val speed = 70f * resources.displayMetrics.density
                offset = (offset + speed * dt / 1000f).coerceAtMost(overflow)
                if (offset >= overflow - 0.5f) {
                    offset = overflow
                    phase = PHASE_END
                    phaseStart = now
                }
            }
            PHASE_END -> {
                if (now - phaseStart >= 1200L) {
                    val done = finishCallback
                    finishCallback = null
                    stop()
                    done?.invoke()
                    return
                }
            }
        }
        invalidate()
        start()
    }

    private companion object {
        const val PHASE_HOLD = 0
        const val PHASE_SCROLL = 1
        const val PHASE_END = 2
    }
}
