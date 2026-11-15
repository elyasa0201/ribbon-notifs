package app.ticker

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast

class AppearanceActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private val sizeIds = intArrayOf(R.id.size_xs, R.id.size_s, R.id.size_m, R.id.size_l, R.id.size_xl)
    private val speedIds = intArrayOf(
        R.id.speed_025,
        R.id.speed_05,
        R.id.speed_1,
        R.id.speed_15,
        R.id.speed_2
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        val root = findViewById<View>(R.id.appearance_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPEARANCE)

        findViewById<View>(R.id.preview_strip).setOnClickListener {
            if (!controller.previewStrip()) {
                Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
            }
        }

        sizeIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                controller.setStripTextSizeSp(StripSteps.textSp[index])
                paintSteps(sizeIds, StripSteps.textSp, controller.stripTextSizeSp())
            }
        }
        speedIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                controller.setStripScrollSpeedDp(StripSteps.speedDp(index))
                paintSpeed()
            }
        }
        paintSteps(sizeIds, StripSteps.textSp, controller.stripTextSizeSp())
        paintSpeed()

        val wheel = findViewById<ColorWheelView>(R.id.color_wheel)
        val none = findViewById<TextView>(R.id.bg_none)
        wheel.onColorChanged = { color ->
            controller.setStripBackground(color)
            paintNone(false)
        }
        none.setOnClickListener {
            controller.setStripBackground(StripAppearance.NO_BACKGROUND)
            wheel.clearSelection()
            paintNone(true)
        }
        val current = controller.stripBackground()
        if (Color.alpha(current) == 0) {
            wheel.clearSelection()
            paintNone(true)
        } else {
            wheel.setColor(current)
            paintNone(false)
        }
    }

    private fun paintSteps(ids: IntArray, values: IntArray, selected: Int) {
        val index = values.indexOf(selected).let { if (it < 0) values.size / 2 else it }
        ids.forEachIndexed { item, id ->
            val view = findViewById<TextView>(id)
            val on = item == index
            view.setBackgroundResource(if (on) R.drawable.bg_tab_selected else 0)
            view.setTextColor(getColor(if (on) R.color.ink else R.color.muted))
        }
    }

    private fun paintSpeed() {
        val selected = StripSteps.nearestSpeedIndex(controller.stripScrollSpeedDp())
        speedIds.forEachIndexed { item, id ->
            val view = findViewById<TextView>(id)
            val on = item == selected
            view.setBackgroundResource(if (on) R.drawable.bg_tab_selected else 0)
            view.setTextColor(getColor(if (on) R.color.ink else R.color.muted))
        }
    }

    private fun paintNone(selected: Boolean) {
        val none = findViewById<TextView>(R.id.bg_none)
        val density = resources.displayMetrics.density
        none.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(getColor(if (selected) R.color.tab_pill else R.color.card))
            if (selected) {
                setStroke((2f * density).toInt().coerceAtLeast(1), getColor(R.color.accent))
            }
        }
        none.setTextColor(getColor(if (selected) R.color.ink else R.color.muted))
    }
}
