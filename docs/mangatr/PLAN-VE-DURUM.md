# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Tamamlananlar

- Kullanıcının iki görsel parçasının tek sayfada birleşmesi şikâyeti canlı reader DOM'uyla incelendi.
- Bu bölümde her elemanın tek URL kullandığı, sitenin bu URL'yi iki CSS slice olarak sunduğu doğrulandı.
- `1.6.30`'daki tek bitmap/tek `Page` yaklaşımı kaldırıldı.
- `1.6.31` her hedef slice'ı ayrı `Page` ve tek-strip JPEG olarak döndürüyor; sıra ve flip değerleri korunuyor.
- Yeni `v3-` fragment'ları eski birleştirilmiş önbelleği ayırıyor; legacy `v2-` yolu desteklenmeye devam ediyor.
- Sözleşme testleri iki reader elemanından dört ayrı sayfa ve doğru hedef fragment'larını doğruluyor.
- Canlı örneklerin her biri için iki ayrı çıktı üretildi.
- Kotlin CI `42082228411`, release `42082228514` ve canlı gate+DOM run `42082228450` başarılı.
- `1.6.31 / 106031` APK ve katalogları üretildi; üç kopyanın SHA-256 değeri `e319220ce96d93f9a7799e613426f4fa71e72deb4a9e75aed32c1b5b6ac5bab7` olarak eşleşti.

## Yayın adımları

- [x] PR [#11](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/pull/11) ana dala birleştirildi.
- [x] `manga-yayin` run `42083252779` ve ana dal derleme run `42083252572` başarılı.
- [x] Kalıcı `builds` APK kopyaları ve beş kullanıcı kataloğu `1.6.31 / 106031` için doğrulandı.

## Kullanıcı-doğrulama adımı

- [ ] Kitsugi-Beta'da Manga-TR'yi `1.6.31` sürümüne güncelle ve bölümü yeniden aç.
- [ ] Chainsaw Man Bölüm 1'in 104 ayrı sayfa geldiğini ve her sayfada yalnız bir görsel bulunduğunu kontrol et.
- [ ] Özellikle önceki hatalı örneklerde metinlerin ve görsellerin düz olduğunu kontrol et.

Runner CDN engeli nedeniyle gerçek piksel OCR/Android doğrulaması tamamlanmış değildir; sentetik çıktılar gerçek sayfa kanıtı değildir.
