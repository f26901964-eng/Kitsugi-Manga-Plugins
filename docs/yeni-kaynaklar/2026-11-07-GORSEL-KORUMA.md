# Initlise-Next görsel koruması: kök neden ve aşım stratejisi

Tarih: 2026-11-07 · Kapsam: duckscans.com, lunariscans.com, ailurascans.com, oblivionscans.com

## Soru

Kullanıcı: katalog/arama/bölümler çalışıyor ama **sayfa görselleri hiç gelmiyor**.
Bu siteler resimleri **nasıl** koruyor ve her katman **nasıl** aşılır?

## Kanıt zinciri (runner sondajları — reports/ altında raporlar)

| # | Deney | Sonuç | Anlamı |
|---|---|---|---|
| 1 | Uçuş verisindeki **imzasız** `/uploads/seri/...` yolları: 8 header kombinasyonu | hepsi **403** (`Server: cloudflare`, `Cache-Control: private, no-store`) | Düz yol kasıtlı olarak kapalı; görüntü için kullanılamaz |
| 2 | Kapaklar `/uploads/seri-kapak/*.webp` | **200** | Koruma yol bazlı: bölüm görselleri hedefli; kapak serbest |
| 3 | `/api/chapters` CORS ara yazılımı | Origin/Referer + `sec-fetch-*` isteyen 400 | API tarayıcı-kökenli istemci bekliyor |
| 4 | Doğru anahtar: `series=<slug>&chapter=<NUMARA>&includepages=true` | **200**, `success:true` | Okuyucu chunk'ındaki çağrının birebir aynısı (docs/html-dumps/duckscans/chunks/22g9e7_wv23lw.js) |
| 5 | API yanıtındaki yollar | **imzalı**: `?v=<ts>&t=<expiry>&s=<hmac>&k=<keyId>&sv=5` | Platform imzalı URL şeması (sv=5 = şema sürümü) |
| 6 | **Taze imzalı URL** + python-requests (runner IP) | **403** | İmza tek başına yetmez: istemci kimliği/çerez/IP şartı var |
| 7 | Aynı URL + tarayıcı header'ları (sec-fetch, client-hints, Origin) | **403** | Sadece header taklidi yetmiyor → TLS parmak izi ve/veya çerez katmanı |
| 8 | `POST /api/reader-guard/verify` | 200 `{"verified":true}`, çerez YOK | Koruma çerezle değil; muhtemel telemetri/onay uçları |
| 9 | curl_cffi (Chrome 124 TLS taklidi) | (koşu: initlise-tls-probe.yml) | Parmak izi hipotezinin son ayırt edici testi |
| 10 | MerlinsToon dosyası (docs/merlintoon/OKUYUCU-TESHIS.md) | CI'da 5 farklı TLS profili + gerçek Chromium: **hepsi 403** | Runner IP'sinden bu ailenin görsel katmanı **hiçbir istemciyle** doğrulanamıyor; cihaz tek hakem |

## Platform kimliği ve resmi tutum

- Siteler WordPress **Init Manga** (inithtml.com) ailesinden dönüşmüş **Initlise-Next**
  (Next.js) platformunu kullanıyor: API gövdelerinde `legacyWordPressId`,
  uçuş yollarında `uploads/init-manga/...` kalıntıları var.
- Üreticinin dokümantasyonu (en.inithtml.com) koruma felsefesini **açıkça** yazıyor:
  - "Key Gating" modu: **günlük dönen anahtarlar**, anahtar yalnız **tarayıcı
    kontrollerini geçen** istemciye verilir; sunucu **"tarayıcı olmayan araçları reddeder"**,
    misafirler **IP başına**, okuyucular hesap başına limitlenir.
  - Init Uploader: **özel imza doğrulaması** (custom signature validation).
- DuckScans topluluk ağı: Oblivion ("DuckScans topluluğu burada buluşuyor"),
  Ailura (eski Letona). Dört site aynı altyapı, aynı koruma seti.

## Koruma katmanları (tespit edilen sırayla)

1. **Yol kapısı:** `/uploads/seri/**` imzasız istemeye **403** (kapak hariç).
2. **İmza şeması (sv=5):** `?v=...&t=...&s=...&k=...` — `/api/chapters` üretir;
   `t` = son kullanma, `s` = HMAC, `k` = anahtar kimliği. İmzasız/bozuk imza → 403.
