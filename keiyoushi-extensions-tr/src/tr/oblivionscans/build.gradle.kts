import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Oblivion Scans"
    // 1: İlk sürüm. 18+ ağırlıklı katalog (NSFW). Sitenin okuyucu kilidi
    //    ("Giriş Yapmanız Gerekiyor") yalnız site arayüzünü ilgilendirir;
    //    bölüm sayfa verileri seri sayfasının uçuş verisinde anonim olarak
    //    gömülü gelir (kullanıcı kanıtı: docs/html-dumps/oblivionscans/browser/,
    //    patron-kizinizi-istiyorum serisi 79 bölümün sayfalarıyla birlikte).
    //    Giriş, Discord OAuth'a dayandığı için eklenti içi oturum açma
    //    yerine uçuş verisi stratejisi seçildi; coin ile kilitli ve
    //    uçuşta sayfası bulunmayan bölümlerde açıklayıcı hata verilir.
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "initlisenext"

    source {
        lang = "tr"
        baseUrl = "https://oblivionscans.com"
    }
}
