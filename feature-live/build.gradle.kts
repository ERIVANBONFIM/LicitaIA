plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.licitaia.feature.live"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":connector-api"))
    implementation(libs.androidx.browser)
    implementation("androidx.webkit:webkit:1.11.0")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
