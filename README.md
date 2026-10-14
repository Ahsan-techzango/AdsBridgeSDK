# AdsBridge SDK

Reusable Android advertising library based on **Google Mobile Ads Next-Gen SDK**, written in Kotlin.

> **Repository assumed for the examples:** [`Ahsan-techzango/AdsBridgeSDK`](https://github.com/Ahsan-techzango/AdsBridgeSDK). The GitHub username is confirmed; change `AdsBridgeSDK` everywhere if you choose a different repository name. **This source is not yet verified as a successful Android build or a published Maven artifact.** Do not release version `1.0.0` before running the build and device tests below.

## Features

- Interstitial ads: preload, load-and-show, caching, global same-format fallback, delays and cooldowns.
- Rewarded ads: preload, load-and-show, cache reuse and reward callbacks.
- Native ads: three **built-in** layouts, observable preload with `LiveData<NativeState>`, global native cache and per-container cleanup.
- Next-Gen anchored adaptive banners and optional collapsible banners.
- App-open ads: preload, show and load-and-show with a four-hour cached-ad limit.
- Native/banner skeleton shimmer during load; native shimmer matches the selected native style.
- Optional native background, **Ad badge**, CTA background, headline/body text and CTA text colors. When omitted, built-in XML colors remain in effect.
- Host-controlled premium status, network gating, logs, main-thread callbacks and Activity validity checks.
- Progress events for fullscreen ads. **Preload never displays a progress dialog.**
- Sample Android application in `:sample-app`.

## Requirements

| Setting | Value in this source project |
|---|---|
| Kotlin | Kotlin via AGP's built-in Kotlin support |
| Gradle | 9.4.1 |
| Android Gradle Plugin | 9.2.0 |
| Java / JDK | 17 |
| `compileSdk` | 36 |
| `minSdk` | 24 |
| Google Mobile Ads Next-Gen | `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.5.0` |
| Native state | `androidx.lifecycle:lifecycle-livedata-core:2.9.4` |

AndroidX, the Next-Gen Ads SDK and resource-related libraries are declared in `ads-library/build.gradle.kts`. Use `google()` and `mavenCentral()` for their transitive dependencies. The SDK does **not** implement purchases, Firebase Analytics or Firebase Remote Config.

## Project structure

```text
AdsBridgeSDK/
â”œâ”€â”€ ads-library/                  # Publishable Android AAR
â”‚   â”œâ”€â”€ src/main/java/io/github/adsbridge/sdk/
â”‚   â”‚   â”œâ”€â”€ AdsLibrary.kt         # Public API
â”‚   â”‚   â”œâ”€â”€ FullscreenAds.kt      # Interstitial and rewarded
â”‚   â”‚   â”œâ”€â”€ NativeAds.kt          # Native cache / LiveData / binding
â”‚   â”‚   â”œâ”€â”€ BannerAds.kt          # Next-Gen banner
â”‚   â”‚   â”œâ”€â”€ AppOpenAds.kt         # App-open
â”‚   â”‚   â”œâ”€â”€ AdLoadingEvents.kt    # Progress events
â”‚   â”‚   â”œâ”€â”€ AdLogger.kt           # Diagnostic events
â”‚   â”‚   â”œâ”€â”€ AdShimmer.kt          # Banner/native skeletons
â”‚   â”‚   â””â”€â”€ Infrastructure.kt     # Premium, UI and network helpers
â”‚   â””â”€â”€ src/main/res/layout/      # 3 built-in native layouts
â”œâ”€â”€ sample-app/                   # Demo (not published as SDK)
â”œâ”€â”€ .github/workflows/publish.yml
â””â”€â”€ README.md
```

## 1. Install the dependency

### Option A: Use the local SDK module during development

In the project root `settings.gradle.kts`:



In your host `app/build.gradle.kts`:
```kotlin
dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url 'https://jitpack.io' }
		}
	}
```kotlin
dependencies {
   implementation 'com.github.Ahsan-techzango:AdsBridgeSDK:1.0.2'
}
```

### Option B: Install from GitHub Packages (configured in this source)

**First publish a successful release.** An AAR committed to GitHub is not automatically a Gradle dependency.


## 2. Add the AdMob App ID

In the **host application's** `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application ...>
        <meta-data
            android:name="com.google.android.gms.ads.APPLICATION_ID"
            android:value="ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy" />
    </application>
</manifest>
```

The library manifest supplies `INTERNET` and `ACCESS_NETWORK_STATE`; verify the merged manifest. Use an **App ID** (`~`) for initialization and **ad unit IDs** (`/`) when loading. Configure consent in the host before requesting ads where required. Use Google's test ad units during development; never use production ads for repeated testing.

## 3. Initialize in your Application class

```kotlin
import android.app.Application
import io.github.adsbridge.sdk.AdsLibrary

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AdsLibrary.configureLogging(enabled = BuildConfig.DEBUG)
        AdsLibrary.initialize(
            context = applicationContext,
            isPremium = false, // replace with actual host billing state
            appId = "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"
        ) {
            // SDK init callback, on main thread.
            // Begin preloading only once your host's consent/config is ready.
        }
    }
}
```

Register `MyApplication` via `android:name` in the application manifest.

### Premium status and feature control

```kotlin
AdsLibrary.setPremiumUser(true)    // after a purchase or restore
AdsLibrary.setPremiumUser(false)   // on expiry / account change
AdsLibrary.setAdsEnabled(true)     // global feature flag
AdsLibrary.setCooldownMillis(30_000L)
```

The library does not read your billing state. After making a user premium, also call `AdsLibrary.removeAd(container)` for your visible banners/native containers when necessary, and ensure the host no longer initiates ad requests.

### Internet check

```kotlin
if (!AdsLibrary.isNetworkAvailable(this)) {
    // Your app decides whether to show an offline message or retry.
}
```

This tests validated network capability, **not** guaranteed access to AdMob servers.

## 4. Interstitial Ads

Preload without opening a progress dialog:

```kotlin
AdsLibrary.preloadInterstitial(
    context = applicationContext,
    adUnitId = INTERSTITIAL_ID,
    placement = "language"
) { ready ->
    // Main-thread preload result
}
```

Load and show from an Activity:

```kotlin
AdsLibrary.loadAndShowInterstitial(
    activity = this,
    adUnitId = INTERSTITIAL_ID,
    placement = "onboarding",
    useAnyCachedFallback = true,
    showDelayMs = 0L
) { loaded, failed, showed, dismissed ->
    when {
        dismissed -> continueNavigationOnce()
        failed -> continueNavigationOnce()
        showed -> Unit
        loaded -> Unit // Do NOT navigate on loaded
    }
}
```

If `language` had preloaded an interstitial that was never shown, a subsequent `onboarding` load-and-show may consume that cached **interstitial** first. This can also join an in-flight same-format load. Cache reuse never changes ad format. The host should only navigate once on a terminal event.

To show any already-cached interstitial:

```kotlin
AdsLibrary.showAnyCachedInterstitial(this) { showed, dismissed, failed ->
    if (dismissed || failed) continueNavigationOnce()
}
```

## 5. Rewarded Ads

```kotlin
AdsLibrary.preloadRewarded(applicationContext, REWARDED_ID) { ready ->
    // No progress event for preload
}

