plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.licitaia.connector.pncp"
    // Diagnóstico contra as APIs REAIS (RadarLiveDiagnosticTest): só roda com -Plicitaia.live=true.
    testOptions {
        unitTests.all { test ->
            val live = (project.findProperty("licitaia.live") ?: "false").toString()
            test.systemProperty("licitaia.live", live)
            if (live == "true") test.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    api(project(":connector-api"))
    implementation(project(":core-domain"))
    implementation(project(":core-network"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Diagnóstico ao vivo do pipeline da busca (PNCP + Compras.gov.br reais).
    testImplementation(project(":connector-comprasgov"))
}
