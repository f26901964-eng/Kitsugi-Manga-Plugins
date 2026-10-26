# Gölge Bahçesi — gerçek site ve okuyucu denetimi

**Tarih:** 26 Ekim 2026. **Sonuç: okuma zinciri geçmedi.**

Bu incelemede yalnızca depodaki eski ayrıştırıcıya veya sentetik fixture'lara bakılmadı: canlı API, ham Next.js HTML'i, HTML'deki 25 JavaScript dosyası ve anonim Chromium okuyucusu kontrol edildi. Aşağıdaki sonuçlar Android cihaz testi değildir.

- [Gerçek tarayıcı çalıştırması ve saklanan HTML/JS kanıtları](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/40370515174)
- [Makine tarafından üretilmiş sonuç ve JavaScript SHA-256 özetleri](audits/golgebahcesi-2026-10-26.json)
- Denetim aracı: [`scripts/live-audit/audit.mjs`](../scripts/live-audit/audit.mjs)

## Canlı veride doğrulananlar

| Kontrol | Mairimashita! Iruma-kun | Gölge Köle |
|---|---|---|
| Seri türü | MANGA | MANHWA |
| Başlıkla arama ve aynı seri slug'ı | Geçti | Geçti |
| Bölüm uç noktasındaki kayıt sayısı | 465 | 14 |
| Seçilen kilitsiz bölüm | 462 | 14 |
| Bölüm kimliği | `6ad522327d73db9753612074` | `6adbfb66754ddd5445d518ce` |
| API'deki sayfa metaverisi | 18 sayfa, indeks 0–17 | 181 sayfa, indeks 0–180 |
| Görsel teslimi | `secure`, `imageEnc: true`, `.enc` | `secure`, `imageEnc: true`, `.enc` |
| Site HTML'i ile API'deki kapak URL'si | Aynı | Aynı |
| Gerçek kapak dosyasının piksel çözümlemesi | WebP, 460×710 | WebP, 1038×1515 |
| Bölüm görsellerinin tarayıcıda alınması | **0/18; doğrulamada kaldı** | Bu çalıştırmada denenmedi |

Popülerler sayfa 1 ve 2 ayrı ayrı 24 kayıt getirdi; ikinci sayfa yeni seriler içerdi. API, HTML, bölüm kimliği/sayfa indeksleri ve kapaklar için **17 kontrol geçti**. Okuyucu kontrolü bunlardan ayrıdır ve **başarısızdır**.

Detay yanıtlarında `chapterCount: 0` görüldüğü halde bölüm uç noktalarında sırasıyla 465 ve 14 kayıt vardı. Bölüm listesi bu özet alandan türetilmiyor. Liste uç noktasındaki `pages: null` da "bölümde görsel yok" sayılmıyor; içerik için `/api/chapters/{id}` çağrılıyor.

## Güncel HTML/JavaScript'te görülen akış

Başlıca incelenen dosyalar, o tarihteki adlarıyla:

- `/_next/static/chunks/011xd0039-t4m.js`: API istemcisi ve okuyucu hata durumları.
- `/_next/static/chunks/30wy5w9qj-cy1.js`: okuyucu bileşeni, güvenli manifest, doğrulama ve sayfa çizimi.
- `/_next/static/chunks/34sivhj99y04b.js`: görsel işçisi.
- `/_next/static/chunks/3ss_6f5l95mr1.js`: okuyucu ayarları, tanılama ve ortam kontrolleri.

Okuyucu doğrudan `.enc` adresini bir `<img>` içine koymuyor:

1. Seri ve bölüm numarasıyla okuyucu erişim/manifest akışını başlatıyor.
2. Yanıt doğrulama gerektirirse Turnstile veya hCaptcha bileşenini gösteriyor; ücretli/kilitli yanıtlar ayrıca ele alınıyor.
3. Güvenli teslimde sayfa URL'lerini işçiye `type: "store"` mesajıyla gönderiyor; `idec.bin`, WebAssembly ve işçiler arası mesajlaşma kullanılıyor.
4. Görseller kapalı shadow root içindeki canvas parçalarına, `transferControlToOffscreen()` üzerinden çiziliyor. `[data-reader-page]` kapsayıcıları ve görünürlük/görsel önbellek yönetimi var.
5. `primitive_tamper`, otomasyon/ortam denetimleri ve `poison`/`crash` hata yolları da mevcut.

