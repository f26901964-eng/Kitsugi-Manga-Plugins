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
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

tasks.withType<Test>().configureEach {
    systemProperty("mangatr.fixtureDir", rootDir.parentFile.resolve("mangatr/live/browser").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = System.getenv("MANGATR_LIVE_TEST") == "1"
    }
}

keiyoushi {
    name = "Manga-TR"
    versionCode = 30
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    deeplink {
        host("www.manga-tr.com")
        host("manga-tr.com")
        path("/manga-..*")
    }

    source {
        lang = "tr"
        baseUrl = "https://manga-tr.com"
    }
}
