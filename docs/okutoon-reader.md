# OkuToon bölüm okuyucusu

Paylaşılan Drive klasöründeki `message (73).txt`, depodaki `docs/html-dumps/okutoon/message-73.txt` ile aynı bölüm örneğidir (Chainsaw Man Bölüm 1). İçindeki 53 `#readerPages img.reader-page` öğesi doğrudan CDN URL'leri içerir. Bunlar şifreli değildir ve bölüm sayfası okunabildiğinde eski seçici de bunları bulur.

Depodaki son canlı ölçümde liste ve seri sayfası başarılıyken bölüm URL'si `403`, `cf-mitigated: challenge` döndürmüştür (`reports/okutoon-runtime.json`). Bu tek başına kullanıcının cihazında aynı yanıtın alındığını kanıtlamaz. Kitsugi'nin incelenen `main` kodunda Cloudflare interceptor'ı doğrulamayı WebView'de bekleyip isteği tekrarlar; okuyucu da sayfa listesini 45 saniye sonra iptal eder. Bu yol ekrandaki zaman aşımıyla uyumludur ama cihaz logu olmadan kesin teşhis değildir.

## Doğrulanmış API yolu (2026-11-06)

OkuToon'un kendi herkese açık okuyucu API'si `/api/v1/series/{seri-slug}/chapters/{bölüm-slug}` sayfa listesini `data.pages[].image_url` ve `page_number` alanlarında döndürüyor. [Canlı kontrol 42228665528](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42228665528) sonucunda aynı ortamda HTML okuyucu 403 challenge dönerken:

- Chainsaw Man 1: API 200, 53 sayfa; ilk/orta/son görsel 200 ve WebP imzası.
- Chainsaw Man 2: API 200, 26 sayfa; ilk/orta/son görsel 200 ve WebP imzası.
- One Piece 1197: API 200, 16 sayfa; ilk/orta/son görsel 200 ve WebP imzası.

Görsel imzası kontrolü Android'de gerçek decode/çizim testi değildir. Aynı kontrolün masaüstü Chrome tarayıcısı challenge üzerinde bekledi ve okuyucu açılmadı; bu Android WebView davranışını kanıtlamaz. Ayrıntı: `reports/okutoon-challenge-probe.json`. Ölçüm tek CI IP'sinden yapıldı; bütün bölümler veya her ağ için garanti değildir.

Sürüm 1.6.11 bölüm sayfaları için önce bu API'yi kullanır: seri/bölüm slug'larını doğrular, tüm geçerli görsel adreslerini sayfa numarasına göre sıralar; bozuk, boş veya uyumsuz API cevabını okuyucu olarak kabul etmez. API başarısızsa eski HTML ve otomatik WebView yolu yedektir. Bu sayede test edilen API yolu HTML Cloudflare challenge'ına hiç girmez; bu bir CAPTCHA çözümü değildir. API gelecekte engellenebilir veya değişebilir; yedek WebView interaktif doğrulamada yine kullanıcı yardımı isteyebilir. Görsel isteği bölüm Referer'ını kullanır.

`tests/okutoon_runtime/run.mjs` üç canlı API ve ilk/orta/son görseli kontrol eder. `OkuToonReaderTest` API URL'si, validasyon, sıralama ve kaydedilmiş HTML'yi test eder. APK ve katalog yalnız derleme ve testler başarılı olduktan sonra güncellenir. Gerçek cihazda ekran çizimi ve APK uyumu ayrıca doğrulanmalıdır. Oturum dalı `main`/`builds` yayını değildir.

## Araştırma ve sınırlar

- [Cloudflare: challenge yanıtını tespit etme](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/): `cf-mitigated: challenge` tüm challenge sayfalarında bulunur; HTTP durumu tek başına güvenilir değildir.
- [Mihon CloudflareInterceptor](https://github.com/mihonapp/mihon/blob/main/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt): tarayıcı doğrulamasını bekler; interaktif doğrulama otomatik çözülemeyebilir.
- [LNReader #2083](https://github.com/lnreader/lnreader/pull/2083): arka plan WebView, çerez ve User-Agent uyumu; kullanıcının etkileşimini gerektiren challenge başarısız olabilir.
- Okutoon'da alınan HTML doğrudan WebView'den okunur; çerez aktarımının ve OkHttp'nin TLS fingerprint'inin kabulüne bağlı yeniden istek zorunlu değildir. İki istemcinin de aynı şekilde kabul edileceği varsayılmaz.
- Kullanıcı etkileşimi, cihazın WebView sürümü, ağ erişimi ve site politikası otomatik geçişi engelleyebilir; doğrulamanın her zaman kırılacağı veya %100 geçileceği garanti edilmez. Henüz gerçek Android cihazda doğrulanmadı.