AdsLibrary.loadAndShowRewarded(
    activity = this,
    adUnitId = REWARDED_ID
) { loaded, failed, showed, dismissed, rewarded ->
    if (rewarded) grantRewardOnce()   // reward != dismissal
    if (dismissed || failed) finishRewardFlowOnce()
}
```

The rewarded ad may use a cached ad from another rewarded ad unit. Grant rewards **only** upon the reward callback, never just because an ad was dismissed.

## 6. Native Ads: 3 built-in styles

| Style argument | Included native XML | Shimmer skeleton |
|---|---|---|
| `1` | `native_ad_small` | Compact thumbnail / text / CTA |
| `2` | `native_ad_advance` | Media / text / CTA |
| `3` | `native_ad_large_advance` | Large media / text / CTA |

### Load and show with XML's default colors

```kotlin
AdsLibrary.loadAndShowNativeAd(
    activity = this,
    adUnitId = NATIVE_ID,
    container = binding.nativeContainer, // FrameLayout
    style = 2,
    onResult = { shown ->
        // Main thread
    }
)
```

### Override colors selectively

```kotlin
import android.graphics.Color
import io.github.adsbridge.sdk.NativeColors

AdsLibrary.loadAndShowNativeAd(
    activity = this,
    adUnitId = NATIVE_ID,
    container = binding.nativeContainer,
    style = 1,
    colors = NativeColors(
        backgroundColor = Color.WHITE,
        adTextBackgroundColor = Color.YELLOW, // 'Ad' badge, NOT headline
        ctaColor = Color.rgb(36, 100, 210),
        textColor = Color.BLACK,
        ctaTextColor = Color.WHITE
    ),
    onResult = { shown -> /* UI callback */ }
)
```

Any `NativeColors` property left `null` retains the XML style. Custom native XML from the host app is **not** supported. CTA recoloring should tint the existing rounded drawable rather than call `setBackgroundColor()` (which can flatten the corners). If your local `NativeAds.kt` lacks that tint fix, apply it before publishing.

### Preload and observe `LiveData`

```kotlin
import io.github.adsbridge.sdk.NativeState

val placement = "home_native"

