package app.ticker

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

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
    private lateinit var ribbon: StripView
    private var rendering = false
    private var ribbonOn = false

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
        findViewById<View>(R.id.style_tile).clipToOutline = true
        findViewById<View>(R.id.colour_tile).clipToOutline = true
        ribbon = findViewById(R.id.demo_ribbon)

        sizeIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                controller.setStripTextSizeSp(StripSteps.textSp[index])
                paintSteps(sizeIds, StripSteps.textSp, controller.stripTextSizeSp())
                refreshRibbon()
            }
        }
        speedIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                controller.setStripScrollSpeedDp(StripSteps.speedDp(index))
                paintSpeed()
                refreshRibbon()
            }
        }
        paintSteps(sizeIds, StripSteps.textSp, controller.stripTextSizeSp())
        paintSpeed()

        findViewById<View>(R.id.text_color_row).setOnClickListener {
            showColorWheel(
                this,
                getString(R.string.text_color_label),
                controller.stripTextColor(),
                getString(R.string.text_color_white),
                Color.WHITE
            ) { color ->
                controller.setStripTextColor(color)
                paintSwatches()
                refreshRibbon()
            }
        }
        findViewById<View>(R.id.bg_color_row).setOnClickListener {
            val current = if (controller.backgroundNone()) {
                StripAppearance.DEFAULT_BG_RGB
            } else {
                controller.stripBackgroundRgb()
            }
            showColorWheel(
                this,
                getString(R.string.background_label),
                current,
                getString(R.string.bg_default),
                StripAppearance.DEFAULT_BG_RGB
            ) { color ->
                controller.setStripBackgroundRgb(color)
                paintSwatches()
                refreshRibbon()
            }
        }
        findViewById<View>(R.id.bg_none).setOnClickListener {
            controller.setBackgroundNone()
            paintSwatches()
            refreshRibbon()
        }
        val opacity = findViewById<SeekBar>(R.id.bg_opacity)
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.opacity_value).text = "$progress%"
                if (!fromUser || rendering) return
                controller.setStripBackgroundAlpha(alphaFromPercent(progress))
                paintSwatches()
                refreshRibbon()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        rendering = true
        opacity.progress = percent(controller.stripBackgroundAlpha())
        findViewById<TextView>(R.id.opacity_value).text = "${opacity.progress}%"
        rendering = false
        paintSwatches()
    }

    override fun onStart() {
        super.onStart()
        ribbonOn = true
        refreshRibbon()
    }

    override fun onStop() {
        ribbonOn = false
        ribbon.stopDemo()
        super.onStop()
    }

    private fun refreshRibbon() {
        if (!ribbonOn) return
        ribbon.playDemo(controller.stripAppearance(), getString(R.string.preview_line))
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

    private fun paintSwatches() {
        paintSwatch(findViewById(R.id.text_swatch), controller.stripTextColor(), false)
        val none = controller.backgroundNone()
        paintSwatch(findViewById(R.id.bg_swatch), controller.stripBackgroundRgb(), none)
        paintNone(none)
    }

    private fun paintSwatch(view: View, fill: Int, empty: Boolean) {
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (empty) Color.TRANSPARENT else fill)
            setStroke((1f * density).toInt().coerceAtLeast(1), getColor(R.color.divider))
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

    private fun percent(alpha: Int): Int = ((alpha * 100f) / 255f).roundToInt().coerceIn(0, 100)

    private fun alphaFromPercent(percent: Int): Int {
        return ((percent.coerceIn(0, 100) * 255f) / 100f).roundToInt()
    }
}
