package app.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hue = 0f
    private var saturation = 0f
    private var markerVisible = false
    private var wheelShaderReady = false

    fun setColor(color: Int) {
        if (Color.alpha(color) == 0) {
            markerVisible = false
            invalidate()
            return
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] < 0.5f) {
            markerVisible = false
            invalidate()
            return
        }
        hue = hsv[0]
        saturation = hsv[1]
        markerVisible = true
        invalidate()
    }

    fun clearSelection() {
        markerVisible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - markerPaint.strokeWidth * 2f
        if (radius <= 0f) return
        if (!wheelShaderReady) {
            huePaint.shader = SweepGradient(cx, cy, HUES, null)
            satPaint.shader = RadialGradient(
                cx,
                cy,
                radius,
                Color.WHITE,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            wheelShaderReady = true
        }
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        if (!markerVisible) return
        val angle = Math.toRadians(hue.toDouble())
        val distance = saturation * radius
        val x = cx + (cos(angle) * distance).toFloat()
        val y = cy + (sin(angle) * distance).toFloat()
        val mark = 8f * resources.displayMetrics.density
        markerFill.color = Color.HSVToColor(floatArrayOf(hue, saturation, 1f))
        canvas.drawCircle(x, y, mark, markerFill)
        canvas.drawCircle(x, y, mark, markerPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wheelShaderReady = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - markerPaint.strokeWidth * 2f
        if (radius <= 0f) return false
        val dx = event.x - cx
        val dy = event.y - cy
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (event.action == MotionEvent.ACTION_DOWN && distance > radius) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        val clamped = distance.coerceAtMost(radius)
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (degrees < 0f) degrees += 360f
        hue = degrees
        saturation = (clamped / radius).coerceIn(0f, 1f)
        markerVisible = true
        invalidate()
        onColorChanged?.invoke(Color.HSVToColor(floatArrayOf(hue, saturation, 1f)))
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private companion object {
        val HUES = intArrayOf(
            Color.RED,
            Color.YELLOW,
            Color.GREEN,
            Color.CYAN,
            Color.BLUE,
            Color.MAGENTA,
            Color.RED
        )
    }
}