AdsLibrary.nativeState(placement).observe(this) { state ->
    when (state) {
        NativeState.Ready -> {
            AdsLibrary.showNative(
                activity = this,
                container = binding.nativeContainer,
                placement = placement,
                style = 3
            ) { shown ->
                // Result on main thread
            }
        }
        is NativeState.Failed -> {
            // Optional: status.text = state.reason
        }
        else -> Unit
    }
}

AdsLibrary.preloadNativeAd(
    context = applicationContext,
    adUnitId = NATIVE_ID,
    placement = placement
)
```

A cached native ad is consumed when displayed. Don't register one native ad to multiple containers or list items. An offline preload without a container does **not** show shimmer.

## 7. Banner ads (Next-Gen)

Add a `FrameLayout` for your banner in the Activity layout:

```xml
<FrameLayout
    android:id="@+id/bannerContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
import io.github.adsbridge.sdk.ShimmerOptions

AdsLibrary.loadBanner(
    activity = this,
    container = binding.bannerContainer,
    adUnitId = BANNER_ID,
    collapsible = false,
    top = false,
    onResult = { loaded ->
        // Actual banner load result, main thread
    },
    shimmer = ShimmerOptions(enabled = true, heightDp = 60)
)
```

The library creates a Next-Gen `AdView`, requests an adaptive banner and cleans up the `AdView` when removed. The banner shimmer appears during load and disappears when loading finishes. To request a collapsible banner set `collapsible = true`; rendering depends on Google ad availability.

## 8. App Open Ads

Preload without displaying a progress dialog:

```kotlin
AdsLibrary.loadAppOpenAd(applicationContext, APP_OPEN_ID) { ready ->
    // Ready for a later show
}
```

Show a cached app-open ad:

```kotlin
AdsLibrary.showAppOpenAd(this) { showed, dismissed, failed ->
    if (dismissed || failed) continueSplashOnce()
}
```

Or load if needed and show:

```kotlin
AdsLibrary.loadAndShowAppOpenAd(
    activity = this,
    adUnitId = APP_OPEN_ID
) { showed, dismissed, failed ->
    if (dismissed || failed) continueSplashOnce()
}
```

App-open cache lifetime in the current SDK is four hours. The host chooses appropriate foreground/splash timing. **Using the latest shared `AppOpenAds.kt` variant**, the fullscreen progress event starts even when the ad is cached and remains active until the ad dismisses or fails. The progress dialog should remain *behind* the ad's fullscreen surface, not above the ad content.

## 9. Progress dialog integration (preload never triggers it)

The SDK does not draw your custom dialog. Use the SDK's loading events and your own existing dialogs:

```kotlin
AdsLibrary.setAdLoadingListener { event ->
    when (event.format) {
        "interstitial", "rewarded" -> {
            if (event.isLoading) showSmallProgressDialog()
            else dismissSmallProgressDialog()
        }
        "app_open" -> {
            if (event.isLoading) showFullScreenProgressDialog()
            else dismissFullScreenProgressDialog()
        }
    }
}
```

| Operation | Progress listener |
|---|---|
| `preloadInterstitial`, `preloadRewarded`, `loadAppOpenAd` | No event |
| Interstitial / rewarded `loadAndShow` with cache miss | Small dialog while waiting for load |
| Interstitial / rewarded cached show | No load-wait dialog |
| App-open `showAppOpenAd` or `loadAndShowAppOpenAd` | Fullscreen dialog until ad dismissed / fails **with the latest `AppOpenAds.kt` implementation** |

`AdLoadingEvent` has `format`, `placement`, `isLoading`. Notifications are dispatched to the main thread. This listener is a **single global callback**: register it for the current host screen, avoid retaining a destroyed Activity, unregister as appropriate, and explicitly dismiss any open dialogs in `onDestroy`. If you unregister in `onStop`, app-open dismissal while your Activity is stopped may not reach that Activity's listener; design the app-open dialog ownership accordingly.

## 10. Diagnostic ads log listener

```kotlin
AdsLibrary.configureLogging(enabled = BuildConfig.DEBUG)
AdsLibrary.setAdLogListener { event ->
    android.util.Log.d(
        "MyAds",
        "${event.format}: ${event.action} [${event.placement}] ${event.detail}"
    )
}
```

Use `AdsLibrary` as your Logcat filter. `AdLogEvent` exposes `format`, `action`, `placement`, `detail` and `timestampMs`. Loading, success, failure, show, dismiss, reward, cache hit and skipped events are logged where supported. Logging is opt-in, and it is **not** a substitute for ad revenue analytics.

```kotlin
AdsLibrary.setAdLogListener(null)
AdsLibrary.configureLogging(false)
```

## 11. Cleanup and lifecycle

```kotlin
override fun onDestroy() {
    AdsLibrary.removeAd(binding.nativeContainer)
    AdsLibrary.removeAd(binding.bannerContainer)
    super.onDestroy()
}
```

Clear per-placement cache with `AdsLibrary.clearPlacement("home")` or all cached SDK state with `AdsLibrary.clearAll()`. The SDK checks Activity validity before showing fullscreen ads, but your callbacks must still avoid touching destroyed views. Keep navigation and rewards idempotent.

## 12. Publish to GitHub Packages

1. Create the GitHub repository **`https://github.com/Ahsan-techzango/AdsBridgeSDK`** (or substitute your real repository name).
2. Keep `ads-library` and `sample-app` as modules; publish **only** `ads-library`.
3. In `ads-library/build.gradle.kts`, replace old `OWNER/ads-library` in POM metadata and the default package URL with `Ahsan-techzango/AdsBridgeSDK`. The `GITHUB_REPOSITORY` environment variable supplied by GitHub Actions also selects the publishing URL.
4. Decide on a permanent Maven `group` before the first release. The existing GitHub Actions workflow uses `io.github.Ahsan-techzango.ads`, while the checked-in `gradle.properties` still has an example group; align both.
5. Verify the module locally or in CI:

