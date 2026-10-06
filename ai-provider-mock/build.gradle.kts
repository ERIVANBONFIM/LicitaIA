plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":ai-provider-api"))
    testImplementation(libs.bundles.unit.test)
}
