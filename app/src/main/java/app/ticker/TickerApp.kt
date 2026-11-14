package app.ticker

import android.app.Application

class TickerApp : Application() {
    internal lateinit var controller: TickerController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = TickerController(this)
        controller.onProcessStart()
    }
}