```bash
./gradlew :ads-library:clean :ads-library:assembleRelease
./gradlew :sample-app:assembleDebug
./gradlew :ads-library:publishReleasePublicationToMavenLocal
```

On Windows replace `./gradlew` with `gradlew.bat`. A local bootstrap launcher is not necessarily an official Gradle wrapper: generate and commit a **real** wrapper (`gradle wrapper --gradle-version 9.4.1`) and `gradle/wrapper/gradle-wrapper.jar` before relying on CI.

6. Commit and push:

```bash
git init
git add .
git commit -m "Initial AdsBridge SDK"
git branch -M main
git remote add origin https://github.com/Ahsan-techzango/AdsBridgeSDK.git
git push -u origin main
```

7. **After tests pass**, release `1.0.0`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The included `.github/workflows/publish.yml` listens for `v*` tags and publishes the release Maven publication to GitHub Packages using `secrets.GITHUB_TOKEN`. Check the GitHub Actions logs for success and verify the published package exists in the repository's packages. Each new version needs a new tag (`v1.0.1`, `v1.1.0`, etc.).

## 13. Troubleshooting

| Symptom | Check |
|---|---|
| `Could not find ads-library` | Release published? Correct owner/repo, version, group, artifact, GitHub token permissions? |
| `401/403` resolving GitHub Packages | Developer PAT `read:packages`, package visibility/repo access and credentials in `~/.gradle/gradle.properties` |
| `Unresolved reference: AdsLibrary` | Correct dependency coordinates and import `io.github.adsbridge.sdk.AdsLibrary` |
| `App ID missing` | Host manifest `com.google.android.gms.ads.APPLICATION_ID`, `initialize(appId = ...)` consistency |
| Native ready but not displayed | LiveData lifecycle, correct placement, valid container, only consume cached ad once |
| Native CTA corners disappear | Tint the XML background drawable rather than replacing it via `setBackgroundColor()` |
| Small/large progress not displayed | Register `setAdLoadingListener` on the owning Activity; preloads intentionally do not emit progress |
| App-open progress closes too early | Confirm **the latest** `AppOpenAds.kt` variant that transfers the loading token into the actual show and stops on dismissal |
| Gradle sync fails | JDK 17, AGP/Gradle compatibility, Android SDK 36 installed, no redundant Kotlin Android plugin with AGP 9 |
| Ads never load | Check test ad IDs, consent, network, premium status, AdMob initialization and Logcat |

## 14. Migration from app-specific ads code

Replace original `Ads.kt`, `NativeAdManager.kt`, `NativeListAdManager.kt`, `AdBanner.kt` and `AppOpenAdManager.kt` call sites with `AdsLibrary` calls. Supply ad unit IDs from the host, initialize in `Application`, update premium via `setPremiumUser`, move subscription/Firebase/Remote Config/dialog ownership into the host app, use `NativeState` for preload observation, and replace app-level ad view cleanup with `removeAd`.

## Validation / release notice

The supplied project has gone through multiple source patches. Before release, ensure your local working tree includes **the final latest versions** of `FullscreenAds.kt`, `AppOpenAds.kt`, `NativeAds.kt`, `BannerAds.kt`, `AdLoadingEvents.kt`, `AdsLibrary.kt`, and the sample app. The provided code has **not been compiled or tested on a device here**, and this README does not claim successful GitHub publishing. Test premium activation mid-load, cancellation, multi-Activity handoff, duplicate navigation prevention, rewarded callback ordering, ad cleanup, native XML styling, app-open dialog lifetime, and R8 release builds. Only tag `v1.0.0` after validation.
