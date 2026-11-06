import io.github.keiyoushi.gradle.api.ContentWarning
import org.gradle.api.tasks.testing.Test

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("main") {
        java.setSrcDirs(listOf("src/eu"))
        kotlin.setSrcDirs(listOf("src/eu"))
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// The @Source generated entry point is only needed for the packaged extension, not for unit tests.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

keiyoushi {
    name = "Manga Diyarı"
    // 2: chapter and reader fixes for the current site (number based reader route, optional fields).
    // 3: live-verified against the 2026-11 API contract (empty chapter lists, CDN covers, TR genres).
    // 4: genre filters send the label the site actually stores; unopenable rows are skipped.
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl = "https://mangadiyari.com"
        lang = "tr"
    }

    deeplink {
        path("/series/..*")
        path("/seri/..*")
    }
}
