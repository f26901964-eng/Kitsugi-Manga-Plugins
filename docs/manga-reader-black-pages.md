# Kitsugi manga okuyucusunda siyah sayfa araştırması

Tarih: 2026-11-05

## Sonuç

Kitsugi-Beta `main` (`ab81c9ef889e9246a5bceca22f2d2a8da6144f53`) üzerinde, MangaTR ve Mangacix'ten bağımsız ortak bir reader hatası kod düzeyinde kanıtlandı:

- Uygulama Coil **3.5.0** ve `coil3.request.ImageRequest` kullanıyor.
- Manga sayfasını çizen Telephoto bağımlılığı ise **`zoomable-image-coil:0.8.0`**; bu artefakt Coil **2.5.0** içindir.
- Telephoto 0.8.0, modeli `model as? coil.request.ImageRequest` ile tanımaya çalışır. Coil 3 request'i bu kontrole uymaz ve Coil 2 request'inin sıradan `data` değeri olur.
- `ZoomableAsyncImage(model: Any?)` tipi bunu derleme zamanında yakalayamaz. İstek başarısız olduğunda Telephoto null painter üretir; uygulamada ayrıca Coil hata listener'ı olmadığı için kullanıcı hata yerine boş/siyah alan görür.

Bu, indirme/cache aşaması `Ready` olsa bile görüntünün çizilememesini açıklayan kesin bir uygulama kusurudur. Kullanıcının cihazındaki bütün siyah sayfaların tek nedeni olduğu ise ancak düzeltmeli APK ile gerçek cihaz testinde kanıtlanabilir.

İkinci bağımsız kusur: reader `customBrightness` için `Color.Black.copy(alpha = 1.0f - customBrightness)` katmanı çizer. DataStore okuma/yazma yolu değeri sınırlamadığından eski veya bozuk bir `0` değeri tam opak siyah katman oluşturur. Bu katman sayfa, yükleme ve hata UI'sının üstünde, reader kontrollerinin altında çizilir; dolayısıyla kontroller görünürken içerik tamamen siyah kalabilir. Varsayılan değer `1.0f`, mevcut slider aralığı `0.1f..1.0f`'dir; sıfır ancak kalıcı/eski/bozuk değerden gelir.

## Kod kanıtları

### Kitsugi-Beta

