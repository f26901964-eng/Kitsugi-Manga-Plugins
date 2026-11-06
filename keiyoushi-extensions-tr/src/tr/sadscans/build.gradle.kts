import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 12: arama kutusunun adı ve tRPC yönlendirici adı (build hash) değişse de çalışan sürüm.
    //     İstekler gerçek Android Chrome başlıklarıyla gider; 1.6.11'de arama boş dönüyordu.
    versionCode = 12
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
