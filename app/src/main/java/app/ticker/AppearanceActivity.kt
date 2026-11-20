package app.ticker

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class AppearanceActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private val themeIds = intArrayOf(R.id.theme_light, R.id.theme_dark, R.id.theme_system)
    private val themeValues = intArrayOf(AppTheme.LIGHT, AppTheme.DARK, AppTheme.SYSTEM)
    private val sizeIds = intArrayOf(R.id.size_xs, R.id.size_s, R.id.size_m, R.id.size_l, R.id.size_xl)
    private val speedIds = intArrayOf(
        R.id.speed_025,
        R.id.speed_05,
        R.id.speed_1,
        R.id.speed_15,
        R.id.speed_2
    )
    private var rendering = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        val root = findViewById<View>(R.id.appearance_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPEARANCE)
        bindTheme()

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

        val textHue = findViewById<HueBarView>(R.id.text_hue)
        val textWhite = findViewById<View>(R.id.text_white)
        val bgHue = findViewById<HueBarView>(R.id.bg_hue)
        val bgDark = findViewById<View>(R.id.bg_dark)
        val none = findViewById<TextView>(R.id.bg_none)
        val opacity = findViewById<SeekBar>(R.id.bg_opacity)

        textWhite.setOnClickListener {
            controller.setStripTextColor(Color.WHITE)
            textHue.clearSelection()
            paintChip(textWhite, Color.WHITE, true)
        }
        textHue.onColorChanged = { color ->
            controller.setStripTextColor(color)
            paintChip(textWhite, Color.WHITE, false)
        }
        bgDark.setOnClickListener {
            controller.setStripBackgroundRgb(StripAppearance.DEFAULT_BG_RGB)
            bgHue.clearSelection()
            paintBackground()
        }
        bgHue.onColorChanged = { color ->
            controller.setStripBackgroundRgb(color)
            paintBackground()
        }
        none.setOnClickListener {
            controller.setBackgroundNone()
            paintBackground()
        }
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.opacity_value).text = "$progress%"
                if (!fromUser || rendering) return
                controller.setStripBackgroundAlpha(alphaFromPercent(progress))
                paintBackground()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        rendering = true
        opacity.progress = percent(controller.stripBackgroundAlpha())
        findViewById<TextView>(R.id.opacity_value).text = "${opacity.progress}%"
        rendering = false
        paintTextColor()
        paintBackground()
    }

    private fun bindTheme() {
        themeIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                val next = themeValues[index]
                if (TickerPrefs(this).appTheme == next) return@setOnClickListener
                TickerPrefs(this).appTheme = next
                recreate()
            }
        }
        paintTheme()
    }

    private fun paintTheme() {
        val selected = TickerPrefs(this).appTheme
        themeIds.forEachIndexed { index, id ->
            val view = findViewById<TextView>(id)
            val on = themeValues[index] == selected
            view.setBackgroundResource(if (on) R.drawable.bg_tab_selected else 0)
            view.setTextColor(getColor(if (on) R.color.ink else R.color.muted))
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

    private fun paintTextColor() {
        val color = controller.stripTextColor()
        val white = isWhite(color)
        findViewById<HueBarView>(R.id.text_hue).let { hue ->
            if (white) hue.clearSelection() else hue.setColor(color)
        }
        paintChip(findViewById(R.id.text_white), Color.WHITE, white)
    }

    private fun paintBackground() {
        val none = controller.backgroundNone()
        val rgb = controller.stripBackgroundRgb()
        val dark = !none && isDefaultDark(rgb)
        val hueView = findViewById<HueBarView>(R.id.bg_hue)
        if (none || dark) hueView.clearSelection() else hueView.setColor(rgb)
        paintChip(findViewById(R.id.bg_dark), StripAppearance.DEFAULT_BG_RGB, dark)
        paintNone(none)
    }

    private fun paintChip(view: View, fill: Int, selected: Boolean) {
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            val stroke = if (selected) getColor(R.color.accent) else getColor(R.color.divider)
            val width = if (selected) 2f * density else 1f * density
            setStroke(width.toInt().coerceAtLeast(1), stroke)
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

    private fun isWhite(color: Int): Boolean = (color and 0xFFFFFF) == 0xFFFFFF

    private fun isDefaultDark(color: Int): Boolean {
        return (color and 0xFFFFFF) == (StripAppearance.DEFAULT_BG_RGB and 0xFFFFFF)
    }

    private fun percent(alpha: Int): Int = ((alpha * 100f) / 255f).roundToInt().coerceIn(0, 100)

    private fun alphaFromPercent(percent: Int): Int {
        return ((percent.coerceIn(0, 100) * 255f) / 100f).roundToInt()
    }
}
