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
    // SecretStore: alias do certificado digital (KeyChain) lembrado por empresa+host.
    implementation(project(":core-security"))
    implementation(libs.androidx.browser)
    implementation("androidx.webkit:webkit:1.11.0")
    // "Manter sessão ativa": FGS só com o app em primeiro plano + inicialização via App Startup.
    implementation(libs.androidx.lifecycle.process)
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
