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

/**
 * Add or remove modules to load as needed for local development here.
 */
// The repository-wide allowlist also controls the generated public catalogs.
val enabledFile = File(rootDir.parentFile, "enabled_plugins.txt")
require(enabledFile.isFile) { "enabled_plugins.txt bulunamadı" }
enabledFile.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .distinct()
    .forEach { module ->
        require(Regex("[a-z0-9_-]+/[a-z0-9_-]+").matches(module)) { "Geçersiz eklenti: $module" }
        val (lang, name) = module.split("/")
        require(File(rootDir, "src/$lang/$name/build.gradle.kts").isFile) { "Eklenti bulunamadı: $module" }
        loadIndividualExtension(lang, name)
    }

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")
include(":compiler")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            include("src:${dir.name}:${subdir.name}")
        }
    }
}
fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
