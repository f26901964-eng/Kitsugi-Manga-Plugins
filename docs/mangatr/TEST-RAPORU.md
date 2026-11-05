# Manga-TR inceleme / test raporu — 5 Kasım 2026

## Kapsam ve sürüm

Kullanıcının ardışık ekran görüntüleri `1.6.30` ve `1.6.31` sürümlerinde hâlâ birleşik, ters ve sırası bozuk görseller geldiğini gösterdi. Kalıcı yayınlanan `1.6.32 / 106032` de cihazda başarılı olmadı: Chainsaw Man Bölüm 1 ve 2, `KTS-SRC-9008` gate/config hatasıyla açılmadı. Bu nedenle `1.6.32` ürün düzeltmesi olarak kabul edilmiyor. Yeni aday `1.6.33 / 106033`; canlı production Kotlin testleri geçti, kullanıcı cihazı doğrulaması henüz yapılmadı.

## Aktif gate/config düzeltmesi

- `getPageList` artık HTTP cevabının ham gövdesini `asJsoup()` öncesinde alıyor; grup seçimi sonrasındaki cevap için de aynısını yapıyor.
- Gate path, `n`/`c` query değerleri, fallback page key ve pointer girdileri Jsoup'un yeniden serialize ettiği HTML yerine ham cevaptan çıkarılıyor.
- Gate URL, gate anahtarı, config decrypt ve sayfa üretimi hataları `KTS-SRC-9101`–`9104` ile ayrılıyor; gizli anahtar/nonce kullanıcı mesajına yazılmıyor.
- Browser referansı encrypted config'i `.strip()` ile okurken Android temel Base64 decoder'ına script'in newline/girintisini temizlemeden veriyordu. `decrypt` artık decoder öncesinde `trim()` uyguluyor; whitespace'li gerçek `<script type="application/json">` fixture testi eklendi.
- Sitenin `_calcPointerDelta` fonksiyonu flow'u önce jQuery `$.css(body, 'flow')` ile okuyor. Her iki canlı reader'da gerçek girdi `ease=mTr_v4`, `flow=84`; CSS `--ui-flow` değeri `20`. Production artık canlı 84, CSS 20 ve CSS/ease henüz boşken oluşan sınırlı pointer adaylarını deniyor.
- Canlı browser-fixture + production Kotlin run `42107749381` başarılıdır. Chromium'dan alınan gerçek ham HTML, `n/cv` gate cevabı ve pointer yalnız `/tmp` içinde tutuldu; Git'e veya artifact'a gizli değer yazılmadı. Kotlin aynı canvas/pointer değerlerini üretti, config'i çözdü ve ayrı `v3` sayfaları üretti.
- Bölüm 1 reader `125451`: 52 kaynak eleman × 2 = **104 ayrı sayfa**. Bölüm 2 reader `125989`: 26 kaynak eleman × 2 = **52 ayrı sayfa**. İki config de `current-pointer` ile çözüldü.
- Aynı kaynak commit'in fixture/unit + release APK CI run `42107749466` başarılıdır. Browser DOM doğrulaması da geçti; gerçek CDN piksel doğrulaması `real_images_verified=false` durumundadır.
- Canlı browser run `42093595643`: gate query alanları `c,n`, nonce 16, canvas token 16; config `grid=int`, `seeds=list<int>`. String-DTO esnekliği yalnız savunma amaçlıdır.
- Bu sonuçlar cihaz başarısı iddiası değildir; kullanıcının Bölüm 1 ve 2 ekranındaki tek parça/sıra/yön sonucu ayrıca doğrulanmalıdır.

## Canlı reader yapısı

- Chainsaw Man Bölüm 1 reader elemanları tek URL taşıyor; site bu URL'yi `background-size: 100% 200%` ile iki CSS slice olarak sunuyor.
- `1.6.31` her hedef slice için ayrı `v3-{destination}-{codes}` fragment'ı üretiyordu ancak kullanıcı cihazında birleşik kaynak görsel gelmeye devam etti.
- `1.6.32` artık request üretimini açıkça yapıyor, current `v3` isteğinde `FORCE_NETWORK` kullanıyor ve current config veya slice decode başarısızsa ham birleşik bitmap'i başarılı cevap olarak döndürmüyor.

