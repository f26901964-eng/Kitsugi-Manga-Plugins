pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "kitsugi-mangadenizi-mex"
include(":host-contract", ":extension")
