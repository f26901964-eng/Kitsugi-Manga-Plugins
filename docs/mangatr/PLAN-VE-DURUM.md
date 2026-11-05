# Manga-TR görev planı ve güncel durum — 5 Kasım 2026

## Tamamlananlar

- Güncel ana sayfa/arama/detay seçicileri ve gerçek Chrome bölüm-listesi fixture'ları eklendi.
- CSS seed ve SHA-256 imzalı bölüm API'si, `0 → 20 → +100` sayfalama ve boş bitiş yanıtı uygulandı.
- Aynı bölümün farklı çevirileri ayrı reader-ID, başlık, tarih ve `scanlator` değerleriyle korunuyor; 109 karttan 153 benzersiz çeviri ayrıştırıldı.
- Güncel şifreli okuyucu öznitelikleri, `_fpx` gate'i, gerçek sayfa sırası, noscript/0×0 tuzak eleme ve scramble fragment'ı uygulandı.
- DDoS-Guard clearance ve aynı isteğin tekrarı, AJAX/image header ayrımı ve görselde site-kökü Referer eklendi.
- JVM fixture/contract testleri, Python katalog testleri, Spotless ve debug/release derlemeleri CI'da çalıştırıldı.
- Canlı testte dört chapter-2 çevirisinin gate ve sayfa-listesi kontrolleri geçti. Görsel yüklemeleri runner Cloudflare engelinden açık skip olarak ayrıldı; başarılı sayılmadı.
- `1.6.29` / `106029` APK'sı üretildi; `prebuilt/apk`, `apk`, `builds/apk` ve bütün JSON katalogları release run `42015177025` tarafından güncellendi.
- Üç yayın APK'sının boyutu `96.819` bayt ve SHA-256 değeri `14389dc6feba2f3bed493699c28f142e15c0adca803482444e5b080025f2a518` olarak eşleşti.

## Dağıtım durumu

- PR [#7](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/pull/7) `main` için açıldı; otomatik birleştirilmedi.
- APK ve kataloglar bu çalışma dalında hazır. Kataloglardaki kalıcı `builds` dalı URL'sinin yeni APK'yı sunması için PR birleştirilmeli ve mevcut `build-mangatr` / `manga-yayin` workflow'ları çalışmalıdır. `builds` dalında yeni APK henüz bulunmadığı API ile kontrol edildi.

## Kalan kullanıcı-doğrulama adımı

- [ ] PR #7'yi incele/birleştir ve `builds` dalındaki dağıtım workflow'unun tamamlandığını kontrol et.
- [ ] APK'yı gerçek Android/Kitsugi-Beta kurulumunda aç; arama, detay, dört chapter-2 çevirisi ve her çevirinin ilk görselini kontrol et.
- [ ] Şeritli/scramble bir bölümde görsel sırasını ve önbellekten yeniden açmayı kontrol et.

GitHub Actions hem OkHttp hem headed Chrome'da `image.mngtr.site` için Cloudflare `403 Attention Required` / `ERR_BLOCKED_BY_ORB` aldı. Bu nedenle cihazda görsel doğrulaması tamamlanmış kabul edilmez. Ayrıntılar `TEST-RAPORU.md` içindedir.
