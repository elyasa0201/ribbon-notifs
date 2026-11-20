package app.ticker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View

class RuleExplanationActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule)
        val root = findViewById<View>(R.id.rule_scroll)
        installSystemBarPadding(root)
        capContentWidth(root)
    }
}
