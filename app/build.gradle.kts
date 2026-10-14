import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phonemcp.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonemcp.server"
        minSdk = 29
        targetSdk = 35
        // First Play upload: versionCode 1. Bump +1 on EVERY Play release (see docs/play-release.md).
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from env (never commit a keystore):
    //   PHONE_MCP_KEYSTORE_FILE, PHONE_MCP_KEYSTORE_PASSWORD, PHONE_MCP_KEY_ALIAS, PHONE_MCP_KEY_PASSWORD
    // Without them the release build falls back to debug signing (Play will reject it as an upload key —
    // generate the upload key first; see docs/play-release.md).
    val hasReleaseKey = System.getenv("PHONE_MCP_KEYSTORE_FILE") != null
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(System.getenv("PHONE_MCP_KEYSTORE_FILE")!!)
                storePassword = System.getenv("PHONE_MCP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONE_MCP_KEY_ALIAS") ?: "phone-mcp"
                keyPassword = System.getenv("PHONE_MCP_KEY_PASSWORD")
            }
        }
    }
    if (!hasReleaseKey) {
        logger.warn("PHONE_MCP_KEYSTORE_FILE not set: release builds will use debug signing (not for upload).")
    }

    buildTypes {
        release {
            // R8 ON (validated on-device 2026-10-12: full suite green on shrunk build).
            // Rules: config/proguard-rules.pro. Tighten only with a green device-matrix re-run.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../config/proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // One copy per Netty jar; version detection still works, it just logs a warning.
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.txt"
        }
    }
    composeOptions {
        // Compose compiler bundled with Kotlin 2.2; no explicit version needed.
    }
}

// Netty desktop QUIC artifacts (native .so + licenses) must never reach the APK.
// The NIO fallback needs the native *loader* classes present (Ktor references them eagerly),
// so only QUIC/HTTP3 (unused by the server) is excluded — never the transports.
configurations.all {
    exclude(mapOf("group" to "io.netty", "module" to "netty-codec-native-quic"))
    exclude(mapOf("group" to "io.netty", "module" to "netty-codec-http3"))
    exclude(mapOf("group" to "io.netty", "module" to "netty-codec-classes-quic"))
}

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.network.tls)
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.security.crypto)
    implementation(libs.listenablefuture)
    implementation(libs.zxing.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.play.location)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
