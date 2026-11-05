# Manga-TR inceleme / test raporu — 5 Kasım 2026

## Kapsam ve sürüm

Bu çalışma, Chainsaw Man Bölüm 1 sayfalarının 180° ters görünmesini düzeltir. Hazırlanan kaynak sürümü `1.6.30`, katalog sürüm kodu `106030`, eklenti `versionCode` değeri `30`'dur. Önceki yayın `1.6.29 / 106029` idi.

## Uygulanan düzeltme

- Güncel `cap.js` okuyucusundaki xorshift32, Fisher–Yates şerit sıralaması ve sayfa bazındaki flip bitleri Android tarafına taşındı.
- Flip kodları `1 = yatay`, `2 = dikey`, `3 = iki eksen` olarak uygulanır. Böylece iki ekseni de çevrilen şeritler 180° ters kalmaz.
- `_fpx` gate isteği gerçek reader ile aynı `{"n": nonce, "cv": canvasFingerprint(c)}` gövdesini ve pointer-delta çözümleme anahtarını kullanır.
- Yeni üretilen sıra/flip fragment'ları `v2-` öneki taşır; eski ters bitmap önbelleği yeniden kullanılmaz.
- Şifre çözme, seed dizi/obje biçimleri, canvas fingerprint vektörleri, pointer ve sıra/flip üretimi JVM sözleşme testleriyle korunur.

## Otomatik test sonuçları

- Kotlin CI run `42047594906`: Spotless, JVM fixture/contract testleri ve derleme kontrolleri başarılı.
- Canlı Chromium run `42052741943`: DDoS-Guard exchange 200, reader 200, eklenti `n+cv` gate çözümlemesi başarılı; grid `2`, seed/page count `52`.
- Aynı canlı reader'da üretilen kodlar site DOM'undaki kodlarla beş örnekte birebir eşleşti:

| Sayfa | Kodlar |
| --- | --- |
| 1 | `[200,1]` |
| 2 | `[1,0]` |
| 3 | `[301,200]` |
| 27 | `[100,301]` |
| 52 | `[201,100]` |

- `1` ve `3` kodlu örnekler sırasıyla yalnız yatay ve iki eksenli flip içerir; yeni kodun eski ters görüntü hatasını hedefleyen yolu canlı DOM değerleriyle doğrulanmıştır.

## Bilinen doğrulama sınırı

GitHub Actions runner'ı `image.mngtr.site` isteklerinde CDN `403` alıyor. Android image header'ları, Windows/macOS runner'ları ve yalnız test amaçlı public image proxy denemeleri gerçek beş sayfa görselini sağlamadı. Bu nedenle gerçek pikseller üzerinde OCR/180° kabulü tamamlanmış sayılmaz.

Testin ürettiği etiketli sentetik PNG'ler yalnız dönüşüm hattını çalıştırmak içindir; gerçek sayfa veya doğru yön kanıtı değildir. `real_images_verified=false` sonucu gizlenmez. Gerçek Android/Kitsugi-Beta kurulumunda Chainsaw Man Bölüm 1 sayfa 1, 2, 3, 27 ve 52 ayrıca kullanıcı tarafında kontrol edilmelidir.

## Dağıtım durumu

`1.6.30` kaynak kodu hazırdır; bu dosyanın yazıldığı aşamada APK/katalog yayını henüz tamamlanmamıştır. Yayın sonrası kalıcı APK URL'si, sürüm kodu ve SHA-256 değeri ayrıca doğrulanmalıdır.
