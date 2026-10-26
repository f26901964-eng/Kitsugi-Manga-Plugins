@file:Suppress("ktlint:standard:kdoc")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kei") {
            from(files("gradle/kei.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Keiyoushi"

// Fail closed: archived Turkish extensions must not reappear in a bulk build.
val sourceProfile = java.util.Properties().apply {
    file("../sources.properties").inputStream().use { load(it) }
}
val enabledExtensions = sourceProfile.getProperty("keiyoushi.enabled", "")
    .split(',').map(String::trim).filter(String::isNotEmpty)
check(enabledExtensions == listOf("tr:mangadenizi")) {
    "The recovery profile must contain only tr:mangadenizi"
}
enabledExtensions.forEach { module ->
    check(file("src/${module.replace(':', '/')}/build.gradle.kts").isFile) {
        "Unknown enabled extension: $module"
    }
    include(":src:$module")
}

include(":core")
include(":compiler")
// MangaDenizi is API-based; archived multisrc/library modules remain disabled.
// Kitsugi releases use extensions/mangadenizi native MEX, NOT a modern KeiSource APK.
