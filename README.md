# Reusable Android Ads SDK (source migration, not yet verified release)

A Kotlin Android library extracted and refactored from the supplied `Ads.kt`, `AdBanner.kt`, `NativeAdManager.kt`, `NativeListAdManager.kt`, `AppOpenAdManager.kt` plus three native-ad XML designs. This is **development source**: Android SDK/Gradle were not available in the build environment. Do not tag `v1.0.0` or distribute as production-ready before compilation and device tests.

## Toolchain

- `compileSdk = 36`, `minSdk = 24`, Java 17; Android Gradle Plugin 8.13.2, Kotlin 2.2.21 (verify with your host project).
- Next-Gen Google Mobile Ads `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.5.0` (the currently documented release, newer than requested 1.0.5).
- `androidx.lifecycle:lifecycle-livedata-core:2.9.4` for native state; ConstraintLayout and AppCompat for migrated layouts. No Firebase or billing dependency.
- `google()` and `mavenCentral()` must both be configured in settings.

## Overview

`AdsLibrary` is the host-facing facade. `FullscreenAds`, `AppOpenAds`, `BannerAds`, and `NativeAds` manage individual formats. `Policy` implements central premium and feature gating; `Ui` dispatches callbacks using the main Looper. Native ad payloads never leave the manager; `LiveData<NativeState>` exposes `Idle`, `Loading`, `Ready`, `Failed(reason)`, `Disabled` safely. Activity's observer consumes the cached ad via `showNative`.

## Host AppManifest

```xml
<application>
    <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"/>
</application>
```

The library supplies `INTERNET` and `ACCESS_NETWORK_STATE` via manifest merger. Use a genuine AdMob app ID matching `initialize()`. Provide consent/user messaging and ad unit IDs in the host. No automatic Firebase or billing initialization. Check the merged manifest for mediation SDK requirements. No consumer R8 rules are currently included; verify minified builds.

