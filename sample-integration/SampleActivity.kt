package example.host

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.github.adsbridge.sdk.AdsLibrary
import io.github.adsbridge.sdk.NativeColors
import io.github.adsbridge.sdk.NativeState

// Adapt to AppCompatActivity/ComponentActivity with LifecycleOwner in host.
class SampleActivity : androidx.activity.ComponentActivity() {
    private lateinit var nativeContainer: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeContainer = FrameLayout(this)
        setContentView(nativeContainer)
        val placement = "home_native"
        AdsLibrary.nativeState(placement).observe(this) { state ->
            when (state) {
                NativeState.Ready -> AdsLibrary.showNative(this, nativeContainer, placement, 2,
                    NativeColors(backgroundColor = Color.WHITE,
                        adTextBackgroundColor = Color.LTGRAY, ctaColor = Color.BLUE))
                is NativeState.Failed -> android.util.Log.w("Host",state.reason)
                else -> Unit
            }
        }
        AdsLibrary.preloadNativeAd(applicationContext,"YOUR_NATIVE_ID",placement)
    }
    override fun onDestroy() {
        AdsLibrary.removeAd(nativeContainer)
        super.onDestroy()
    }
}
