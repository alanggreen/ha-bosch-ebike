package app.ebikecompanion

import android.app.Application
import app.ebikecompanion.ble.BridgeLink

class EbikeApp : Application() {
    lateinit var link: BridgeLink
        private set

    override fun onCreate() {
        super.onCreate()
        link = BridgeLink(this)
    }
}