3. **İstemci kimliği:** imzalı URL + eksik tarayıcı kimliği → **403** (deney #6-7).
   Cloudflare düz 403 döner, **challenge açmaz** → uygulamanın otomatik
   CloudflareInterceptor'ı tetiklenmez (o, challenge işaretli yanıtları bekler).
4. **CORS ara yazılımı:** `/api/*` Origin/Referer + sec-fetch başlıkları ister.
5. **Oturum kapısı (site bazlı):** Oblivion `allowUnauthenticated:false` → anonim
   `/api/chapters` **pages vermez**; DuckScans `true` → anonime imzalı sayfa verir.
6. **Cloudflare ön katman:** gerçek tarayıcıya `cf_clearance` (UA'ya bağlı) verir;
   veri merkezi IP + tarayıcı olmayan TLS'e yol bazlı 403.

## Aşım stratejisi (katman başına)

| Katman | Aşım | Durum |
|---|---|---|
| Yol kapısı + imza | Sayfaları uçuştan DEĞİL, `/api/chapters`'tan al (okuyucunun aynısı) | **1.6.3'te çözüldü** |
| CORS | Referer+Origin+sec-fetch başlık seti | **1.6.3'te çözüldü** |
| İstemci kimliği (Cloudflare düz 403) | **WebView oturum ısıtma**: cihazın gerçek Chromium'u siteyi yükler → `cf_clearance` paylaşılan çerez kavanozuna düşer → WebView'in **UA'sı saklanır** → OkHttp tüm API+görsel isteklerini o UA+çerezle yollar | **1.6.4'te eklendi** (SadScans üretimde kanıtlı desen) |
| Oturum kapısı (Oblivion) | WebView'de Discord girişi; çerezler otomatik akar. Coin'li bölümde açıklayıcı hata | Belgelendi |
| Data merkezi IP (yalnız runner) | Çözülemez ve çözülmesi gereksiz: kullanıcı cihazı konut IP'sidir | Kısıt olarak belgelendi |

## Sürüm haritası

- **1.6.2** (106002): uçuş verisinden imzasız yollar → görseller 403. *Hatalı*.
- **1.6.3** (106003): `/api/chapters` imzalı akış + CORS başlıkları + tutarlı UA.
  Runner imzalı URL'i de 403'lüyor; cihazda CF clearance'ı olmadığından aynı tablo
  (kullanıcı teyidi: "hâlâ gelmiyor").
- **1.6.4** (106004): **WebView oturum ısıtma** — ilk 403'te gizli WebView oturumu
  açılır, `cf_clearance` kazanılır, WebView UA'sı tüm isteklere zorlanır, tek retry.
  Hiçbir katman geçilmezse kullanıcıya Türkçe yol gösteren hata.

## Kalıntı riskler

- Cloudflare **Bot Fight Mode** tipi sıkı JA3 engeli varsa OkHttp + clearance bile
  düşebilir; o ihtimalde sıradaki kademe görseli WebView `interceptRequest` ile
  akıtmaktır (Chromium kimliği garantili). 1.6.4'ün cihaz sonucuna göre karar.
- Kitsugi uygulamasındaki **aynı paketten eski APK kalması** kusuru (bkz.
  docs/merlintoon/OKUYUCU-TESHIS.md): kullanıcı 1.6.4 kurduğunda sürümün
  gerçekten 1.6.4 olduğu "Kaynaklar → sürüm"den teyit edilmeli.

## EK (okuyucu JS'i tam çözümlemesi — 22g9e7_wv23lw.js)

Okuyucu bileşeni (ScrambledImage) resmi şu akışla indirir; **eksik olan her
kalem 403/kilit adayıdır**:

1. `await C()` — `__duckInstallReaderSecurity` tarafından kurulan tarayıcı
   güvenlik kontrolü; engellenmişse istek hiç başlamaz.
   `POST /api/reader-guard/verify` (`hcaptcha` Duck'ta açık, Oblivion'da kapalı;
   boş token ile verify 200 `{"verified":true}` döner).
2. URL dönüşümleri: `k` parametresi varsa **`cv=5` eklenir**; kripto modunda
   **`rk=<cacheKey>`** eklenir.
3. Başlıklar: `Accept: image/webp,image/*;q=0.8`, **`X-Duck-Scramble-Version: 5`**,
   kripto modunda `X-Duck-Reader-Crypto: 1` + **`X-Duck-Reader-Key: <publicKey>`**
   (tarayıcı anahtar çifti üretir, sunucu yanıtı o anahtarla şifreler).
4. Yanıt: kripto modunda `application/octet-stream` → JS worker çözer →
   **scramble'lı satırlar canvas'a programatik çizilir** (ham dosya bozuk gelir);
   düz modda `image/*` blob'u doğrudan `<img>`'ye gider.
5. 429 + `x-duck-reader-guard: slow` → retry-after bekleyip yeniden dener.

Chunk md5 duckscans = oblivionscans (`575e79d0401b`) → header adları iki sitede
aynı ("Duck" marka ismine rağmen).

### 1.6.5 (versionCode 4) — okuyucu-birebir görsel isteği

- `imageRequest`: `Referer` = **bölüm sayfası** (Page.url), `Accept` =
  `image/webp,image/*;q=0.8`, **`X-Duck-Scramble-Version: 5`**,
  `Sec-Fetch-Dest: empty`, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: same-origin`.
- Sayfa yollarına `k` parametresi varsa **`cv=5`** eklenir.
- 1.6.4'ün WebView ısıtması (cf_clearance + WebView UA) korunur.
- Runner'dan doğrulama imkânsız (IP katmanı); cihaz tek doğrulayıcı.
- Kalıntı risk: site kripto modundaysa (octet-stream) OkHttp katmanı çözemez;
  o ihtimalde tek yol WebView içinde render edilen canvas'ın toDataURL ile
  aktarılması (yerel sunucu köprüsü) — plan B olarak belgelendi.
