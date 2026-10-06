plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core-domain"))
    testImplementation(libs.bundles.unit.test)
}