## Initialize after consent/config setup

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsLibrary.initialize(this, isPremium = premiumFromHost(), appId = "YOUR_ADMOB_APP_ID") {
            // Ready to load ads; runs on UI thread.
        }
    }
    private fun premiumFromHost(): Boolean = false // integrate your real billing status
}
```

Call `AdsLibrary.setPremiumUser(true)` after purchase/restore; call `false` after expiry/account change. Premium change invalidates caches; native/banner SDK-managed views are removed, but callers must also stop requesting ads in their own code. A `setAdsEnabled(false)` kill switch and `setCooldownMillis(30000)` are supplied. `setAnalyticsListener { event, placement -> ... }` is optional; no Firebase required.

```kotlin
if (!AdsLibrary.isNetworkAvailable(this)) {
    // Decide how to display an offline indication in the host.
}
```

This checks INTERNET + VALIDATED capabilities, **not** whether AdMob's servers can actually be reached.

## Interstitial

```kotlin
AdsLibrary.preloadInterstitial(applicationContext, "UNIT_ID", "home") { success -> }
AdsLibrary.loadAndShowInterstitial(this, "UNIT_ID", "home", showDelayMs = 300) {
        loaded, failed, showed, dismissed ->
    if (dismissed || failed) continueNavigationOnce()
}
```

Four Boolean fields preserve the original event-tuple shape. A request can emit `loaded`, `showed`, then `dismissed` events; only one terminal `dismissed` or `failed` result is intended. `showAnyCachedInterstitial` supports fallback. `clearPlacement` / `clearAll` remove cache. Do not navigate on `loaded`.

## Rewarded

```kotlin
AdsLibrary.loadAndShowRewarded(this, "REWARDED_ID") {
        loaded, failed, showed, dismissed, rewarded ->
    if (rewarded) grantRewardExactlyOnce()
    if (dismissed || failed) closeLoadingUi()
}
```

Five booleans preserve the original API. Reward is a distinct event from dismissal. On invalidation, a delayed request fails rather than showing.

## Native with observable preload and host styling

Three adapted native XML layouts are retained: `native_ad_small` (style 1), `native_ad_advance` (style 2), and `native_ad_large_advance` (style 3). Fonts, app-specific images, colors and `sdp` dimensions were replaced with self-contained equivalents. Pass `NativeColors(backgroundColor, adTextBackgroundColor, ctaColor, textColor, ctaTextColor)` as Android ARGB `Int`s. See `sample-integration/SampleActivity.kt` for a complete Activity example.

```kotlin
val placement = "home_native"
AdsLibrary.nativeState(placement).observe(this) { state ->
    if (state == NativeState.Ready) {
        AdsLibrary.showNative(this, binding.nativeContainer, placement, style = 2,
            colors = NativeColors(backgroundColor = Color.WHITE,
                adTextBackgroundColor = Color.LTGRAY, ctaColor = Color.BLUE))
    }
}
AdsLibrary.preloadNativeAd(applicationContext, "NATIVE_ID", placement)
// Or AdsLibrary.loadAndShowNativeAd(this, "NATIVE_ID", binding.nativeContainer)
```

Native ads are consumed once from cache and registered to **one** NativeAdView. Do **not** attach one NativeAd to multiple RecyclerView positions. For list placements, use a unique placement/container for each visible holder and cleanup on recycle. `AdsLibrary.removeAd(container)` destroys visible native ads. Always remove observers automatically with LifecycleOwner. Do not pass Activity into an application singleton.

## Banner

```kotlin
AdsLibrary.loadBanner(this, binding.bannerContainer, "BANNER_ID", collapsible = true, top = false) {
    loaded -> binding.bannerContainer.isVisible = loaded
}
```

Adaptive banner sizing and collapsible Google extras are preserved. `removeAd(container)` removes SDK-managed banner/native views. Placeholder shimmer and loading dialogs are now the host's responsibility.

## App open

```kotlin
AdsLibrary.loadAppOpenAd(applicationContext, "APP_OPEN_ID") { loaded ->
    if (loaded) AdsLibrary.showAppOpenAd(this) { showed, dismissed, failed ->
        if (dismissed || failed) continueSplashOnce()
    }
}
```

App-open cached ads expire after four hours. Host must coordinate foreground lifecycle and splash navigation. Do not display after Activity finishing/destroyed.

## Callback and concurrency contract

Public callbacks are delivered on main/UI thread by `Ui`. Google Next-Gen loader callbacks may arrive on background threads. Terminal callbacks are de-duplicated for fullscreen operations, but event callbacks (`loaded`, `showed`, `rewarded`) are distinct nonterminal notifications. Host UI must still honor Activity lifecycle; main thread does not imply that a destroyed view is valid. Requests are currently not queued while the GMA SDK initializes: call ad APIs only after `onInitialized`.

## Publishing to GitHub Packages

GitHub Packages supports private packages but **consumers need GitHub credentials even for public packages** in this workflow. Source repository may be private or public; CI uses `GITHUB_TOKEN`, consumers use a classic PAT with `read:packages` and repo access as required. Do not commit PATs. GitHub Actions publishes a Maven publication (AAR, POM and dependency metadata), not a loose AAR. Replace `OWNER` and `sdkGroup` with your actual GitHub owner before publishing.

Add to consuming `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/OWNER/ads-library")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

In each developer's **`~/.gradle/gradle.properties`** (never the repository):

```properties
gpr.user=GITHUB_USERNAME
gpr.key=GITHUB_CLASSIC_PAT_WITH_READ_PACKAGES
```

In consumer's app module:

```kotlin
dependencies { implementation("io.github.OWNER.ads:ads-library:1.0.0") }
```

Create the GitHub repo, push source and publish (install Gradle 8.13 locally or generate a wrapper first):

```bash
gradle :ads-library:assembleRelease :ads-library:publishToMavenLocal
# After successful tests:
git tag v1.0.0
git push origin v1.0.0
```

