import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LunariScans"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "initlisenext"

    source {
        lang = "tr"
        baseUrl = "https://lunariscans.com"
    }
}
