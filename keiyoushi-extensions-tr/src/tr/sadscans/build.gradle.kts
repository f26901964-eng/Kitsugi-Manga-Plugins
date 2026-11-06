import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 11: arama/filtre/detay/okuyucu düzeltmeleri (1.6.8-1.6.10) + okuyucu CDN toleransı.
    //     Uygulamadaki mağaza güncellemesi bu sürümle 1.6.7 -> 1.6.11 yükseltmesini görür.
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
