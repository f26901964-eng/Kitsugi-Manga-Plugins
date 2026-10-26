# Kitsugi Manga Plugins — MangaDenizi tek kaynak profili

**Aktif kaynak: MangaDenizi.** Gölge Bahçesi okuma testinde 0/18 görsel verdiği için seçili kaynak olmaktan çıkarıldı. Diğer Türkçe kaynakların kodu silinmez; bu deponun izin listesinde yalnız `tr:mangadenizi` bulunur, Kotatsu parser'ları kapalıdır.

## Resimler gerçekten alındı mı?

**Evet.** 26 Ekim 2026'da canlı API ve Nuxt okuyucusu incelendi:

| Kontrol | Gerçek sonuç |
|---|---|
| Katalog | 110 seri; manga/manhwa/webtoon içeriyor |
| Black Clover | 393 bölüm kaydı; 392. bölümün **20/20** görseli indirildi ve birleştirildi |
| Shadow Slave | 7 bölüm; 07. bölümün **33/33** görseli indirildi ve birleştirildi |
| Sitenin kendi okuyucusuyla karşılaştırma | Her iki örnek bölümün ilk sayfasında **sıfır piksel farkı** |
| Native MEX | Host API sınıflarını paketlemeden, gerçek Kitsugi `MangaSource` arayüzüyle derlendi |
| İlk Android kontrolü | Android 11: orijinal Kitsugi APK'sı ile **53/53** sayfa pikselleri birebir eşleşti |
| Android 15 ilk kontrolü | Görseller **53/53** eşleşti, ama yalnız dosyayı salt okunur yaptıktan sonra; normal kurulum testi başarısız olarak kaydedildi |

[Canlı tarayıcı kanıtı](docs/audits/mangadenizi-2026-10-26.json). Sitenin “Türkiye'nin en büyük/en popüler kaynağı” olduğu ölçülmüş değildir; katalogda Black Clover, One Piece ve Solo Leveling gibi tanınmış seriler bulunur.

## Android 10 ve Android 14 — aynı paket, farklı uygulama yükleyici durumu

İki cihaz için farklı kaynak seçmek gerekmiyor. Aynı MEX'in görüntü çözümü iki Android sürümünü de hedefliyor; minimum API 26.

Ancak test edilen **Kitsugi v2.4.381 (targetSdk 36)** Android 14+ üzerinde yeni eklenti dosyasını salt okunur yapmadan `PathClassLoader` ile açıyor. Android bunu reddediyor. `setReadOnly()` okuma iznini kaldırmaz. Bu, MangaDenizi görsel API'sinden ayrı bir **uygulama yükleyici hatasıdır**.

- Android 10 için **orijinal APK** ile doğrudan test yürütülür.
- Android 14 için gerçek yayımlanmış APK'nın yalnız yükleyici/tek-TR-kaynak düzeltmesi eklenen **CI test kopyası** kullanılır; dışarıdan MEX dosyasına chmod yapılarak başarı uydurulmaz.
- Test kopyası imzasızdır, telefona kurulacak güncelleme diye sunulmaz.
- Android 14'te normal kurulumun tamamlanması için, düzeltmenin **Kitsugi uygulamasına aynı imzalı bir güncelleme ile girmesi gerekir**. Yalnız bu eklenti deposunun değişmesi telefondaki APK'yı değiştirmez.

**Yayın kontrolü sürüyor. Kurulabilir MEX ve tek girişli depo adresi, Android 10/14 testlerinin gerçek kapsamıyla birlikte yayımlanacak. Android 14 için imzalı uygulama güncellemesi henüz yoktur.**

## Uygulama düzeltmesi

[`integration/kitsugi-mex/loader-readonly.patch`](integration/kitsugi-mex/loader-readonly.patch), pinlenmiş Kitsugi-Beta kaynağına:

1. APK ve MEX sınıf yükleyicilerine salt-okunur kod dosyası hazırlığını;
2. yalnız native MangaDenizi'yi Türkçe kaynak olarak etkin bırakan geçici seçim politikasını ekler.

Diğer diller, eklenti dosyaları, manga eşleştirmeleri ve okuma ilerlemesi silinmez. Bu uygulama politikası **uygulamaya alınmadan**, eklenti paketinin diğer depolardan daha önce kurulmuş kaynakları kendiliğinden kapattığı iddia edilmez.

[Uygulama düzeltmesinin sınırları ve test yöntemi](integration/kitsugi-mex/README.md).

> **Eski Gölge uygulama yamasını uygulamayın.** `integration/kitsugi/single-source.patch` harici MEX/APK yüklemesini kapatır; yeni paketle çakışır. Tarihsel kod ve regresyon testleri korunur, kurucu araç aktif profilde eski yamayı reddeder.

## Paket mimarisi ve testler

- [`extensions/mangadenizi`](extensions/mangadenizi): yalnız eklenti kodunu içeren `classes.dex` + `manifest.json` + lisans/notis ZIP'i. Modern `KeiSource`/OkHttp 5 ABI'sını zorunlu kılmaz; WebView veya CAPTCHA çözme yolu kullanmaz.
- Katalog, arama, doğru seri/kapak, tam bölüm listesi, bölüm aidiyeti ve sayfa sayıları doğrulanır. Eksik/tekrarlanan sayfa veya bilinmeyen görsel düzeni sessiz boş başarı olmaz.
- `tiled-v1` parçaları unsigned 32-bit seed ve eşit olmayan kenar boyutları korunarak Android Canvas ile birleştirilir; çıktı PNG'dir.
- TLS denetimi kapatılmaz. İptal edilen iş OkHttp çağrısını iptal eder. Geçici aktarım hataları sınırlı tekrar edilir; 403 gibi erişim hataları aşılmaz.
- Bitmap işleme seri ve boyut sınırlıdır; host ön yüklemesi birkaç büyük bitmap'i aynı anda biriktirmez.
- [Native derleme + Android 10/14 kabul iş akışı](.github/workflows/build-mangadenizi.yml): 27 gerçek API JUnit testi ve bütün örnek sayfaların Android piksel karşılaştırması.
- [Çevrimdışı profiller/tarihsel regresyonlar](.github/workflows/verify.yml): Android okuma testinin yerine geçmez.

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
node --test tests/*.test.mjs
```

Hiçbir korumalı/ücretli bölüm, CAPTCHA veya site erişim kısıtı aşılmaz. Test sonucu gözlenen örnekler ve tarih içindir; bütün eserlerin her bölümünün sonsuza kadar sorunsuz çalışacağı garantisi değildir.