Bu nedenle yalnızca API'den 18 veya 181 adres almak, dosya uzantısını değiştirmek ya da HTML'de `<img>` aramak güncel okuyucu desteği sayılmaz. Mevcut yakalama kancasının bütün güncel güvenlik/çözümleme yollarıyla uyumu da kanıtlanmış değildir; "doğrulama geçilirse kesin çalışır" sonucu çıkarılmıyor.

## Gerçek okuyucu sonucu

Resmî URL: `https://golgebahcesi.com/manga/mairimashita-iruma-kun/bolum/chapter-462`

```json
{
  "status": "blocked_by_challenge",
  "expectedCount": 18,
  "bridgeCount": 0,
  "decodedCount": 0,
  "challengeVisible": true,
  "errors": ["site_verification_required"],
  "passed": false,
  "androidVerified": false
}
```

Doğrulama metni gerçekten tarayıcıda görünürdü; yalnızca Cloudflare betiği indirilmesinden başarısızlık sonucu çıkarılmadı. Otomatik doğrulamaya süre tanındı. Test çıkış kodu `2` ve CI sonucu başarısızdır; bu bekleyen kapı yeşile çevrilmedi. Doğrulama, oturum veya ücretli bölüm kısıtı aşılmadı. Test temiz, anonim bir tarayıcı oturumunda yapıldı; bu sonuç her kullanıcı oturumunun başarısız olacağını da kanıtlamaz.

## İnceleme sırasında düzeltilenler

- `deliverySystem` yanında `imageEnc` ve `.enc` uzantısı da kontrol ediliyor; şifreli dosya normal görsel indiricisine düşmüyor.
- API'nin beklenen sayfa sayısı, WebView'in döndürdüğü sayı ve cache ile karşılaştırılıyor; eksik yakalama tamamlanmış bölüm sayılmıyor.
- Eksik, yinelenen veya kesirli sayfa indeksleri sessizce yeniden numaralandırılmıyor.
- Görünür doğrulama kapısı ve sitenin "bu tarayıcıda görüntülenemedi" durumu ayrı hatalar. Her zaman aşımı yanlış biçimde CAPTCHA diye sınıflandırılmıyor.
- Keiyoushi tarafında bölüm kimliği kontrolü ve resmî CDN/doğrulama kaynakları için izinler düzeltildi. Bunların Android üzerinde çalışması ayrıca doğrulanmalıdır.
- Gerçek 26 Ekim API yanıtlarından alanlar korunarak iki regresyon fixture'ı eklendi; sentetik testlerden açıkça ayrıldı.
- Tarayıcı denetimi, bütün sayfa indekslerini/piksel çözümlemesini/boyutlarını kontrol etmeden başarı vermiyor. HTML/JS kanıtlarını hata durumunda da saklıyor.

## Hâlâ tamamlanmayan kabul koşulları

- Android APK/MEX derlemesi ve Kitsugi içinde gerçek sayfa gösterimi.
- Güvenli bölümün bütün görsellerinin doğru sırada alındığının kanıtı.
- Uzun manhwa bölümlerinde süre/bellek, bölüm değiştirme, iptal ve cache testleri.
- Kullanıcının ekleyip yükleyebileceği, doğrulanmış bir eklenti paketi ve uyumlu depo/index URL'si.

**Sonuç:** Katalog ve metaveri akışı canlı veride çalışıyor. "Kurulabilir ve sorunsuz okutan tek kaynak hazır" kabul koşulu henüz karşılanmadı. Yerel uygulama yaması, yayımlanmış eklenti veya tamamlanmış Android entegrasyonu gibi sunulmamalıdır.
