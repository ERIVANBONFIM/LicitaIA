plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.licitaia.feature.settings"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-ui"))
    // GoogleAiAuthorizer: fluxo "Entrar com conta Google" (PendingIntent) da tela Provedor de IA.
    implementation(project(":core-ai"))
    // Navegador interno de portais (PortalWebPolicy/PortalWebSessions): "Sair do portal" limpa cookies.
    implementation(project(":feature-live"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
