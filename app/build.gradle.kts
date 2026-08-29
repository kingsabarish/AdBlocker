plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android plugin: AGP 9.0+ has built-in Kotlin support. The Compose
    // compiler plugin applies on top.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.adblocker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adblocker"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// jvmTarget is not set explicitly: with AGP built-in Kotlin it defaults to
// android.compileOptions.targetCompatibility (17 above).

dependencies {
    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM keeps all Compose artifacts on one version)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Settings storage
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
