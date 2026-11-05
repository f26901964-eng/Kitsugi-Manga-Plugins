# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Kullanıcı kanıtı ve mevcut sonuç

- `1.6.30` ve `1.6.31` kullanıcı ekranında birleşik/ters/bozuk görseller üretti.
- `1.6.32 / 106032` PR #12 ile kalıcı yayınlandı; CI, release, APK hash ve DEX kontrolleri başarılı oldu.
- Buna rağmen kullanıcı `image-11.png` ve `image-12.png` ile Chainsaw Man Bölüm 1 ve 2'nin açılmadığını bildirdi: `Manga-TR okuyucu anahtarı/config'i çözülemedi` / `KTS-SRC-9008`.
- Dolayısıyla `1.6.32` ürün sorununu çözmedi. Derleme veya dağıtım başarısı cihaz başarısı olarak sunulmayacak.

## Aktif teknik çalışma

- Aktif dal `arena/f3fadc72-kitsugi-manga-plugins`; eski Arena dalına işlem yapılmıyor.
- Reader cevabının ham HTML'i artık Jsoup parse öncesinde korunuyor. Gate URL/query, fallback page key ve pointer girdileri ham gövdeden çıkarılıyor; grup seçimi sonrası yeni cevap da aynı yolu kullanıyor.
- Gate hataları aşamalı `KTS-SRC-9101`–`9104` kodlarına ayrıldı. Ham birleşik görsel fallback'i açık değil.
- Canlı browser run `42093595643`, `n/c` query'sini, 16 karakterlik nonce/canvas token'ını ve sayısal grid/seed tiplerini doğruladı; `current-pointer` config'i çözüyor.
- Production Kotlin canlı testi aynı `n/cv` gate isteği ve pointer delta ile Bölüm 1/2 reader'larını çalıştıracak şekilde güncellendi.
- Trim sonrası fixture/APK run `42096850038` başarılı oldu. Canlı run `42098534354` ise reader/gate öncesindeki gereksiz detay isteğinde HTTP 403 aldı; trim veya gate/config sonucu olarak yorumlanamaz.
- Browser `.strip()` kullanırken Android Base64 decoder'ının script whitespace'ini reddetmesi kök neden adayı olarak düzeltildi. Decoder öncesi `trim()` ve whitespace'li script fixture testi eklendi.
- Detay isteği kaldırıldı; canlı test artık doğrudan reader→gate→config→page-list yolunu çalıştırıyor ve CDN görsel indirmeyi ayrı opt-in tutuyor.

## Kontrol adımları

- [ ] Production Kotlin fixture/unit testleri ve release derlemesi başarılı olmalı.
- [ ] Gerçek OkHttp + DDoSGuardInterceptor + production DTO/config canlı testi gate/config aşamalarını geçmeli.
- [ ] Yeni sürüm numarasıyla APK ve kataloglar üretilmeli; eski `1.6.32` APK'sının üzerine yazılmamalı.
- [ ] PR, ana dal ve kalıcı `builds` yayını doğrulanmalı.
- [ ] Kullanıcı yeni sürümde Chainsaw Man Bölüm 1 ve 2'yi açmalı.

## Kullanıcı kabul ölçütleri

- [ ] Bölüm 1 ve 2 gate/config hatası olmadan açılmalı.
- [ ] Her gerçek görsel parçası ayrı sayfa olmalı; bir okuyucu sayfasında iki parça birleşmemeli.
- [ ] Sıra ve yön doğru olmalı.
- [ ] Gate/config hatası ham birleşik görselle sessizce geçiştirilmemeli.

Runner gerçek CDN görsellerini alamadığında `real_images_verified=false` kalır. Browser DOM, sentetik crop, DEX string'i veya release başarısı tek başına kullanıcı kabulü değildir.
