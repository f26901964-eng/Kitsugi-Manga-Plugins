# Manga-TR inceleme / test raporu — 5 Kasım 2026

## Kapsam ve sürüm

Kullanıcının ardışık ekran görüntüleri `1.6.30` ve `1.6.31` sürümlerinde hâlâ birleşik, ters ve sırası bozuk görseller geldiğini gösterdi. Bu nedenle yalnız site DOM'u ile flip kodlarını karşılaştırmak yeterli kabul edilmedi. Yeni kaynak sürümü `1.6.32`, katalog kodu `106032`, eklenti `versionCode` değeri `32`'dir.

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

Runner CDN/proxy engelleri nedeniyle gerçek sayfaların Android cihazdaki OCR/yön kontrolü tamamlanamadı. Sentetik görseller gerçek manga sayfası veya cihaz kanıtı değildir. Kullanıcının `1.6.32` ile her sayfada tek görsel ve doğru sıra/yön kontrolü yapması gerekir.

## Dağıtım durumu

`tachiyomi-tr.mangatr-v1.6.32.apk` ve kataloglar çalışma dalında üretildi. Üç APK kopyası **100.343 bayt** ve SHA-256 **`4a91353da786d18efbef3615216c826834aafc865f4958b3846378a56f570730`** olarak eşleşti. Ana dal PR'ı ve kalıcı `builds` yayını bekleniyor.
