// AGP 9 has built-in Kotlin; declaring the Kotlin plugin here (not applied) pins the KGP version
// so it matches the Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
