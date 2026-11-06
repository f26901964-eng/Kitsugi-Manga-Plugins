# OkuToon bölüm okuyucusu

Paylaşılan Drive klasöründeki `message (73).txt`, depodaki `docs/html-dumps/okutoon/message-73.txt` ile aynı bölüm örneğidir (Chainsaw Man Bölüm 1). İçindeki 53 `#readerPages img.reader-page` öğesi doğrudan CDN URL'leri içerir. Bunlar şifreli değildir ve bölüm sayfası okunabildiğinde eski seçici de bunları bulur.

Depodaki son canlı ölçümde liste ve seri sayfası başarılıyken bölüm URL'si `403`, `cf-mitigated: challenge` döndürmüştür (`reports/okutoon-runtime.json`). Bu tek başına kullanıcının cihazında aynı yanıtın alındığını kanıtlamaz. Kitsugi'nin incelenen `main` kodunda Cloudflare interceptor'ı doğrulamayı WebView'de bekleyip isteği tekrarlar; okuyucu da sayfa listesini 45 saniye sonra iptal eder. Bu yol ekrandaki zaman aşımıyla uyumludur ama cihaz logu olmadan kesin teşhis değildir.

Yeni kaynak kodu yalnız bölüm HTML isteğinde host'un Cloudflare interceptor'ını çıkarır: normal yanıt doğrudan çözülür, **yalnız** açık `cf-mitigated: challenge` yanıtında WebView bölüm HTML'ini okumayı dener. WebView doğrulamanın gerektirebileceği kaynakları engellemez ve en fazla 30 saniye bekler; sayfa HTML’ini doğrudan WebView’den alır, farklı bir HTTP istemcisine clearance çerezi kopyalamaya dayanmaz. WebView kendi User-Agent ve çerez deposunu kullanır. İnsan etkileşimi gerektiren veya tamamlanamayan doğrulama otomatik geçilemeyebilir; bu durumda kullanıcıya WebView'de doğrulama yapması söylenir. HTTP veya boş bölüm hataları başarılı bölüm gibi gösterilmez. Görsel isteklerinde bölüm Referer'ı kullanılır. Katalog ve diğer kaynakların istemcisi değiştirilmez.

`tests/okutoon_runtime/run.mjs` doğrudan HTTP testidir; challenge alması artık başarı sayılmaz. Android WebView doğrulamasını test etmez. `OkuToonReaderTest` kaydedilmiş HTML, URL tercihleri ve challenge tespitini test eder; sürümleme ve yayın iş akışı Gradle testinden sonra APK üretir. Gerçek cihazda WebView, çerezler, uygulama uyumu ve ilk/orta/son sayfa görüntüleme ayrıca doğrulanmalıdır.

Kaynak sürümü 1.6.10'a yükseltilmiş ve APK [Actions 42227421827](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42227421827) ile derlenip oturum dalına eklenmiştir. Aynı iş Kotlin testlerini ve APK manifestiyle katalog eşleşmesini doğrular. Bu henüz ana yayın veya `builds` dalı değildir: katalogdaki builds adresi ancak yayın iş akışı çalıştırıldıktan sonra yeni APK'ya erişir. Cihazdaki 1.6.9 kendiliğinden değişmez.

## Araştırma ve sınırlar

- [Cloudflare: challenge yanıtını tespit etme](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/): `cf-mitigated: challenge` tüm challenge sayfalarında bulunur; HTTP durumu tek başına güvenilir değildir.
- [Mihon CloudflareInterceptor](https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt): tarayıcı doğrulamasını bekler; interaktif doğrulama otomatik çözülemeyebilir.
- [LNReader #2083](https://github.com/lnreader/lnreader/pull/2083): arka plan WebView, çerez ve User-Agent uyumu; kullanıcının etkileşimini gerektiren challenge başarısız olabilir.
- Okutoon'da alınan HTML doğrudan WebView'den okunur; çerez aktarımının ve OkHttp'nin TLS fingerprint'inin kabulüne bağlı yeniden istek zorunlu değildir. İki istemcinin de aynı şekilde kabul edileceği varsayılmaz.
- Kullanıcı etkileşimi, cihazın WebView sürümü, ağ erişimi ve site politikası otomatik geçişi engelleyebilir; doğrulamanın her zaman kırılacağı veya %100 geçileceği garanti edilmez. Henüz gerçek Android cihazda doğrulanmadı.
