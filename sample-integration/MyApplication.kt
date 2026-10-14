package example.host

import android.app.Application
import io.github.adsbridge.sdk.AdsLibrary

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsLibrary.initialize(this, isPremium = false, appId = "YOUR_ADMOB_APP_ID") {
            // Start ad requests only after initialization.
        }
    }
    fun onPremiumChanged(premium: Boolean) = AdsLibrary.setPremiumUser(premium)
}