The tag starts `.github/workflows/publish.yml`, which runs publication with `-PsdkGroup=io.github.OWNER.ads -PsdkVersion=1.0.0`. For a release use `v1.0.1` etc. If owner contains invalid Maven group characters, choose a normalized lowercase group in **both** publishing and consumption.

Alternatives: JitPack is convenient for public repositories but depends on its build infrastructure and repository availability. Maven Central offers a broader, unauthenticated consumer experience but requires namespace verification, signing and release automation. GitHub Packages matches colleague/team distribution here.

## Migration table

| Original | Library | Refactor |
| --- | --- | --- |
| `Ads.kt` | `FullscreenAds.kt`, `AdsLibrary.kt` | Removed `TattooApp`, premium Preferences, Remote Config, Firebase, ProgressUtils; kept four/five Boolean callbacks |
| `NativeAdManager.kt` | `NativeAds.kt` | Per-placement native cache, LiveData, configurable colors and 3 XML styles |
| `NativeListAdManager.kt` / `WidgetNativeAd` | `NativeAds.kt` | Removed app `ItemNativeAdBinding`, unsafe multiple-holder ad reuse |
| `AdBanner.kt` | `BannerAds.kt` | Adaptive/collapsible banner; host owns shimmer |
| `AppOpenAdManager.kt` | `AppOpenAds.kt` | Four-hour cache with host-controlled ID/lifecycle |
| Original layouts | `res/layout/native_ad_*` | Removed unbundled original drawables/fonts/dimens |

## Known limitations and validation

**Not built or run** in the supplied execution environment. A project Gradle wrapper and complete original `Application`, manifest, root Gradle config, unit IDs, Firebase/Remote Config code, billing flags and RecyclerView holder implementation were **not** included in the archive. Therefore full behavior parity and release readiness **cannot** be certified. Some original behaviors (UI loading dialog, counter trigger, paused deferred replay, Remote Config gates and click analytics) are intentionally moved to host integration, not shipped as identical public APIs. Premium invalidation needs regression tests for operation cancellation callbacks, especially in-flight initial loads. Native state is synchronized by Ui dispatch while `LiveData` is exposed to lifecycle owners.

Run on a real Android-capable developer/CI machine:

```bash
gradle :ads-library:clean :ads-library:assembleRelease
gradle :ads-library:lintRelease :ads-library:publishToMavenLocal
```

Test initialization, offline/premium gating, premiums toggled mid-load, delayed shows, duplicate terminal callbacks, Activity rotations, native list recycling and cleanup, reward delivery, R8 minified host builds, consent flow and Android 24/36 devices. Only after those tests should the version be treated as stable.


## Runnable sample application (`sample-app`)

The root project includes **two Gradle modules**: `:ads-library` (publishable SDK) and `:sample-app` (Android demo APK). Open the root directory in Android Studio, sync Gradle, and run the `sample-app` configuration on an Android 7.0+ device/emulator with Google Play services. It uses Google's demo App ID and example unit IDs, not production IDs.

The sample demonstrates premium toggling, validated-network checking, interstitial preload/show, rewarded, native preload state via `LiveData.observe(this)`, native layouts/colors, banner, app-open preload/show, and cleanup. Replace demo IDs in `sample-app/src/main/java/com/example/adssdksample/SampleApplication.kt` and the AdMob App ID in its manifest before deploying an actual application. **The sample is not part of the published library artifact.**

Build from the project root with Android Studio's bundled Gradle or a locally installed Gradle compatible with AGP 8.13.2. A Gradle wrapper is not included because a matching distribution could not be fetched or verified in this environment.

## Gradle 9.4.1 / AGP 9.2 compatibility update

This project targets **Gradle 9.4.1 + Android Gradle Plugin 9.2.0 + JDK 17**.
AGP 9 provides built-in Kotlin, so **do not add** `org.jetbrains.kotlin.android`
back to the module plugin blocks. Compile SDK and target SDK remain 36.

