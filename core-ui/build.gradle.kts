plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.licitaia.core.ui"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core-domain"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.navigation.compose)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.hilt.navigation.compose)
    api(libs.kotlinx.coroutines.android)
    api(libs.coil.compose)
    debugApi(libs.compose.ui.tooling)
    testImplementation(libs.bundles.unit.test)
}
