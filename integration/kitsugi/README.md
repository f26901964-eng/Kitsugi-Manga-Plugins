# ARŞİV — eski Gölge Bahçesi uygulama yaması

> **Bu entegrasyon artık seçili değildir. Uygulamayın.** Aktif kaynak MangaDenizi,
> yeni dağıtım biçimi native `.mex` paketidir: [güncel kurulum](../../README.md).
> Eski `single-source.patch` harici MEX/APK yüklemesini kapatır ve yeni kurulumla çakışır.
> `apply_kitsugi_integration.py` aktif profilde bu nedenle yazma yapmadan durur.
> Aşağıdaki metin geçmiş denemenin teknik kaydıdır; çalışan kurulum vaadi değildir.

## Kapsam ve gerçek durum

Bu dizin **uygulamaya eklenecek kaynak kodudur**, kurulabilir APK/MEX değildir. Uygulamanın ayrı deposuna bu oturumda push yapılmadı. Yama temel revizyonu:

- Depo: `f26901964-eng/Kitsugi-Beta`
- Revizyon: `c2a254e22270d485053689854bf5ae41dd03d675`
- Yama: [`single-source.patch`](single-source.patch)
- Referans dosya SHA-256 özetleri: [`upstream.json`](upstream.json)

**Canlı site incelendi:** API, ham HTML, 25 JavaScript dosyası ve gerçek Chromium okuyucusu denendi. Katalog/bölüm metaverisi/kapak için 17 kontrol geçti; güvenli bölüm **site doğrulamasında kaldı ve 0/18 görsel alındı**. Kotlin API istemcisinin 29 JVM testi CI'da geçti. Android derlemesi ve gerçek WebView okuması doğrulanmadı. [Kanıtlar ve güncel engeller](../../docs/golgebahcesi-live-audit-2026-10-26.md).

## Neden native adaptör?

Depodaki yeni Keiyoushi kaynakları `KeiSource`, OkHttp 5.4 ve yeni çalışma zamanı bileşenlerini kullanıyor. İncelenen Kitsugi uygulaması OkHttp 4.12 kullanıyor. APK’yı yükleyebilmek, bu farklı ABI’ların her istekte uyumlu çalıştığı anlamına gelmez.

Bu adaptör doğrudan uygulamanın `MangaSource`, `MangaDetails`, `MangaChapter`, `MangaPage` ve `MangaSourceResult` sözleşmelerini kullanır. Yeni manga kaynağı için dinamik `KeiSource`, QuickJS, sıkıştırma kütüphanesi veya ikinci Kotatsu ayrıştırıcısı başlatmaz. Ek Android bağımlılığı: **AndroidX WebKit 1.12.1**.

### Kullanılan API sözleşmesi

Bu yollar 26 Ekim 2026 canlı API ve HTML denetiminde doğrulandı. Güvenli okuyucu yolunun açılması, bölüm piksellerinin alınabildiği anlamına gelmez:

| İşlem | İstek |
|---|---|
| Popülerler | `GET https://api.golgebahcesi.com/api/series?page=1&limit=24&sort=popular` |
| Arama | `GET /api/series?page=1&limit=24&sort=default&search=...` |
| Detay | `GET /api/series/{slug}` |
| Bölümler | `GET /api/series/{slug}/chapters` |
| Bölüm içeriği | `GET /api/chapters/{id}` |
| Güvenli okuyucu | `https://golgebahcesi.com/manga/{seriesSlug}/bolum/{chapterSlug}` |

Sorgu/yol parçaları URL oluşturucu ile kodlanır. Kullanıcı aracısı uygulamanın NetworkHelper/config değeridir; API ve görsellerde kaynak `Referer`/`Origin` korunur. İptal edilen API/görsel isteği alttaki OkHttp çağrısını da iptal eder.

## Uygulama adımları

Kitsugi-Beta’nın yerel kopyasını hazırlayın. Değişiklikleri gözden geçirmek için uygulama çalışma ağacının temiz olması önerilir. Bu deponun kökünde:

```sh
python3 scripts/apply_kitsugi_integration.py /path/to/Kitsugi-Beta --check
python3 scripts/apply_kitsugi_integration.py /path/to/Kitsugi-Beta --apply
```

