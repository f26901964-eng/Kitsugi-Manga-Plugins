import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("main") {
        java.setSrcDirs(listOf("src/eu"))
        kotlin.setSrcDirs(listOf("src/eu"))
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

keiyoushi {
    name = "MerlinToon"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "initmanga"

    source {
        lang = "tr"
        baseUrl = "https://merlintoon.com"
        // Preserve the ID previously derived from "Merlin Scans/tr/2".
        id = 4883620005315879682L
        versionId = 2
    }
}
