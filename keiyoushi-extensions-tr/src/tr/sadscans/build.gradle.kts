import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 13: kart başlığı h3 olmasa ve /seriler alt yola yönlense de arama/liste çalışır.
    // 12: arama kutusunun adı ve tRPC yönlendirici adı (build hash) değişse de çalışır.
    versionCode = 13
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "tr"
        baseUrl = "https://sadscans.net"
    }

    deeplink {
        path("/seriler/..*")
        path("/reader/..*")
    }
}