Run `./gradlew :sample-app:assembleDebug :ads-library:assembleRelease`
(or `gradlew.bat` on Windows). This archive includes a portable bootstrap
launcher that downloads Gradle 9.4.1 on first run, **not** the official
`gradle-wrapper.jar`, because it was unavailable in the build environment.
For an official wrapper after the first successful Gradle download, run
`./gradlew wrapper --gradle-version 9.4.1 --distribution-type bin` and
commit its generated `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, and
`gradlew.bat` to Git.

**Validation limitation:** Gradle, Android SDK and remote repositories were not
available in the packaging environment, so compilation, dependency resolution,
and a real-device test have not been verified. Run the command above locally
and consult its first error if additional source-level changes are required.

## Shimmer loading and built-in native layouts

Banner and `loadAndShowNativeAd` show an animated shimmer while loading, then remove it on success, failure, premium activation, or explicit `AdsLibrary.removeAd(container)`. Customize with `ShimmerOptions`.

Native ads use SDK-provided layouts only: `style = 1` (small), `style = 2` (advanced), or `style = 3` (large advanced). You can still customize the native background, ad text background, CTA and text colors with `NativeColors`. Host-provided native XML and `NativeLayoutSpec` are no longer supported.

```kotlin
AdsLibrary.loadAndShowNativeAd(
    activity = this,
    adUnitId = NATIVE_ID,
    container = binding.nativeContainer,
    style = 2,
    colors = NativeColors(
        backgroundColor = android.graphics.Color.WHITE,
        adTextBackgroundColor = android.graphics.Color.LTGRAY,
        ctaColor = android.graphics.Color.BLUE
    ),
    shimmer = ShimmerOptions(heightDp = 130)
) { success -> /* callback on main thread */ }
```

For separate native preloading, observe `AdsLibrary.nativeState(placement)`; preloading itself does not display shimmer because it has no UI container.

### Native colors: use built-in XML defaults

The `colors` parameter is **optional** for both `showNative` and `loadAndShowNativeAd`. Omitting it leaves the three built-in native XML layouts' own background, text, and CTA backgrounds/text colors unchanged. You may also pass `NativeColors()` to keep all XML colors.

```kotlin
AdsLibrary.loadAndShowNativeAd(
    activity = this,
    adUnitId = nativeAdUnitId,
    container = binding.nativeContainer,
    style = 1
) { success ->
    // Callback on main thread
}
```

For selective overrides, use nullable properties, e.g. `NativeColors(ctaColor = Color.BLUE)`: only the CTA background changes, while all other XML styling stays intact. Previously `NativeColors()` forced hardcoded white/blue/black colors; now it preserves XML defaults.


## Ad diagnostics / centralized logging

Logging is **disabled by default**. Enable it in your host `Application` (or before requesting ads):

```kotlin
AdsLibrary.configureLogging(enabled = BuildConfig.DEBUG)
AdsLibrary.setAdLogListener { event ->
    // Main/UI thread. Use your own debug console or optional analytics adapter.
    Log.d("HostAds", "${event.format}: ${event.action} (${event.placement}) ${event.detail}")
}
```

Filter Android Studio Logcat using tag `AdsLibrary`. The sample app also writes events with `SampleAds`. To disable diagnostics:

```kotlin
AdsLibrary.configureLogging(enabled = false)
AdsLibrary.setAdLogListener(null)
```

`AdLogEvent` fields: `format`, `action`, `placement`, `detail`, `timestampMs`. Events include `initialize`, `premium_changed`, `load_requested`, `loaded`, `load_failed`, `show_requested`, `show_attempt`, `shown`, `dismissed`, `show_failed`, `reward_earned`, `cache_hit`, `skipped`, `offline`, and `shimmer_started`, where supported by the format. Events are best-effort diagnostics, not a guarantee of measured revenue, ad impressions, or clicks. Native and banner impression/click events are **not** reported unless their underlying ad objects expose the corresponding hooks; no impression is inferred from view visibility. No ad unit IDs or user identifiers are deliberately written to logs. Be careful with remote SDK error text and placement naming if forwarding logs to analytics.

The log listener is optional and dispatched on Android's main thread. Clear listeners in the host when no longer needed, to avoid retaining an Activity.


## Global cross-placement fallback (Interstitial, Rewarded, Native)

Each format has its **own** cache (no swapping ad formats). When Language preloads an interstitial/native/rewarded ad without displaying it, Onboarding can request the **same format** using a different placement/ad unit, and the SDK will prefer an available cached ad from Language. If a load of that format is already in progress, Onboarding joins the in-flight load instead of starting a second one. A subsequent fullscreen show request invalidates a previously delayed fullscreen show request of the same format.

```kotlin
// Language screen
AdsLibrary.preloadInterstitial(applicationContext, LANGUAGE_INTERSTITIAL_ID, "language")
AdsLibrary.preloadNativeAd(applicationContext, LANGUAGE_NATIVE_ID, "language")
AdsLibrary.preloadRewarded(applicationContext, LANGUAGE_REWARDED_ID)

