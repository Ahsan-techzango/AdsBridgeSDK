package com.example.adssdksample

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.adsbridge.sdk.AdsLibrary
import io.github.adsbridge.sdk.NativeColors
import io.github.adsbridge.sdk.ShimmerOptions
import io.github.adsbridge.sdk.NativeState

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var nativeContainer: FrameLayout
    private lateinit var bannerContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
        }
        nativeContainer = FrameLayout(this).apply { minimumHeight = 140.dp }
        bannerContainer = FrameLayout(this).apply { minimumHeight = 60.dp }
        AdsLibrary.configureLogging(enabled = true)
        AdsLibrary.setAdLogListener { event ->
            android.util.Log.d("abcd", "${event.format}: ${event.action} [${event.placement}] ${event.detail}")
        }
        AdsLibrary.setAdLoadingListener { event ->
            when (event.format) {

                "interstitial", "rewarded" -> {
                    if (event.isLoading) {
                        showSmallProgressDialog()
                    } else {
                        dismissSmallProgressDialog()
                    }
                }

                "app_open" -> {
                    if (event.isLoading) {
                        showFullScreenProgressDialog()
                    } else {
                        dismissFullScreenProgressDialog()
                    }
                }
            }
        }
        status = TextView(this).apply {
            text = "Ready • using Google demo ad unit IDs"
            textSize = 15f
            setPadding(0, 0, 0, 14.dp)
        }
        root.addView(TextView(this).apply { text = "Reusable Ads SDK Demo"; textSize = 24f })
        root.addView(status)

        root.addView(Switch(this).apply {
            text = "Premium user (disable ads)"
            isChecked = AdsLibrary.isPremiumUser()
            setOnCheckedChangeListener { _, checked ->
                AdsLibrary.setPremiumUser(checked)
                status.text = if (checked) "Premium enabled • ad requests blocked" else "Premium disabled"
                if (checked) {
                    // Explicitly detach views owned by this Activity.
                    AdsLibrary.removeAd(nativeContainer)
                    AdsLibrary.removeAd(bannerContainer)
                }
            }
        })
        addButton(root, "Check internet") {
            status.text = "Validated network: ${AdsLibrary.isNetworkAvailable(this)}"
        }
        addButton(root, "Preload interstitial") {
            AdsLibrary.preloadInterstitial(applicationContext, DemoAds.INTERSTITIAL, "home") { ok ->
                status.text = "Interstitial preloaded: $ok"
            }
        }
        addButton(root, "Load and show interstitial") {
            AdsLibrary.loadAndShowInterstitial(this, DemoAds.INTERSTITIAL, "home") { loaded, failed, showed, dismissed ->
                status.text = "Interstitial: loaded=$loaded failed=$failed showed=$showed dismissed=$dismissed"
            }
        }
        addButton(root, "Load and show rewarded") {
            AdsLibrary.loadAndShowRewarded(this, DemoAds.REWARDED) { loaded, failed, showed, dismissed, rewarded ->
                status.text = "Rewarded: loaded=$loaded failed=$failed showed=$showed dismissed=$dismissed earned=$rewarded"
            }
        }
        root.addView(nativeContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addButton(root, "Preload native (LiveData)") {
            AdsLibrary.preloadNativeAd(applicationContext, DemoAds.NATIVE, "home_native")
        }
        addButton(root, "Show preloaded native") {
            AdsLibrary.showNative(
                activity = this, container = nativeContainer, placement = "home_native", style = 2,
                colors = NativeColors(
                    backgroundColor = Color.WHITE,
                    adTextBackgroundColor = Color.rgb(232, 239, 247),
                    ctaColor = Color.rgb(36, 100, 210)
                )
            ) { ok -> status.text = "Native shown: $ok" }
        }
        addButton(root, "Show native with XML default colors") {
            AdsLibrary.loadAndShowNativeAd(
                activity = this, adUnitId = DemoAds.NATIVE, container = nativeContainer,
                style = 2,
                onResult = { ok -> status.text = "Default XML native shown: $ok" }
            )
        }
        addButton(root, "Load and show native directly") {
            AdsLibrary.loadAndShowNativeAd(
                activity = this, adUnitId = DemoAds.NATIVE, container = nativeContainer,
                style = 1,
                colors = NativeColors(
                    backgroundColor = Color.parseColor("#e0e0e0"),
                    adTextBackgroundColor = Color.LTGRAY,
                    textColor = Color.BLACK,
                    ctaTextColor = Color.WHITE,
                    ctaColor = Color.rgb(36, 100, 210)
                ),
                onResult = { ok -> status.text = "Native loaded and shown: $ok" }
            )
        }
        root.addView(bannerContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addButton(root, "Load banner") {
            AdsLibrary.loadBanner(
                activity = this,
                container = bannerContainer,
                collapsible = true,
                adUnitId = DemoAds.BANNER,
                onResult = { ok -> status.text = "Banner loaded: $ok" },
                shimmer = ShimmerOptions(heightDp = 80)
            )
        }
        addButton(root, "Preload app-open") {
            AdsLibrary.loadAppOpenAd(applicationContext, DemoAds.APP_OPEN) { ok ->
                status.text = "App-open preloaded: $ok"
            }
        }
        addButton(root, "Show app-open") {
            AdsLibrary.showAppOpenAd(this) { showed, dismissed, failed ->
                status.text = "App-open showed=$showed dismissed=$dismissed failed=$failed"
            }
        }
        addButton(root, "Show app-open load and show") {
            AdsLibrary.loadAndShowAppOpenAd(
                activity = this,
                adUnitId = DemoAds.APP_OPEN
            ) { showed, dismissed, failed ->
                status.text = "App-open showed=$showed dismissed=$dismissed failed=$failed"

            }
        }
        addButton(root, "Clear cached ads and views") {
            AdsLibrary.clearAll()
            AdsLibrary.removeAd(nativeContainer)
            AdsLibrary.removeAd(bannerContainer)
            status.text = "Ads cleared"
        }
        AdsLibrary.nativeState("home_native").observe(this) { state ->
            status.text = when (state) {
                NativeState.Ready -> "Native LiveData: Ready • tap Show preloaded native"
                is NativeState.Failed -> "Native LiveData: ${state.reason}"
                else -> "Native LiveData: $state"
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private var smallDialog: AlertDialog? = null
    private var fullScreenDialog: Dialog? = null

    private fun showSmallProgressDialog() {
        if (smallDialog?.isShowing == true || isFinishing || isDestroyed) return

        smallDialog = AlertDialog.Builder(this)
            .setView(ProgressBar(this))
            .setCancelable(false)
            .create()

        smallDialog?.show()
        smallDialog?.window?.setLayout(180.dp, 140.dp)
    }

    private fun dismissSmallProgressDialog() {
        smallDialog?.dismiss()
        smallDialog = null
    }

    private fun showFullScreenProgressDialog() {
        if (fullScreenDialog?.isShowing == true || isFinishing || isDestroyed) return

        fullScreenDialog = Dialog(this).apply {
            setContentView(
                FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(
                        ProgressBar(this@MainActivity),
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )
                }
            )

            setCancelable(false)
            window?.setBackgroundDrawableResource(
                android.R.color.transparent
            )
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun dismissFullScreenProgressDialog() {
        fullScreenDialog?.dismiss()
        fullScreenDialog = null
    }


    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        AdsLibrary.setAdLogListener(null)
        AdsLibrary.removeAd(nativeContainer)
        AdsLibrary.removeAd(bannerContainer)
        super.onDestroy()
    }
}
