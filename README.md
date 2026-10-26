# Kitsugi Manga Plugins — tek kaynak onarım profili

**Seçili tek kaynak: Gölge Bahçesi (`golgebahcesi.com`).**

Diğer Türkçe kaynakların kodları korunur; varsayılan derleme/entegrasyon profilinde etkin değildir. Bu seçim manga/manhwa/manhua akışını tek bir kaynak üzerinde toparlamak içindir. Kaynağın Türkiye’de “1 numara” olduğu veya en büyük arşive sahip olduğu bu depoda ölçülmüş bir veriyle doğrulanmış değildir.

> **Durum:** Kod ve uygulanabilir Kitsugi entegrasyonu hazırlandı; canlı site + Android cihaz doğrulaması tamamlanmadı. Bu depo tek başına telefondaki uygulamayı değiştirmez ve derlenmiş APK içermez. “%100 çalışıyor” iddiası yoktur.

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

23 sentetik sözleşme testi; Türkçe sorgu kodlaması, HTTP başlıkları, sayfalama, detaylar, eski/yeni bölüm bağlantıları, bölüm/sayfa sıralaması, kilitli/güvenli bölümler, hatalar, görsel istemcisi ve coroutine iptalini kapsar. Fixture’lar canlı siteden alınmış gibi sunulmaz.

[`verify.yml`](.github/workflows/verify.yml) çevrimdışı testler ve JVM testleri için CI tanımlar; bu oturumda CI çalıştırılmış değildir.

### Canlı ağ sözleşmesini kontrol etme

```sh
python3 scripts/smoke_golgebahcesi.py
# veya bilinen bir seriyle:
python3 scripts/smoke_golgebahcesi.py --series SERI_SLUG --query 'SERİ ADI'
```

- `0`: API zinciri ve düz bölümün ilk/orta/son görsel imzaları doğrulandı. **Android doğrulaması değildir.**
- `1`: ağ/veri/görsel hatası.
- `2`: güvenli veya kilitli bölüm; cihaz/WebView testi gerekiyor. **Başarı sayılmaz.**

## Bu ortamda doğrulananlar / kalanlar

| Kontrol | Sonuç |
|---|---|
| Python çevrimdışı testleri | **22 geçti** |
| Gerçek okuyucu varlıklarının Node testleri | **18 geçti** |
| Yeni/değişen Kotlin dosyalarında sözdizimi kontrolü | Geçti; derleme/typecheck değildir |
| Yamanın temel uygulama dosyalarına uygulanabilirliği | Yerel referans kopyasında kontrol edildi |
| Kotlin JVM testlerini Gradle ile çalıştırma | Gradle dağıtımı indirilirken ağ/TLS kısıtı nedeniyle başlayamadı |
| Canlı `api.golgebahcesi.com` smoke testi | Ortamın ağ/TLS kısıtı nedeniyle erişilemedi |
| Android APK derlemesi, WebView ve gerçek cihazda okuma | **Bekliyor** |

Site erişimi, Cloudflare, güvenli okuyucunun güncel uygulaması ve cihazın görsel biçimi desteği gerçek cihazda ayrıca doğrulanmalıdır. Kilitli/ücretli bölümler açılmaz; site erişim kısıtları aşılmaz.