// Onboarding screen: may consume Language's cached ads
AdsLibrary.loadAndShowInterstitial(this, ONBOARDING_INTERSTITIAL_ID, "onboarding")
AdsLibrary.loadAndShowNativeAd(this, ONBOARDING_NATIVE_ID, binding.nativeContainer)
AdsLibrary.loadAndShowRewarded(this, ONBOARDING_REWARDED_ID)
```

**Important:** An ad loaded for one ad unit may be shown at another screen only if this is consistent with your own monetization/accounting and Google ad policies. Track the original ad unit and placement separately in your analytics. Displayed native ads are not reused: remove them with `AdsLibrary.removeAd(container)`. Banner/app-open caching is not changed by this cross-placement feature. Run device tests before shipping; Android build was not verified here.


### Style-matched loading shimmer

When calling `AdsLibrary.loadAndShowNativeAd(..., style = 1/2/3)`, the loading skeleton automatically follows the **same native style**:

- `style = 1`: compact icon, headline/details and CTA.
- `style = 2`: media on the left, text and CTA on the right.
- `style = 3`: wide media on top, details and CTA underneath.

Banner shimmer instead shows an icon, text lines and CTA skeleton sized by `ShimmerOptions.heightDp`. The skeleton hides on load success, failure, premium change, or `removeAd()`. `ShimmerOptions.heightDp` controls **banner** height; native skeletons use style-specific heights for visual consistency. `showNative()` displays already cached ads immediately and therefore does not show a loading shimmer.


## Next-Gen anchored adaptive banner (revised)

`AdsLibrary.loadBanner(activity, container, adUnitId, collapsible = false, top = false, onResult = { loaded -> ... })` uses `com.google.android.libraries.ads.mobile.sdk.banner.AdView` and `BannerAdRequest`, not the legacy `com.google.android.gms.ads.AdView`. The view is added to its container *before* `loadAd`; the skeleton is overlaid while loading and removed on success or failure. The SDK destroys each `AdView` on replacement/removal, on premium cleanup, or when the container detaches from the window. `onResult` is a one-time load result and does not fire again for automatic refreshes. Call `AdsLibrary.removeAd(container)` in the host Activity's `onDestroy`. For collapsible banner ad units set `collapsible = true` and select `top`/`bottom`. Collapsible behavior depends on Google fill. Banner size is computed from the container width (falling back to available display width); request after layout for most accurate sizing. Google Next-Gen banners refresh automatically according to your ad unit configuration.

```kotlin
AdsLibrary.loadBanner(
    activity = this,
    container = binding.bannerContainer,
    adUnitId = BANNER_ID,
    collapsible = false,
    onResult = { loaded -> binding.status.text = "Banner loaded: $loaded" },
    shimmer = ShimmerOptions(enabled = true, heightDp = 60)
)
```
