# MangaTR — test raporu

## Kullanıcı sonucu

`1.6.33 / 106033` kalıcı olarak yayınlandı ve gate/config aşamasını geçti; ancak kullanıcının `image-13.png`–`image-17.png` kanıtları aynı kaynağın iki strip'ini ayrı okuyucu sayfaları olarak gösterdi. Yarım/kesik sayfalar nedeniyle bu sürüm başarısız kabul edilir. Son `image-18.png`–`image-20.png` örnekleri ise sitenin kendi reader'ının tam ve doğru görseller gösterdiğini doğrular; hedef sitenin bu sonucudur.

Yeni aday `1.6.34 / 106034` için gerçek cihaz kabulü henüz yoktur.

## Düzeltilen teknik model

- Eski current `v3-{destination}-{codes}` modeli her destination için ayrı `Page` ve 20×40 örneğinde iki ayrı 20×20 bitmap üretiyordu. Bu artık current üretim yolu değildir.
- Current `v4-{codes}` modeli her kaynak URL/reader elemanı için tek `Page` üretir.
- Interceptor bütün source strip'leri okur, her strip'i kendi yerel koordinatlarında yatay/dikey çevirir ve destination sırasına göre tek bitmap'e yerleştirir.
- Output `source.width × source.height` olur. `source.height % grid != 0` olduğunda son strip kaynak sonuna kadar alınır; satır kaybı olmaz.
- Current v4 ve geriye dönük v3 isteklerinde `FORCE_NETWORK` kullanılır; malformed order veya decode hatasında ham karışık bitmap başarılı cevap olarak dönmez.
- Dönüştürülmüş cevabın eski `Content-Length` ve `Content-Encoding` header'ları temizlenir.

## Beklenen canlı sayfa sayıları

| Reader | Bölüm | Kaynak eleman | Beklenen tam Page |
|---|---:|---:|---:|
| `125451` | 1 | 52 | **52** |
| `125989` | 2 | 26 | **26** |

Her iki reader için daha önce gate/config teknik doğrulaması başarılıydı: `ease=mTr_v4`, canlı jQuery `flow=84`, CSS fallback `20`; gate POST alanları `n/cv`. Bu sonuç yalnız gate/config içindir, bitmap doğruluğu anlamına gelmez.

## Yeni testler

### Kotlin / Robolectric

- Current fragment `v4-300,1` korunur ve request `no-cache` olur.
- İki 20×20 strip içeren 20×40 kaynak için current cevap **tek 20×40 bitmap** olur.
- 20×41 kaynak için current cevap **20×41** olur; tek sayılı yükseklikte son satır kaybolmaz.
- Geçersiz `v4-0,2` ham kaynağa dönmek yerine `IOException` üretir.
- Eski v3 slice istekleri yalnız geriye dönük oturum uyumluluğu için test edilir; current Page üretiminde kullanılmaz.
- Reader contract iki kaynak eleman için 4 slice Page yerine **2 full Page** ve `v4-300,1`, `v4-201,200` fragment'ları üretir.

`MangaTRImagePipelineTest` ayrıca `@GraphicsMode(NATIVE)` ile çalışır. Dört renkli 20×40 kaynak üzerinde production interceptor çıktısının gerçek pikselleri okunur; yatay flip, dikey flip, 180° flip, destination sırası ve iki strip'in tek 20×40 çıktıya yerleşimi ayrı ayrı doğrulanır. CI run `42118286341` bu testlerle başarılıdır.

### Browser / sentetik piksel

`tools/mangatr_browser_orientation.py` önce küçük etiketli bitmap'lerde şu kontrolleri yapar:

- destination sırası;
- yatay flip;
- dikey flip;
- 180° flip;
- 4×5 tek sayılı yükseklikte bütün satır/sütunların korunması.

Ardından canlı DOM'daki `data-reader-slice`, `background-position` ve `transform` değerlerini production seed kodlarıyla karşılaştırır. Her örnek için tek çıktı ve `source_dimensions == output_dimensions` zorunludur. Rapor `full_page_contract_passed`, 52/26 sayfa sayıları ve gate/DOM sonucu birlikte başarılı olmadan contract geçmez.

CDN engeli nedeniyle sentetik kaynak kullanılırsa `real_images_verified=false` kalır. Sentetik piksel testi algoritmayı doğrular; gerçek CDN görüntüsü veya kullanıcı cihazı kabulü değildir.

## Yerel kontroller — 6 Kasım 2026

- Python katalog/test paketi: **15 test başarılı, 4 private snapshot testi skip**.
- Etiketli Pillow piksel sözleşmesi: sıra, yatay/dikey/180° flip ve tek sayılı yükseklik kontrolleri başarılı.
- Python syntax ve üç MangaTR workflow YAML/branch kontrolü başarılı.
- `git diff --check` temiz.
- Yerel Java/Gradle bulunmadığından Kotlin derleme ve Robolectric doğrulaması CI'da yapıldı; sonuçlar aşağıdadır.

## CI ve branch yayın sonuçları

- İlk Kotlin/test/APK run `42115170299`: **success**.
- Native production output pixel testlerini içeren run `42118286341`: **success**.
- Live Chromium + production Kotlin fixture run `42117796891`: **success**; `full_page_contract_passed=true`, Bölüm 1 `52`, Bölüm 2 `26`, DOM sıra/flip eşleşmesi ve kaynakla aynı output boyutları doğrulandı.
- Branch release run `42115170298`: **success**. Üç `1.6.34` APK kopyası **100.971 bayt**, SHA-256 **`065d68642bed39db94be33a214532d263d6d3636805cca40d60504b5cae986ec`**; kataloglar `1.6.34 / 106034`.
- Live runner gerçek CDN görsellerinde 403 aldı; `real_images_verified=false`. Native/sentetik piksel testleri gerçek CDN veya cihaz kabulü değildir.
- İlk iki live workflow yalnız sanitized diagnostics'i bot commit'iyle kaydetme adımında Git `128` verdi. Diagnostics artık read-only artifact olarak yükleniyor; başarılı run `42117796891` bu sorunun da çözüldüğünü doğrular.

## Henüz kanıtlanmayanlar

- PR'ın main'e alınması ve kalıcı `builds` yayınının aynı APK'yı içermesi.
- Kullanıcının cihazında Bölüm 1 ve 2'de tam görsel, doğru sıra ve doğru yön.

Kullanıcı cihazı kabulü olmadan ürün düzeltmesi kesinleşmiş sayılmaz.
