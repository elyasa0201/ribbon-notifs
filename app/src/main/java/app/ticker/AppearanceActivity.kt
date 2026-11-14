package app.ticker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

class AppearanceActivity : android.app.Activity() {
    private val controller: TickerController
        get() = (application as TickerApp).controller

    private val swatches = listOf(
        R.id.bg_ribbon to R.color.strip_bg_ribbon,
        R.id.bg_black to R.color.strip_bg_black,
        R.id.bg_charcoal to R.color.strip_bg_charcoal,
        R.id.bg_navy to R.color.strip_bg_navy
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        val root = findViewById<View>(R.id.appearance_root)
        installSystemBarPadding(root)
        capContentWidth(root)
        bindTabs(AppTab.APPEARANCE)

        val textValue = findViewById<TextView>(R.id.text_size_value)
        val textSize = findViewById<SeekBar>(R.id.text_size)
        textSize.progress = controller.stripTextSizeSp() - TEXT_MIN
        textValue.text = getString(R.string.text_size_value, controller.stripTextSizeSp())
        textSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sp = progress + TEXT_MIN
                textValue.text = getString(R.string.text_size_value, sp)
                if (fromUser) controller.setStripTextSizeSp(sp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val speedValue = findViewById<TextView>(R.id.scroll_speed_value)
        val speed = findViewById<SeekBar>(R.id.scroll_speed)
        speed.progress = controller.stripScrollSpeedDp() - SPEED_MIN
        speedValue.text = getString(R.string.scroll_speed_value, controller.stripScrollSpeedDp())
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = progress + SPEED_MIN
                speedValue.text = getString(R.string.scroll_speed_value, dp)
                if (fromUser) controller.setStripScrollSpeedDp(dp)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        for ((viewId, colorId) in swatches) {
            val swatch = findViewById<View>(viewId)
            swatch.setOnClickListener {
                controller.setStripBackground(getColor(colorId))
                paintSwatches()
            }
        }
        paintSwatches()
    }

    private fun paintSwatches() {
        val density = resources.displayMetrics.density
        val current = controller.stripBackground()
        for ((viewId, colorId) in swatches) {
            val color = getColor(colorId)
            val swatch = findViewById<View>(viewId)
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(color)
                if (color == current) {
                    setStroke((2f * density).toInt().coerceAtLeast(1), getColor(R.color.ink))
                }
            }
        }
    }

    private companion object {
        const val TEXT_MIN = 10
        const val SPEED_MIN = 20
    }
}
