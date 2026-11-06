import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("main") {
        java.setSrcDirs(listOf("src/eu"))
        kotlin.setSrcDirs(listOf("src/eu"))
    }
    sourceSets.named("test") {
        java.srcDir(rootDir.parentFile.resolve("tools/kitsugi-mangitto-bridge/src"))
        kotlin.srcDir(rootDir.parentFile.resolve("tools/kitsugi-mangitto-bridge/src"))
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    testImplementation(libs.tachiyomi.lib.v16)
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("mangitto.fixtures", rootDir.parentFile.resolve("docs/mangitto").absolutePath)
}

keiyoushi {
    name = "Mangitto"
    versionCode = 3
    contentWarning = ContentWarning.NSFW // or MIXED, please confirm
    libVersion = "1.6"

    source {
        lang = "tr"
        baseUrl = "https://mangtto.com"
    }

    deeplink {
        path("/manga/..*")
    }
}
