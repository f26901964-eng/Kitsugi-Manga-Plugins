# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Tamamlananlar

- Kullanıcının `1.6.31` sonrası da birleşik görsel alması nedeniyle yalnız DOM testleri yeterli sayılmadı.
- Production request/bitmap yolu bağımsız `MangaTRImagePipeline` nesnesine ayrıldı ve Robolectric ile doğrudan test edildi.
- İki ayrı `20×20` çıktı, fragment korunması, `FORCE_NETWORK` ve geçersiz current slice için ham fallback yasağı doğrulandı.
- Current gate/config başarısızken ham encrypted-attribute görsel fallback'i kapatıldı.
- `1.6.32 / 106032` APK ve katalogları üretildi.
- CI `42089413923`, release `42090052071`, canlı gate+DOM `42090052016` başarılı.
- Üç APK kopyası 100.343 bayt ve SHA-256 `4a91353da786d18efbef3615216c826834aafc865f4958b3846378a56f570730` olarak eşleşti.

## Yayın adımları

- [ ] PR aç ve ana dala birleştir.
- [ ] `manga-yayin` ve ana dal derleme workflow'larını kontrol et.
- [ ] Kalıcı `builds` APK ve kullanıcı kataloglarının `1.6.32 / 106032` olduğunu doğrula.

## Kullanıcı-doğrulama adımı

- [ ] Manga-TR'yi `1.6.32` sürümüne güncelle ve Chainsaw Man Bölüm 1'i yeniden aç.
- [ ] Her sayfada yalnız tek görsel bulunduğunu, sıranın ve yönün düz olduğunu kontrol et.
- [ ] Gate/config hatası görülürse hatayı aynen bildir; artık bozuk ham görsel sessiz fallback olarak gösterilmeyecek.

Gerçek CDN görselleri runner'da alınamadığı için cihaz/piksel doğrulaması tamamlanmış değildir.
