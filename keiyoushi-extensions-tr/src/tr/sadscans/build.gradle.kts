import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SadScans"
    // 17: görseller — kart kapakları lazy/srcset/_next/image üzerinden bulunur, kapaklı arayüz
    //     kademesi sitemap'tan önce çalışır, okuyucu sorgu dizili/CDN görsellerini alır.
    // 16: WAF mimarisi — tRPC artık POST (sorgu dizesinde JSON = "malformed data" bloğu),
    //     saniyede en fazla 2 istek, sert blokta 10 dk boyunca hiç istek atılmaz.
    // 15: Cloudflare mimarisi — doğrulama ekranı beklenir (12 sn), çerez yoksa API hiç denenmez,
    //     cf_clearance üreten WebView'in UA'sı API çağrılarında birebir kullanılır.
    // 14: Kitsugi her aramayı 25 sn'de kesiyor; API denemesi 8 sn, WebView kademesi 18 sn
    //     (eski 45+45 sn toplam uygulama tarafından kesiliyordu: "Kaynak araması zaman aşımı").
    // 13: kart başlığı h3 olmasa ve /seriler alt yola yönlense de arama/liste çalışır.
    // 12: arama kutusunun adı ve tRPC yönlendirici adı (build hash) değişse de çalışır.
    versionCode = 17
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
