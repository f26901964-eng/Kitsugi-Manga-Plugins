import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 12: arama kutusunun adı ve tRPC yönlendirici adı değişse de çalışan, tarayıcı başlıklarıyla
    //     istek atan sürüm (1.6.11'de arama hâlâ boş dönüyordu).
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
