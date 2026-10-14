plugins { id("com.android.library"); id("maven-publish") }
group = providers.gradleProperty("sdkGroup").get()
version = providers.gradleProperty("sdkVersion").get()
android {
    namespace = "io.github.adsbridge.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    api("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.5.0")
    api("androidx.lifecycle:lifecycle-livedata-core:2.9.4")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "ads-library"
                pom {
                    name.set("Reusable Android Ads SDK")
                    description.set("Android Next-Gen Google Mobile Ads integration with premium gating")
                    url.set("https://github.com/OWNER/ads-library")
                    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "OWNER/ads-library"}")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                    password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
                }
            }
        }
    }
}
