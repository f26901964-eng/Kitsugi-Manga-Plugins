import io.github.keiyoushi.gradle.api.ContentWarning
import org.gradle.api.tasks.testing.Test

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("main") {
        java.exclude("test/**")
        kotlin.exclude("test/**")
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

tasks.withType<Test>().configureEach {
    systemProperty("mangatr.fixtureDir", rootDir.parentFile.resolve("mangatr/live/browser").absolutePath)
}

keiyoushi {
    name = "Manga-TR"
    versionCode = 28
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
