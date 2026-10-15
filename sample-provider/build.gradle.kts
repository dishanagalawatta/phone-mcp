import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.phonemcp.sampleprovider"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonemcp.sampleprovider"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Zero external dependencies by design: the sample proves any app can speak
// the capability protocol with framework APIs alone (Service + Messenger +
// org.json). Normative contract: app mcp/CapabilityIpcContract.kt.
