pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "kitsugi-mangadenizi-mex"
include(":host-contract", ":extension")
