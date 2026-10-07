import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Repositório GitHub ("owner/repo") consultado pela atualização automática (API pública de releases).
// local.properties → LICITAIA_UPDATE_REPO=owner/repo, ou propriedade Gradle / variável de ambiente.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val updateRepo: String = (
    localProps.getProperty("LICITAIA_UPDATE_REPO")
        ?: providers.gradleProperty("LICITAIA_UPDATE_REPO").orNull
        ?: System.getenv("LICITAIA_UPDATE_REPO")
        ?: "ERIVANBONFIM/LicitaIA"
    ).trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
require(updateRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "LICITAIA_UPDATE_REPO deve ter o formato owner/repo (recebido: '$updateRepo')"
}

android {
    namespace = "com.licitaia.app"

    defaultConfig {
        applicationId = "com.licitaia.app"
        targetSdk = 34
        versionCode = 15
        versionName = "0.5.4"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // Inspeção do WebView (chrome://inspect) só em builds locais de diagnóstico: -Plicitaia.webviewDebug=true.
        // Releases publicados nunca usam essa flag.
        buildConfigField(
            "boolean", "WEBVIEW_DEBUG",
            (providers.gradleProperty("licitaia.webviewDebug").orNull == "true").toString(),
        )
    }

    val signingPath = providers.gradleProperty("LICITAIA_RELEASE_STORE_FILE").orNull
        ?: providers.environmentVariable("LICITAIA_RELEASE_STORE_FILE").orNull
    fun signingValue(key: String) = providers.gradleProperty(key).orNull ?: providers.environmentVariable(key).orNull
    if (!signingPath.isNullOrBlank()) {
        signingConfigs.create("personalRelease") {
            storeFile = file(signingPath)
            storePassword = signingValue("LICITAIA_RELEASE_STORE_PASSWORD") ?: error("Release store password missing")
            keyAlias = signingValue("LICITAIA_RELEASE_KEY_ALIAS") ?: error("Release key alias missing")
            keyPassword = signingValue("LICITAIA_RELEASE_KEY_PASSWORD") ?: error("Release key password missing")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Nome distinto para não confundir com o app release (que recebe as atualizações).
            resValue("string", "app_name", "LicitaIA Dev")
        }
        release {
            resValue("string", "app_name", "LicitaIA")
            if (!signingPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("personalRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-network"))
    implementation(project(":core-security"))
    implementation(project(":core-ai"))
    implementation(project(":connector-api"))
    implementation(project(":connector-mock"))
    implementation(project(":ai-provider-api"))
    implementation(project(":ai-provider-mock"))
    implementation(project(":feature-auth"))
    implementation(project(":feature-dashboard"))
    implementation(project(":feature-radar"))
    implementation(project(":feature-tender"))
    implementation(project(":feature-documents"))
    implementation(project(":feature-live"))
    implementation(project(":feature-bidding"))
    implementation(project(":feature-warroom"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-audit"))
    implementation(project(":feature-messages"))
    implementation(project(":feature-competition"))

    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.bundles.unit.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}
