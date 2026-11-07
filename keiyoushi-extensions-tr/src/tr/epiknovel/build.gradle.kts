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

// KSP üreteci testlerde çalışmaz (üretilmiş sınıf test ortamında yok).
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}

// Testler docs/html-dumps/epiknovel/ üzerindeki gerçek dump'lara bağlıdır.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val dumps = rootDir.parentFile.resolve("docs/html-dumps/epiknovel")
    systemProperty("epiknovel.home", dumps.resolve("message-97.txt").absolutePath)
    systemProperty("epiknovel.search", dumps.resolve("message-98.txt").absolutePath)
    systemProperty("epiknovel.series", dumps.resolve("message-99.txt").absolutePath)
    systemProperty("epiknovel.chapter", dumps.resolve("message-100.txt").absolutePath)
    systemProperty("epiknovel.list", dumps.resolve("live-list.html").absolutePath)
    systemProperty("epiknovel.listPage2", dumps.resolve("live-list-page2.html").absolutePath)
    systemProperty("epiknovel.listCompleted", dumps.resolve("live-list-completed.html").absolutePath)
    systemProperty("epiknovel.seriesCompleted", dumps.resolve("live-series-completed.html").absolutePath)
    systemProperty("epiknovel.searchLive", dumps.resolve("live-search.html").absolutePath)
    systemProperty("epiknovel.chapterLive", dumps.resolve("live-chapter.html").absolutePath)
}

keiyoushi {
    name = "Epik Novel"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "tr"
        baseUrl = "https://www.epiknovel.com"
    }
}