## Uygulanan düzeltme

- `MangaTRImagePipeline` production request ve bitmap yolunu `HttpSource` Android stub'ından bağımsız, doğrudan test edilebilir hâle getirir.
- `imageRequest` seçilen slice fragment'ını açık `Request.Builder` ile korur; current `v3` için `Cache-Control: no-cache` / `FORCE_NETWORK` uygular.
- İki slice kullanan 52 reader elemanı 104 ayrı sayfa üretir; her cevap yalnız seçilen source strip'in crop+flip sonucudur.
- Current gate/config çözülemezse eski encrypted-attribute fallback ile ham scramble görsel kullanılmaz; açık hata verilir.
- Current `v3` fragment/order/decode geçersizse orijinal birleşik görsel döndürülmez.
- Eski `v2-` ve legacy encrypted-order birleştirme davranışı yalnız eski okuyucular için korunur.

## Gerçek production pipeline testleri

Kotlin CI run `42089413923` ve release run `42090052071` başarılıdır:

- `Page → imageRequest` fragment'ı ve imzalı query hedefini korur; `no-cache` header'ı doğrulanır.
- Gerçek production interceptor'ına `20×40`, iki parçalı sentetik PNG verilir.
- `v3-0-0,1` ve `v3-1-0,1` isteklerinin her biri **`20×20`** ayrı bitmap döndürür; orijinal `20×40` bitmap dönmez.
- Geçersiz current destination (`v3-2-0,1`) ham birleşik kaynağa dönmek yerine `IOException` üretir.
- Gate DTO'sunun `n` ve `cv` alanları doğru serialize edilir; crypto/seed/order/flip sözleşme testleri geçer.
- Robolectric gerçek Canvas piksel render'ı yapmadığından renk/piksel iddiası test edilmez; boyut ve fallback yasağı gerçek production kodunda test edilir.
- Canlı gate+DOM run `42090052016` başarılıdır; gerçek CDN piksel doğrulaması hâlâ yoktur.
- Python katalog testleri: 15 başarılı, özel snapshot gerektiren 4 skip.
- Release DEX içinde `v3-`, `no-cache` ve current-slice hata korumaları doğrulandı.

## Bilinen doğrulama sınırı

Runner CDN/proxy engelleri nedeniyle gerçek sayfaların Android cihazdaki OCR/yön kontrolü tamamlanamadı. Sentetik görseller gerçek manga sayfası veya cihaz kanıtı değildir. Kullanıcının yeni sürümde Bölüm 1 ve 2'yi açması, her sayfada tek görsel ve doğru sıra/yön görmesi gerekir; bu doğrulama yapılmadan düzeltme başarılı sayılmaz.

## Dağıtım durumu

`tachiyomi-tr.mangatr-v1.6.32.apk` PR #12 ile ana dala ve kalıcı `builds` dalına yayınlandı. Üç APK kopyası **100.343 bayt** ve SHA-256 **`4a91353da786d18efbef3615216c826834aafc865f4958b3846378a56f570730`** olarak eşleşti; kalıcı yayın run `42091010382` başarılı oldu. Ancak kullanıcının `image-11.png` ve `image-12.png` kanıtları bu sürümün Bölüm 1 ve 2'de gate/config hatası verdiğini gösterir. Dağıtım başarısı ürün başarısı değildir.

Yeni `tachiyomi-tr.mangatr-v1.6.33.apk` PR #13 ile ana dala alındı. Branch release `42108951711`, main build `42109766869` ve kalıcı yayın `42109766922` başarılıdır. Üç branch APK kopyası ve kalıcı `builds` APK'sı **100.739 bayt**, SHA-256 **`49f1b018b6a28f90619a8175a25958329cfff3b26dcc870fad67948da21ed4c9`** olarak doğrulandı. Beş kullanıcı kataloğu `1.6.33 / 106033` gösteriyor. Python testleri 15 başarılı, 4 özel snapshot skip. Cihaz kabulü hâlâ kullanıcı doğrulamasına bağlıdır.