Araç:

1. Tek kaynak profilini ve yama uyumluluğunu denetler.
2. Var olan farklı içerikteki yeni dosyaları ezmeden durur; symlink ile hedef dışına yazmayı reddeder.
3. Uygulama yamasını uygular.
4. Kotlin kaynaklarını, telefon/TV panelini, EN/TR metinleri, okuyucu JS varlıklarını ve JVM testlerini uygulamaya kopyalar.
5. İkinci çalıştırmada aynı içeriği yeniden yazmaz.

**Dal oluşturmaz/değiştirmez, commit/push yapmaz.** Varsayılan komut yalnızca kontroldür. Elle yalnız `.patch` dosyasını uygulamak yeterli değildir; yeni dosyaların ve varlıkların da kopyalanması gerekir. Bunun için aracı kullanın.

Sonra **uygulama deposunda**:

```sh
./gradlew :app:testDebugUnitTest --tests '*GolgeBahcesiApiTest'
./gradlew :app:assembleDebug
```

Uygulamanın kendi derleme/signing/yerel AAR gereksinimleri ayrıca sağlanmalıdır. Bu araç tüm uygulamanın mevcut derleme sorunlarını çözme veya otomatik yayınlama aracı değildir.

## Tek kaynak modu neyi durduruyor?

- Çalışma anında tek `GolgeBahcesiSource` örneği sunulur.
- Yüklü APK/MEX sınıfları taranıp çalıştırılmaz; eski Gölge Bahçesi APK’sı da ikinci bir kaynak olarak eklenmez.
- Kotatsu başlatılmaz; eski MangaReader tabanlı Gölge Bahçesi dahil bütün parser’lar bu modda pasiftir.
- Kotatsu katalog sorgusu ve APK otomatik güncellemesi atlanır. Eski eklentiler güncelleyici üzerinden yeniden etkinleşmez.
- Telefon/TV eklenti ekranı, eski “1300 aktif kaynak” gibi yanıltıcı yedek sayılar yerine tek kaynak panelini gösterir.
- Tek kaynak hedefi gereği bu modda Türkçe dışındaki manga kaynakları da çalıştırılmaz. Anime/video eklentileri etkilenmez.
- Eski eklenti dosyaları, kaynak kodları, manga eşleştirmeleri ve okuma veritabanı **silinmez**.

Native kaynak APK’nın mevcut paket kimliğini korur. Eski Kotatsu kaynağına bağlı bir manga seçimi otomatik DB göçüyle native kaynağa çevrilmez; gerekirse manga için **Gölge Bahçesi’ni yeniden seçin**. Eski kayıtların silinmemesi, her farklı kaynak kimliğindeki ilerlemenin otomatik taşındığı anlamına gelmez.

İlk native geçişte yalnız bu kaynağın eski `Broken/Disabled/cooldown` teşhisi bir defa temizlenir. Her açılışta sıfırlanmaz ve kaynak test edilmeden `Healthy` işaretlenmez.

## Okuyucu davranışı

### Düz görseller

Sayfalar gerçek `index` sırasına dizilir, uygulama için sıfırdan indekslenir. Göreli HTTPS adresleri kaynak alan adına göre çözülür. Boş/tekrarlanan sayfalar ve yanlış bölüm yanıtları açık hata olur. Görseller kaynağın istemcisi ve bölüm referer’ı üzerinden indirilir; yanıt akış kapandığında kapatılır.

### Güvenli bölümler

`deliverySystem=secure` olan **erişilebilir** bölümler sitenin kendi okuyucusunda açılır. WebView:

