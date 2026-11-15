package app.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A short hue strip. It replaces the full color wheel and stays one row tall.
 */
class HueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var hue = 0f
    private var selected = false
    private var shaderReady = false

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.85f || hsv[2] < 0.85f) {
            selected = false
            invalidate()
            return
        }
        hue = hsv[0]
        selected = true
        invalidate()
    }

    fun clearSelection() {
        selected = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shaderReady = false
    }

    override fun onDraw(canvas: Canvas) {
        placeBar()
        if (bar.width() <= 0f || bar.height() <= 0f) return
        if (!shaderReady) {
            barPaint.shader = LinearGradient(
                bar.left,
                bar.centerY(),
                bar.right,
                bar.centerY(),
                HUES,
                null,
                Shader.TileMode.CLAMP
            )
            shaderReady = true
        }
        val radius = bar.height() / 2f
        canvas.drawRoundRect(bar, radius, radius, barPaint)
        if (!selected) return
        val mark = radius - markerPaint.strokeWidth
        if (mark <= 0f) return
        val x = bar.left + (hue / 360f) * bar.width()
        markerFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(x, bar.centerY(), mark, markerFill)
        canvas.drawCircle(x, bar.centerY(), mark, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        placeBar()
        if (bar.width() <= 0f) return false
        if (event.action == MotionEvent.ACTION_DOWN &&
            (event.y < 0f || event.y > height || event.x < 0f || event.x > width)
        ) {
            return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val fraction = ((event.x - bar.left) / bar.width()).coerceIn(0f, 1f)
        hue = fraction * 360f
        if (hue >= 360f) hue = 0f
        selected = true
        invalidate()
        onColorChanged?.invoke(Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun placeBar() {
        val inset = 2f * resources.displayMetrics.density
        bar.set(inset, inset, width - inset, height - inset)
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
