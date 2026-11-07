import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DuckScans"
    // 1: İlk sürüm — Initlise-Next uçuş verisi okuyucusu; sitedeki giriş
    //    kilidi okuyucu arayüzüne özgüdür, gömülü sayfa verisi anonim gelir.
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "initlisenext"

    source {
        lang = "tr"
        baseUrl = "https://duckscans.com"
    }
}
