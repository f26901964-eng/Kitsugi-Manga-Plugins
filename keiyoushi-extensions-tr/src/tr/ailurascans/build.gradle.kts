import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AiluraScans"
    // 1: İlk sürüm. Not: GitHub runner IP'sine site 403 veriyor (Cloudflare);
    //    cihaz trafiği kullanıcı tarayıcısıyla aynı koşuldadır.
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "initlisenext"

    source {
        lang = "tr"
        baseUrl = "https://ailurascans.com"
    }
}