- Coil 3.5.0: `gradle/libs.versions.toml`.
- Coil 2 Telephoto artefaktı: [`app/build.gradle.kts`](https://github.com/f26901964-eng/Kitsugi-Beta/blob/ab81c9ef889e9246a5bceca22f2d2a8da6144f53/app/build.gradle.kts#L361).
- Coil 3 request ve Coil 2 Telephoto import'u: [`MangaPageItem.kt`](https://github.com/f26901964-eng/Kitsugi-Beta/blob/ab81c9ef889e9246a5bceca22f2d2a8da6144f53/app/src/main/java/com/kitsugi/animelist/ui/screens/manga/components/MangaPageItem.kt).
- Tam opak olabilen karartma katmanı: [`MangaReaderScreen.kt`](https://github.com/f26901964-eng/Kitsugi-Beta/blob/ab81c9ef889e9246a5bceca22f2d2a8da6144f53/app/src/main/java/com/kitsugi/animelist/ui/screens/manga/MangaReaderScreen.kt#L274-L281).
- Sınırsız DataStore okuma/yazma: [`SettingsDataStore.kt`](https://github.com/f26901964-eng/Kitsugi-Beta/blob/ab81c9ef889e9246a5bceca22f2d2a8da6144f53/app/src/main/java/com/kitsugi/animelist/data/settings/SettingsDataStore.kt).

### Telephoto

- [0.8.0 sürüm kataloğu](https://github.com/saket/telephoto/blob/0.8.0/gradle/libs.versions.toml): Coil `2.5.0`.
- [0.8.0 `ZoomableAsyncImage`](https://github.com/saket/telephoto/blob/0.8.0/zoomable-image/coil/src/main/kotlin/me/saket/telephoto/zoomable/coil/ZoomableAsyncImage.kt): `coil.ImageLoader` (Coil 2).
- [0.8.0 `CoilImageSource`](https://github.com/saket/telephoto/blob/0.8.0/zoomable-image/coil/src/main/kotlin/me/saket/telephoto/zoomable/coil/CoilImageSource.kt): yalnız Coil 2 `ImageRequest` nesnesini request olarak tanır.
- [0.14.0 sürüm notu](https://github.com/saket/telephoto/releases/tag/0.14.0): Coil 3 desteği ve `zoomable-image-coil3` artefaktı bu sürümde eklendi.
- [0.14.0 Coil 3 `Coil3ImageSource`](https://github.com/saket/telephoto/blob/0.14.0/zoomable-image/coil3/src/main/kotlin/me/saket/telephoto/zoomable/coil3/Coil3ImageSource.kt): Coil 3 request'ini doğru türde tanır ve request listener'ını korur.
- [Resmî Telephoto dokümanı](https://saket.github.io/telephoto/zoomableimage/): Coil 3 için `zoomable-image-coil3`; hata yakalamak için `ImageRequest.listener`.

### Açık kaynak reader karşılaştırması

- [Neko PR #3484](https://github.com/nekomangaorg/Neko/pull/3484), aynı sessiz hata sınıfını belgeler: indirilen sayfa `Ready` kalırken Coil decode hatası boş sayfa üretir; `ZoomableAsyncImage` hata callback'i sunmadığından request listener ve görünür Retry gerekir.
- [Neko PR #3483](https://github.com/nekomangaorg/Neko/pull/3483), çok uzun sayfalarda texture/canvas sınırının da boş çizim yapabildiğini belgeler. Kitsugi global ImageLoader'da hardware bitmap'i kapattığı için bu ikinci kayıt mevcut ekran görüntüsünün birincil nedeni sayılmamıştır.
- Güncel [Mihon `PagerPageHolder`](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt), `Ready` sonrası `onImageLoadError` ile decode hatasını ayrıca yakalayıp hata layout'u açar; `onImageLoaded` ile loading göstergesini ancak gerçek çizim/decode başarısında kaldırır.
- Güncel [Mihon Coil `ImageDecoder`](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/coil/ImageDecoder.kt), Android sistem decoder'ının açamadığı AVIF/JXL/HEIF/JP2 için ayrı native decoder kullanır. Kitsugi'nin global component listesinde yalnız animated decoder ve OkHttp fetcher vardır; böyle bir genişletilmiş sayfa decoder'ı yoktur.

## Eklenti sorumluluğunun ayrılması

| Yol | MangaTR | Mangacix |
|---|---|---|
| Sayfa URL'si | Reader HTML'sinden/gate sonrası çözümlenir | `/secure/` title cevabındaki `episodeImages` listesinden gelir |
| Görsel request'i | `imageRequest` override edilir | Varsayılan `HttpSource.imageRequest` kullanılır |
| Özel header | Referer, AVIF/WebP Accept ve image fetch metadata'sı | Auth interceptor yalnız `/secure/` API GET isteklerine uygulanır |
| Client/interceptor | Site gate ve parçalı görsel interceptor zinciri | Görselde ortak MangaTR interceptor'ı yoktur |

İki eklentinin ortak bir görsel kimlik doğrulama/header mekanizması yoktur. Bu nedenle ikisindeki aynı boş çizim doğrudan MangaTR header'larına bağlanamaz. Önce ortak Kitsugi reader düzeltilmelidir.

MangaTR'nin AVIF içeren `Accept` başlığı eski Android decoder'ları için ayrı bir uyumluluk riskidir. Ancak güncel `MangaCache`, bounds decode başarısız veriyi `Ready` yapmadan reddeder; AVIF'in bu cihazdaki siyah ekranın nedeni olduğuna dair gerçek yanıt/decode kanıtı yoktur. Mangacix için de canlı görsel response ve Android decode kanıtı alınmadan eklenti hatası ilan edilmemiştir.

“Animecix” adı uygulamada Cloudstream/video tarafına işaret eder; bu çalışmadaki manga karşılaştırması kullanıcının “mangacix” kullanımına uygun olarak `src/tr/mangacix` eklentisidir.

## Hazırlanan uygulama düzeltmesi

Patch: `tools/kitsugi-beta-manga-reader-black-pages.patch`

- Telephoto `zoomable-image-coil3:0.14.0` ve `me.saket.telephoto.zoomable.coil3` import'u.
- Reader dışındaki mevcut Coil 2 `AsyncImage` ekranları için `coil-compose:2.7.0` bağımlılığını açıkça koruma; böylece yalnız reader Coil 3'e taşınır ve geniş kapsamlı UI migrasyonu bu patch'e karışmaz.
- Coil request `listener(onError/onSuccess)` ile display/decode hatasını görünür kırık sayfa ve Retry UI'sına bağlama.
- Retry'de Telephoto composition'ını yeniden oluşturup aynı dosyayı gerçekten yeniden decode etme.
- Decode loglarına URL, response body, cache anahtarı, jeton veya exception mesajı yazmama; yalnız sayfa numarası ve exception sınıfını kaydetme.
- Parlaklığı DataStore okuma ve yazma sırasında `0.1f..1.0f` aralığına alma; NaN/sonsuz değerde `1.0f` varsayılanına dönme.
- Üç birim testi: sıfır/negatif, aralık dışı/pozitif ve non-finite parlaklık değerleri.

Patch tabanı: Kitsugi-Beta `ab81c9ef889e9246a5bceca22f2d2a8da6144f53`.

Patch commit'i: `8d7e548965a9cd0176412c4b4005d3cb3cd298ba`.

Patch SHA-256: `0384d264088d9e2fbbb18ddffdcc2c4be8ee32363fce1a8b25fe89fb001d35fd`.

Kitsugi-Beta deposuna push 403 döndürdüğü için patch bu depoda saklandı; app PR'ı açılamadı. GitHub bağlantısı/yetkisi düzeltildikten sonra patch app deposunda `git am` ile uygulanabilir.

## Doğrulama durumu ve zorunlu cihaz testi

- Coil 3.5.0 `ImageRequest.Builder.listener` imzası ve Telephoto 0.14.0 Coil 3 API'si kaynak kodundan kontrol edildi.
- Patch `git diff --check` benzeri whitespace kontrolünden ve yerel commit kontrolünden geçti.
- Kitsugi-Beta'ya doğrudan push ve Actions dispatch 403 döndürdüğü için bağımsız checkout/patch workflow'u kullanıldı.
- İlk gerçek Gradle derlemesi, reader dışındaki ekranların eski Telephoto'dan gelen transitive Coil 2 bağımlılığına ihtiyaç duyduğunu gösterdi. Güncel patch bu mevcut ekranlar için Coil 2 Compose'u açıkça koruyor; reader yine Coil 3 Telephoto kullanıyor.
- Gerçek cihazda görsel decode/çizim doğrulaması yapılmadı. Bu nedenle “uçtan uca düzeldi” denilemez.
- MangaTR canlı testindeki eski `first image OK` ölçütü yalnız HTTP 200 ve 100 bayttan büyük veri kontrol eder; gerçek Android decode kanıtı değildir.

Patch'li APK'da zorunlu kontroller:

1. Reader parlaklığını yüzde 100'e sıfırla.
2. MangaTR Chainsaw Man Bölüm 1'de birden fazla sayfanın gerçekten çizildiğini doğrula; 52 sayfa sayacını tek başına başarı sayma.
3. Mangacix'te en az bir bölümün ilk ve orta sayfalarını aç.
4. Bir görseli gerçekten bozuk/desteklenmeyen formatta test et; kırık sayfa ve Retry görünür olmalı, sessiz siyah ekran kalmamalı.
5. Hem normal hem uzun/webtoon sayfasında zoom ve kaydırmayı kontrol et.
6. `KitsugiMangaLog` / `GÖRSEL_DECODE` kayıtlarını kontrol et; log paylaşırken URL, anahtar, jeton, imza ve çerezleri çıkar.
