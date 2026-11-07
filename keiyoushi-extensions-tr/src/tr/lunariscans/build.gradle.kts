import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LunariScans"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "initlisenext"

    source {
        lang = "tr"
        baseUrl = "https://lunariscans.com"
    }
}
