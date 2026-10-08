import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ID do cliente OAuth "Web" do Google Cloud (não é segredo, mas fica fora do código):
// local.properties → LICITAIA_GOOGLE_SERVER_CLIENT_ID=xxxx.apps.googleusercontent.com
// ou propriedade Gradle / variável de ambiente com o mesmo nome. Vazio = botão Google desabilitado.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val googleServerClientId: String = localProps.getProperty("LICITAIA_GOOGLE_SERVER_CLIENT_ID")
    ?: providers.gradleProperty("LICITAIA_GOOGLE_SERVER_CLIENT_ID").orNull
    ?: System.getenv("LICITAIA_GOOGLE_SERVER_CLIENT_ID")
    ?: ""

android {
    namespace = "com.licitaia.feature.auth"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${googleServerClientId.trim()}\"")
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-platform"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    // Credential Manager + Sign in with Google (ID token apenas; nenhum escopo de Gmail/Drive).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
