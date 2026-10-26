# Kitsugi Manga Plugins — tek kaynak onarım profili

**Seçili tek kaynak: Gölge Bahçesi (`golgebahcesi.com`).**

Diğer Türkçe kaynakların kodları korunur; varsayılan derleme/entegrasyon profilinde etkin değildir. Bu seçim manga/manhwa/manhua akışını tek bir kaynak üzerinde toparlamak içindir. Kaynağın Türkiye’de “1 numara” olduğu veya en büyük arşive sahip olduğu bu depoda ölçülmüş bir veriyle doğrulanmış değildir.

> **Durum:** Canlı katalog, detay, bölüm metaverisi ve kapak kontrolleri geçti. **Gerçek tarayıcıda okuma testi site doğrulamasında kaldı: 0/18 görsel.** Android doğrulaması ve kurulabilir APK/MEX yoktur. Bu depo tek başına telefondaki uygulamayı değiştirmez.

## 26 Ekim 2026 — canlı inceleme sonucu

Ham Next.js HTML'i ve 25 JavaScript dosyası alındı; okuyucu/işçi kodu incelendi. Mairimashita! Iruma-kun için **465 bölüm / 462. bölümde 18 şifreli sayfa**, Gölge Köle için **14 bölüm / 14. bölümde 181 şifreli sayfa** doğrulandı. Her iki kapağın gerçek pikselleri çözüldü ve kapak URL'leri site HTML'iyle eşleştirildi.

Bunlar görüntü okumasının geçtiği anlamına gelmez. Resmî okuyucu Chromium testinde görünür doğrulama istedi; hiçbir bölüm görseli alınamadı. Test başarısız olarak bırakıldı. **Çalışan eklenti/index URL'si yayımlanmış değildir.**

**[İnceleme, düzeltmeler ve kalan engeller](docs/golgebahcesi-live-audit-2026-10-26.md)** · [Gerçek tarayıcı çalıştırması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/40370515174)

## Neler değişti?

- [`sources.properties`](sources.properties): tek kaynak için açık izin listesi.
- **Keiyoushi:** Gradle yalnızca `:src:tr:golgebahcesi`, `:core` ve `:compiler` modüllerini yükler. Diğer 75 eklenti ve ortak motorlar silinmedi, derlemeye alınmaz.
- **Kotatsu:** eski ayrıştırıcılar varsayılan olarak devre dışıdır; eski Gölge Bahçesi/MangaReader kodu da buna dahildir. Kaynak enum adları korunur; fabrika devre dışı kaynağı oluşturmayı reddeder. Bağlantı çözümleyici bunları atlar.
- **Gölge Bahçesi eklentisi:** bölüm özetinde eksik olabilen okuyucu alanları, sayfalama, bölüm/sayfa sırası, boş/kilitli bölüm hataları ve güvenli okuyucu betiklerinin yaşam döngüsü düzeltildi.
- **Kitsugi native adaptörü:** `KeiSource`/OkHttp 5 bağımlılığını uygulamaya taşımadan, mevcut **OkHttp 4.12 + `MangaSource`** arayüzünü kullanır. Arama, popülerler, detay, bölüm ve görsel indirme yollarını kapsar.
- **Uygulama yaması:** yalnızca native Gölge Bahçesi’ni sunar; APK/MEX yüklemeyi, Kotatsu başlatmayı ve eski eklentilerin otomatik güncellemeyle geri gelmesini durdurur. Telefon ve TV eklenti ekranlarında tek kaynak modu gösterilir. Kurulu eklenti dosyaları, kütüphane ve okuma ilerlemesi silinmez.

## Kitsugi uygulamasına uygulama

Asıl Android uygulaması **ayrı `f26901964-eng/Kitsugi-Beta` deposundadır**. Bu oturumda o depoya doğrudan değişiklik/push yapılmadı.

Kitsugi-Beta’nın yerel kopyası varsa, bu deponun kökünden:

```sh
# Önce yalnızca kontrol; dosya değiştirmez.
python3 scripts/apply_kitsugi_integration.py /path/to/Kitsugi-Beta --check

# Yama, native kaynak, okuyucu varlıkları, çeviriler ve JVM testlerini ekler.
python3 scripts/apply_kitsugi_integration.py /path/to/Kitsugi-Beta --apply
```

Araç farklı içerikteki mevcut dosyayı ezmez; uyumsuz yama durumunda ön kontrolde durur. Aynı sürümü tekrar uygulamak güvenlidir. Dal değiştirmez, commit/push yapmaz. Temel alınan uygulama revizyonu ve dosya özetleri: [`integration/kitsugi/upstream.json`](integration/kitsugi/upstream.json).

