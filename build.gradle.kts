// Top-level build file. Plugin versions resolve from the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-android removed: AGP 9.0+ provides built-in Kotlin support.
    alias(libs.plugins.kotlin.compose) apply false
}
