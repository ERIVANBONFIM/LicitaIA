plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.licitaia.feature.platform"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-platform"))
    // Motor de IA on-device (análise/proposta com a chave local do aparelho).
    implementation(project(":ai-provider-api"))
    // Motor do robô on-device (PortalRobotEngine) — REUSO sem alterar; ponte importa p/ os repos locais.
    implementation(project(":feature-live"))
    // Site da VPS dentro do app (modelo B): script no início da página (login + barra embaixo).
    implementation("androidx.webkit:webkit:1.11.0")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
