# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Kullanıcı kanıtı ve mevcut sonuç

- `1.6.30` ve `1.6.31` kullanıcı ekranında birleşik/ters/bozuk görseller üretti.
- `1.6.32 / 106032` PR #12 ile kalıcı yayınlandı ancak kullanıcı `image-11.png` ve `image-12.png` ile Chainsaw Man Bölüm 1 ve 2'nin `KTS-SRC-9008` gate/config hatası verdiğini bildirdi.
- `1.6.32` ürün sorununu çözmedi. Yeni aday `1.6.33 / 106033`; cihaz sonucu henüz doğrulanmadı.

## Tamamlanan teknik düzeltmeler

- Aktif dal `arena/f3fadc72-kitsugi-manga-plugins`; eski Arena dalına işlem yapılmıyor.
- Reader cevabının ham HTML'i Jsoup parse öncesinde korunuyor. Gate URL/query, fallback page key ve pointer girdileri ham gövdeden çıkarılıyor; grup seçimi sonrası yeni cevap da aynı yolu kullanıyor.
- Encrypted script verisi temel Base64 decoder'ından önce trim ediliyor. String grid/seed değerleri için savunma amaçlı DTO desteği eklendi.
- Gate hataları aşamalı `KTS-SRC-9101`–`9104` kodlarına ayrıldı. Ham birleşik görsel fallback'i açık değil.
- Sitenin `_calcPointerDelta` fonksiyonunun jQuery `$.css(body, 'flow')` kullandığı doğrulandı. Gerçek reader'larda `ease=mTr_v4`, `flow=84`; CSS fallback `20`. Production canlı 84, eski 20 ve boş ease/flow pointer adaylarını kapsıyor.

## Canlı doğrulama sonucu

- Runner'da doğrudan OkHttp reader istekleri HTTP 403 olduğu için ağ testi tekrarlanmadı. Chromium gerçek reader ve `n/cv` gate'i çalıştırdı; ham HTML/gate/pointer değerleri yalnız `/tmp` içinde production Kotlin testine verildi.
- Canlı browser-fixture + production Kotlin run `42107749381` **başarılı**: canvas ve pointer aynı, config çözülüyor, ayrı `v3` sayfalar üretiliyor.
- Bölüm 1 `125451`: 52 × 2 = **104 ayrı sayfa**. Bölüm 2 `125989`: 26 × 2 = **52 ayrı sayfa**.
- Fixture/unit + release APK CI run `42107749466` **başarılı**. Browser DOM doğrulaması geçti; gerçek CDN piksel kanıtı yok (`real_images_verified=false`).

## Yayın ve kullanıcı adımları

- [x] Production Kotlin fixture/unit testleri ve kaynak derlemesi başarılı.
- [x] Bölüm 1 ve 2'nin canlı browser fixture'ları production Kotlin config/page kodundan geçti.
- [ ] `1.6.33 / 106033` APK ve katalogları üret; eski `1.6.32` APK'sının üzerine yazma.
- [ ] PR, ana dal ve kalıcı `builds` yayınını doğrula.
- [ ] Kullanıcı yeni sürümde Chainsaw Man Bölüm 1 ve 2'yi açmalı.

## Kullanıcı kabul ölçütleri

- [ ] Bölüm 1 ve 2 gate/config hatası olmadan açılmalı.
- [ ] Her gerçek görsel parçası ayrı sayfa olmalı; bir okuyucu sayfasında iki parça birleşmemeli.
- [ ] Sıra ve yön doğru olmalı.
- [ ] Gate/config hatası ham birleşik görselle sessizce geçiştirilmemeli.

Browser DOM, canlı config/page-list, sentetik crop, DEX string'i veya release başarısı tek başına kullanıcı kabulü değildir.
