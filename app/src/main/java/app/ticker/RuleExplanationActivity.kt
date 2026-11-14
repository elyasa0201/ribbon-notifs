package app.ticker

import android.app.Activity
import android.os.Bundle
import android.view.View

class RuleExplanationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule)
        val root = findViewById<View>(R.id.rule_scroll)
        installSystemBarPadding(root)
        capContentWidth(root)
    }
}
