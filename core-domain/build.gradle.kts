plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    testImplementation(libs.bundles.unit.test)
}
