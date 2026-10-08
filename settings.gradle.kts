pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LicitaPRO"

include(":app")
include(":core-domain", ":core-ui", ":core-data", ":core-network", ":core-security", ":core-ai", ":core-platform")
include(":connector-api", ":connector-mock", ":connector-pncp", ":connector-comprasgov", ":ai-provider-api", ":ai-provider-mock")
include(
    ":feature-auth", ":feature-dashboard", ":feature-radar", ":feature-tender",
    ":feature-documents", ":feature-live", ":feature-bidding", ":feature-warroom",
    ":feature-settings", ":feature-audit", ":feature-messages", ":feature-competition",
    ":feature-platform",
)
