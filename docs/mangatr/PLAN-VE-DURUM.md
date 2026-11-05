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

- [ ] Release APK'sını ve bütün katalogları `1.6.30 / 106030` için üret.
- [ ] Üç APK kopyasının boyut ve SHA-256 değerlerini karşılaştır; kalıcı `builds` URL'sinin yeni dosyayı sunduğunu doğrula.
- [ ] Çalışma dalındaki değişiklikler için PR aç/merge et ve dağıtım workflow'unun tamamlandığını kontrol et.

## Kullanıcı-doğrulama adımı

- [ ] Kitsugi-Beta'da eklentiyi `1.6.30` sürümüne güncelle.
- [ ] Chainsaw Man Bölüm 1 sayfa 1, 2, 3, 27 ve 52'yi aç; metinlerin ve sayfaların düz olduğunu kontrol et.
- [ ] Bölümü kapatıp yeniden açarak yeni önbellek yolunu da kontrol et.

GitHub runner'ının CDN erişimi gerçek pikselleri sağlamadığı için OCR/180° görsel kabulü tamamlanmış değildir. Sentetik test görselleri doğru yön kanıtı sayılmaz. Ayrıntılar `TEST-RAPORU.md` içindedir.
