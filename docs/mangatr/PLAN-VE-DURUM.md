# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Tamamlananlar

- Güncel ana sayfa/arama/detay seçicileri ve imzalı bölüm-listesi API'si korunuyor.
- Aynı bölümün farklı çevirileri ayrı reader-ID, başlık, tarih ve scanlator değerleriyle sunuluyor.
- Güncel şifreli okuyucu için nonce+canvas gate, pointer-delta, config/page-list ve dizi/obje seed biçimleri uygulandı.
- Chainsaw Man Bölüm 1'deki 180° hatası için cap.js xorshift32/Fisher–Yates sıralaması ve yatay/dikey flip bitleri Android bitmap dönüşümüne eklendi.
- `v2-` cache öneki eski ters bitmap'lerin yeniden kullanılmasını önlüyor.
- JVM sözleşme testleri, Spotless ve derleme CI kontrolleri çalıştırıldı.
- Gerçek Chromium reader'ında eklentinin `n+cv` gate çözümlemesi açıldı; sayfa 1, 2, 3, 27 ve 52 için üretilen sıra/flip kodları canlı DOM ile birebir eşleşti.
- Kaynak sürüm `1.6.30 / 106030` olarak hazırlandı.

## Yayın adımları

- [x] Release APK'sını ve bütün katalogları `1.6.30 / 106030` için üret (`42054698092`).
- [x] Üç APK kopyasını karşılaştır: 99.639 bayt; SHA-256 `0c3be7ecb932a1108f8db80743b3b313070484f1044e4e04841719da33383144`.
- [x] PR [#10](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/pull/10) ana dala merge edildi; dağıtım run'ları `42057945193` ve `42057945255` başarılı.
- [x] Kalıcı `builds` dalındaki iki APK kopyası ve beş kullanıcı kataloğu `1.6.30 / 106030` için doğrulandı.

## Kullanıcı-doğrulama adımı

- [ ] Kitsugi-Beta'da eklentiyi `1.6.30` sürümüne güncelle.
- [ ] Chainsaw Man Bölüm 1 sayfa 1, 2, 3, 27 ve 52'yi aç; metinlerin ve sayfaların düz olduğunu kontrol et.
- [ ] Bölümü kapatıp yeniden açarak yeni önbellek yolunu da kontrol et.

GitHub runner'ının CDN erişimi gerçek pikselleri sağlamadığı için OCR/180° görsel kabulü tamamlanmış değildir. Sentetik test görselleri doğru yön kanıtı sayılmaz. Ayrıntılar `TEST-RAPORU.md` içindedir.