- Ana sayfadan önce okuyucu/worker gözlem betiklerini yükler.
- Sadece `https://golgebahcesi.com` ana çerçevesinden gelen köprü mesajlarını kabul eder; wildcard origin veya güvensiz JS-interface yedeği yoktur.
- HTTPS/sertifika kontrollerini kapatmaz; dosya/içerik erişimi ve karışık içerik kapalıdır.
- Site okuyucusunun çözdüğü görselleri almaya çalışır; 26 Ekim canlı testinde bu aşama geçmedi. Ücretli/kilitli bölümün API kilidi önce kontrol edilir; kilit açılmaz.
- Dosyaları bellekte dev bir liste/URL fragment’i yerine uygulama cache dizinine yazar. Bitmap sınırları doğrulanmadan tamamlanmış cache yayınlanmaz.
- API'deki beklenen sayfa sayısı ile cache/yakalama sayısını karşılaştırır; eksik yakalama bitmiş bölüm sayılmaz. Belirsiz eşit boyut eşleşmesinde panelleri tahminle değiştirmez; açık hata/zaman aşımı verir.
- İş iptalinde ve render süreci hatasında dosya yazıcı işleri kapatılır; WebView ana iş parçacığında yok edilir.

Güncel **Android System WebView** gerekir (`DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`). 120 saniyelik yerel okuyucu zaman aşımı açık hata olur; üst işin coroutine iptali normal iptal olarak korunur. Görünür doğrulama 15 saniye sonra hâlâ duruyorsa ayrı doğrulama hatası verilir; sitenin ortamı reddetmesiyle karıştırılmaz. Arka plan WebView'i kullanıcıya etkileşimli doğrulama ekranı sunmaz. Başka tarayıcıda yalnız ana sayfayı açmanın bu bölümün aynı oturumdaki doğrulamasını çözeceği doğrulanmış değildir. Güvenli okuyucu doğrulama atlatma aracı değildir.

Geçici cache sınırları: en fazla 2000 sayfa, tek base64 mesajı 32 MiB, bölüm toplamı 256 MiB; son üç bölüm tutulur. Bu cache, Kitsugi’nin ana görsel cache’inden ayrıdır. Android geçici dosyayı silerse yeniden yakalama internet gerektirir. Tam çevrimdışı okuma desteği bu değişikliğin doğrulanmış bir sonucu değildir.

## Cihaz kabul listesi — tamamlanmadan “çalışıyor” demeyin

1. Yeni APK ile manga kaynak sayısı **1**; yalnızca Gölge Bahçesi. Kotatsu, Tortuga ve eski APK’lar listede ikinci kez görünmüyor.
2. Popülerler sayfa 1/2; kaynakta gerçekten mevcut bir manga ve bir manhwa için Türkçe/özel karakterli arama. Alakasız veya boş hata sonucu başarı gibi gösterilmiyor.
3. Detay: doğru seri URL’si, kapak, açıklama, tür ve durum.
4. Bölümler: doğru serinin tam listesi, yeni→eski sıralama ve küsuratlı bölüm numaraları. Kilitli bölüm boş “başarılı” sonuç değil, açık erişim hatası veriyor.
5. Erişilebilir düz **ve** güvenli bölümde ilk/orta/son görsel gerçek Android bitmap’i olarak açılıyor; paneller doğru sırada. Yalnız URL alınması veya ağ smoke testinin geçmesi yeterli değil.
6. Sayfa kaydırma, bölüm değiştirme, hızlı geri çıkma/iptal ve tekrar açma. Eski bölüm yeni bölümün yerine yazılmıyor; Chromium örnekleri açık kalmıyor.
7. Uygulamayı yeniden başlatma: diğer kaynaklar veya otomatik güncelleyici geri gelmiyor; gerçek hata cooldown’u her açılışta sıfırlanmıyor.
8. Cloudflare/403/429, çevrimdışı bağlantı, cache tahliyesi ve desteklenmeyen görsel biçiminde kullanıcıya anlamlı hata.
9. Telefon/tablet ve TV’de kaynak paneli, sağlık kontrolü ve okuyucu odağı.

## Geri alma

Önce uygulamadaki `git diff` ile değişiklikleri inceleyin. Eski sistemin tamamına dönmek istiyorsanız uygulama yamasını ters uygulayıp aracın eklediği **yeni kaynak/test/varlık dosyalarını** kendi değişikliklerinizden ayırarak kaldırabilirsiniz. Kurulu eklenti arşivleri ve kullanıcı verileri bu entegrasyon tarafından silinmediğinden geri alma için onları yeniden indirmek gerekmez. Eski kaynakları yeniden etkinleştirmek, onların güncel siteyle uyumlu olduğunu doğrulamaz.
