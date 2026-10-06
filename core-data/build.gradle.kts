plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.licitaia.core.data"
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-security"))
    implementation(project(":core-ai"))
    implementation(project(":connector-api"))
    implementation(project(":ai-provider-api"))
    implementation(project(":ai-provider-mock"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Extração de texto de PDFs de edital (Apache 2.0). Inicializado via PDFBoxResourceLoader.init(context).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // OCR local (sem rede) para editais escaneados: ML Kit Text Recognition v2, script latino, modelo embutido (~4 MB no APK).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.bundles.unit.test)
}
