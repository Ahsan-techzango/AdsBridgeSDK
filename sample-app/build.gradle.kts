plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.adssdksample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.adssdksample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":ads-library"))
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}
