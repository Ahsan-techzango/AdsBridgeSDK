package com.example.adssdksample

import android.app.Application
import io.github.adsbridge.sdk.AdsLibrary

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsLibrary.initialize(
            context = this,
            isPremium = false,
            appId = DemoAds.APP_ID
        )
    }
}

/** Official Google demo IDs. Never use demo IDs for a production app. */
internal object DemoAds {
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
}
