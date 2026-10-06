plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":connector-api"))
    testImplementation(libs.bundles.unit.test)
}
