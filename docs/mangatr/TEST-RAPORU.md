# Manga-TR inceleme / test raporu — 5 Kasım 2026

## Kapsam ve sürüm

Bu çalışma Chainsaw Man Bölüm 1'deki ters/birleşik görüntü sorununu hedefler. Kullanıcı ekran görüntüleri tek Android okuyucu sayfasında iki farklı yöndeki görsel parçasının birlikte gösterildiğini ortaya koydu. Yeni kaynak sürümü `1.6.31`, katalog sürüm kodu `106031`, eklenti `versionCode` değeri `31`'dir.

## Canlı reader'da belirlenen yapı

Canlı Chromium run `42082228450` ve önceki DOM tanılaması şunları gösterdi:

- Her reader elemanının şifreli `parts` listesinde bu bölüm için **bir URL** bulunuyor; dolayısıyla `.first()` bu bölümde ikinci bir URL'yi kaybetmiyor.
- Site aynı URL'yi `background-size: 100% 200%` ile **iki ayrı CSS slice** olarak sunuyor.
- Slice sırası ve `scaleX` / `scaleY` / iki eksen flip değerleri sayfa seed'inden üretiliyor.
- Önceki eklenti bu iki slice'ı yeniden birleştirip tek `Page` veriyordu. Yeni sürüm sitenin sunumuna uygun olarak her slice'ı ayrı `Page` ve ayrı JPEG çıktısı yapıyor.

## Uygulanan düzeltme

- Mevcut seed/grid okuyucusunda her hedef slice için `v3-{destination}-{codes}` fragment'ı üretilir.
- Bitmap interceptor yalnız istenen hedefin kaynak strip'ini seçer, gerekli flip'i uygular ve **tek strip yüksekliğinde** görsel döndürür.
- İki slice kullanan 52 reader elemanı uygulamaya 104 bağımsız sayfa olarak gider.
- `v3-` öneki eski birleştirilmiş bitmap önbelleğini ayırır. Eski `v2-` ve legacy şifreli order birleştirme yolu geriye dönük olarak korunur.
- Gate nonce+canvas fingerprint, pointer-delta, config/page-list ve flip üretimi korunur.

## Otomatik test sonuçları

- Kotlin CI run `42082228411`: formatter, JVM sözleşme/fixture testleri ve release derlemesi başarılı.
- Release run `42082228514`: testleri yeniden çalıştırıp `1.6.31 / 106031` APK ve kataloglarını üretti.
- Canlı run `42082228450`: gate+DOM sözleşmesi başarılı; sayfa 1, 2, 3, 27 ve 52'nin her biri için site iki slice, yeni hat iki ayrı çıktı üretti.
- Sentetik dönüşüm örneklerinde her çıktı `800×600`; önceki birleştirilmiş çıktı `800×1200` idi.
- JVM canlı test sözleşmesi chapter-one için 104 ayrı sayfa ve tek-slice decode yolu bekleyecek şekilde güncellendi.
- APK `classes.dex` içinde yeni `v3-` sözleşmesinin bulunduğu doğrulandı.
- Python katalog testleri: 15 test başarılı, özel snapshot gerektiren 4 test skip.

## Bilinen doğrulama sınırı

GitHub Actions runner'ı `image.mngtr.site` isteklerinde CDN `403` alıyor; test proxy'leri de gerçek sayfaları sağlamadı. Bu nedenle gerçek pikseller üzerinde OCR/180° kabulü ve Android cihaz testi tamamlanmış değildir. Sentetik görseller yön kanıtı sayılmaz; raporda `real_images_verified=false` kalır.

Kullanıcı cihazında yeni sürümün her okuyucu sayfasında yalnız bir görsel gösterdiği ve metinlerin düz olduğu ayrıca kontrol edilmelidir.

## Dağıtım durumu

Release run `42082228514`, `tachiyomi-tr.mangatr-v1.6.31.apk` dosyasını ve katalogları çalışma dalına yazdı. `prebuilt/apk`, `apk` ve `builds/apk` kopyaları **100.011 bayt** ve SHA-256 **`e319220ce96d93f9a7799e613426f4fa71e72deb4a9e75aed32c1b5b6ac5bab7`** olarak eşleşti.

PR [#11](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/pull/11) ana dala birleştirildi. `manga-yayin` run `42083252779` ve ana dal derleme run `42083252572` başarılı oldu. Kalıcı `builds` dalındaki iki APK kopyası da yukarıdaki boyut/SHA-256 ile doğrulandı; beş kullanıcı kataloğu `1.6.31 / 106031` ve yeni APK URL'sini gösteriyor.
