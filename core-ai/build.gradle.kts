plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.licitaia.core.ai"
}

dependencies {
    api(project(":ai-provider-api"))
    implementation(project(":ai-provider-mock"))
    implementation(project(":core-domain"))
    implementation(project(":core-network"))
    implementation(project(":core-security"))
    implementation(libs.kotlinx.coroutines.android)
    // Google Identity Services (AuthorizationClient): "Entrar com conta Google" para a API Gemini (OAuth 2.0).
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // "Entrar com ChatGPT": Foreground Service (NotificationCompat/ServiceCompat).
    implementation(libs.androidx.core.ktx)
    // Autorização da OpenAI aberta numa Chrome Custom Tab.
    implementation(libs.androidx.browser)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
