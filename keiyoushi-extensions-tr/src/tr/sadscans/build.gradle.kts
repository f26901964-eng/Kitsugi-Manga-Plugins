import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 11: arama/filtre/detay/okuyucu düzeltmeleri (1.6.8-1.6.10) + CDN toleransı ile ilk
    //     mağaza güncellemesi; önceki yayın 1.6.7 idi.
    versionCode = 11
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