Ardından **Kitsugi-Beta kökünde**, JDK/Android SDK ve bağımlılıklara erişimi olan bir ortamda:

```sh
./gradlew :app:testDebugUnitTest --tests '*GolgeBahcesiApiTest'
./gradlew :app:assembleDebug
```

Detaylar, cihaz kabul listesi ve sınırlar: **[Kitsugi entegrasyon kılavuzu](integration/kitsugi/README.md)**.

## Testler

### Ağ/JDK gerektirmeyen testler

Python 3.10+ ve Node.js 20+:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
node --test tests/*.test.mjs
```

Bunlar izin listesini, yama uygulama/çakışma/idempotans davranışını, ağ smoke testinin hata durumlarını ve gerçek okuyucu JavaScript varlıklarını test eder. Android derlemesi veya canlı site testi yerine geçmez.

### Gerçek Kotlin API istemcisinin JVM testleri

Android SDK gerektirmez; JDK 17 + Gradle/Maven erişimi gerekir:

```sh
bash kotatsu-parsers-tr/gradlew -p integration/kitsugi-api-tests test --no-daemon
```

29 sözleşme/regresyon testi; Türkçe sorgu kodlaması, HTTP başlıkları, sayfalama, detaylar, eski/yeni bölüm bağlantıları, bölüm/sayfa sıralaması, kilitli/güvenli bölümler, hatalar, görsel istemcisi ve coroutine iptalini kapsar. `fixtures.json` sentetiktir. `live-2026-10-26.json` ise iki gerçek API yanıtının alan projeksiyonudur; tarayıcı/görsel doğrulaması gibi sunulmaz.

[`verify.yml`](.github/workflows/verify.yml) çevrimdışı testleri ve gerçek Kotlin API istemcisini CI'da çalıştırır. [26 Ekim doğrulama çalıştırması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/40370990412) geçti: **29 JUnit testi; hata/atlanan test yok**. Bu derleme yalnız API istemcisini kapsar; Android okuyucusunu ve tüm uygulamayı derlemez.

### Canlı ağ sözleşmesini kontrol etme

```sh
python3 scripts/smoke_golgebahcesi.py
# veya bilinen bir seriyle:
python3 scripts/smoke_golgebahcesi.py --series SERI_SLUG --query 'SERİ ADI'
```

- `0`: API zinciri ve düz bölümün ilk/orta/son görsel imzaları doğrulandı. **Android doğrulaması değildir.**
- `1`: ağ/veri/görsel hatası.
- `2`: güvenli veya kilitli bölüm; cihaz/WebView testi gerekiyor. **Başarı sayılmaz.**

### Ham HTML/JavaScript + gerçek tarayıcı denetimi

Node.js 22, Playwright Chromium ve Sharp gerektirir:

```sh
npm ci --prefix scripts/live-audit
cd scripts/live-audit && npx playwright install chromium && cd ../..
node scripts/live-audit/audit.mjs
```

HTML/JS ve rapor `.audit/golgebahcesi/` içine yazılır; Git'e eklenmez. [CI iş akışı](.github/workflows/audit-golgebahcesi.yml) bunları hata halinde de kısa süreli artifact olarak saklar. `browser_chain_passed` dışında çıkış kodu `2` olur; doğrulama veya eksik sayfalar başarı sayılmaz. Hiçbir CAPTCHA/oturum/ücretli bölüm kısıtı aşılmaz.

## Bu ortamda doğrulananlar / kalanlar

| Kontrol | Sonuç |
|---|---|
| Python çevrimdışı testleri | **26 geçti** |
| Gerçek okuyucu varlıklarının Node testleri | **21 geçti** |
| Yeni/değişen Kotlin dosyalarında sözdizimi kontrolü | Geçti; derleme/typecheck değildir |
| Yamanın temel uygulama dosyalarına uygulanabilirliği | Yerel referans kopyasında kontrol edildi |
| Kotlin API istemcisi derlemesi ve JUnit testleri | CI'da **29 geçti**; Android typecheck değildir |
| Canlı API/HTML/sayfa metaverisi/kapak kontrolleri | Gerçek tarayıcı denetiminde **17 geçti** |
| Güvenli bölümün bütün görselleri | **Geçmedi: görünür site doğrulaması, 0/18** |
| Android APK derlemesi, WebView ve gerçek cihazda okuma | **Bekliyor** |

Yerel curl/Python TLS hatası önceki ortamın kısıtıydı; site erişimi başka araçlar ve CI ile sağlandı. Kalan sorun “site hiç incelenmedi” değil, korumalı okuma zincirinin geçmemesi ve Android doğrulamasının bulunmamasıdır. Kilitli/ücretli bölümler açılmaz; site erişim kısıtları aşılmaz.
