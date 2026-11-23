package app.ticker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Hue around the circle, saturation out from the centre. Brightness is a
 * separate slider so a dark ribbon background can still be chosen.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val markerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.BLACK
    }
    private var bitmap: Bitmap? = null
    private var bitmapValue = -1f
    private var hue = 0f
    private var sat = 1f
    private var value = 1f

    fun color(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        bitmapValue = -1f
        invalidate()
    }

    fun setValue(next: Float) {
        value = next.coerceIn(0f, 1f)
        invalidate()
        onColorChanged?.invoke(color())
    }

    fun value(): Float = value

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height)
        if (size <= 0) return
        val disc = ensureBitmap(size)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        canvas.drawBitmap(disc, left, top, null)
        val radius = size / 2f
        val cx = left + radius
        val cy = top + radius
        val dist = sat.coerceIn(0f, 1f) * (radius - marker.strokeWidth)
        val rad = Math.toRadians(hue.toDouble())
        val mx = cx + (dist * cos(rad)).toFloat()
        val my = cy + (dist * sin(rad)).toFloat()
        val mark = 8f * resources.displayMetrics.density
        canvas.drawCircle(mx, my, mark, marker)
        canvas.drawCircle(mx, my, mark, markerEdge)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val size = min(width, height).toFloat()
        if (size <= 0f) return false
        val radius = size / 2f
        val cx = (width - size) / 2f + radius
        val cy = (height - size) / 2f + radius
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = hypot(dx, dy).coerceAtMost(radius)
        sat = (dist / radius).coerceIn(0f, 1f)
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        hue = angle
        invalidate()
        onColorChanged?.invoke(color())
        return true
    }

    private fun ensureBitmap(size: Int): Bitmap {
        val current = bitmap
        if (current != null && current.width == size && bitmapValue == value) return current
        current?.recycle()
        val created = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val radius = size / 2f
        val pixels = IntArray(size * size)
        var index = 0
        for (y in 0 until size) {
            val dy = y + 0.5f - radius
            for (x in 0 until size) {
                val dx = x + 0.5f - radius
                val dist = hypot(dx, dy)
                if (dist > radius) {
                    pixels[index] = Color.TRANSPARENT
                } else {
                    val saturation = (dist / radius).coerceIn(0f, 1f)
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (angle < 0f) angle += 360f
                    pixels[index] = Color.HSVToColor(floatArrayOf(angle, saturation, value))
                }
                index += 1
            }
        }
        created.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap = created
        bitmapValue = value
        return created
    }
}

internal fun showColorWheel(
    context: android.app.Activity,
    title: String,
    color: Int,
    extraLabel: String?,
    extraColor: Int?,
    onPick: (Int) -> Unit
) {
    val density = context.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val wheel = ColorWheelView(context)
    val wheelSize = (260 * density).toInt()
    wheel.layoutParams = LinearLayout.LayoutParams(wheelSize, wheelSize).apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
    }
    val brightnessLabel = TextView(context).apply {
        text = context.getString(R.string.brightness_label)
        setTextColor(context.getColor(R.color.ink))
        textSize = 14f
        setPadding(0, pad, 0, 0)
    }
    val brightness = SeekBar(context).apply {
        max = 100
    }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        addView(wheel)
        addView(brightnessLabel)
        addView(brightness)
    }
    if (extraLabel != null && extraColor != null) {
        val extra = TextView(context).apply {
            text = extraLabel
            setTextColor(context.getColor(R.color.accent))
            textSize = 16f
            setPadding(0, pad, 0, 0)
            setOnClickListener {
                wheel.setColor(extraColor)
                brightness.progress = (wheel.value() * 100f).toInt().coerceIn(0, 100)
                onPick(extraColor)
            }
        }
        column.addView(extra)
    }
    wheel.setColor(color)
    brightness.progress = (wheel.value() * 100f).toInt().coerceIn(0, 100)
    wheel.onColorChanged = { picked ->
        onPick(picked)
    }
    brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            wheel.setValue(progress / 100f)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    })
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(column)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
