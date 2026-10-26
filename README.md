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
| Android 10 / API 29 | **Orijinal v2.4.381 yükleyicisiyle 53/53** sayfa pikselleri birebir eşleşti |
| Android 14 / API 34 | **Uygulama yükleyicisi düzeltilmiş CI test kopyasıyla 53/53** sayfa eşleşti; imzalı telefon güncellemesi değildir |

[Canlı tarayıcı kanıtı](docs/audits/mangadenizi-2026-10-26.json). Sitenin “Türkiye'nin en büyük/en popüler kaynağı” olduğu ölçülmüş değildir; katalogda Black Clover, One Piece ve Solo Leveling gibi tanınmış seriler bulunur.

## Android 10 ve Android 14 — aynı paket, farklı uygulama yükleyici durumu

İki cihaz için farklı kaynak seçmek gerekmiyor. Aynı MEX'in görüntü çözümü iki Android sürümünü de hedefliyor; minimum API 26.

Ancak test edilen **Kitsugi v2.4.381 (targetSdk 36)** Android 14+ üzerinde yeni eklenti dosyasını salt okunur yapmadan `PathClassLoader` ile açıyor. Android bunu reddediyor. `setReadOnly()` okuma iznini kaldırmaz. Bu, MangaDenizi görsel API'sinden ayrı bir **uygulama yükleyici hatasıdır**.

- Android 10 için **orijinal APK** ile doğrudan test **geçti**.
- Android 14 için gerçek yayımlanmış APK'nın yalnız yükleyici/tek-TR-kaynak düzeltmesi eklenen **CI test kopyasıyla test geçti**. Başlangıçta yazılabilir MEX'i yükleyicinin kendisi salt okunur yaptı; test MEX'e dışarıdan chmod uygulamadı. Diğer TR kaynağın listeden ve doğrudan erişimden elendiği de sınandı.
- Test kopyası imzasızdır, telefona kurulacak güncelleme diye sunulmaz.
- Android 14'te normal kurulumun tamamlanması için, düzeltmenin **Kitsugi uygulamasına aynı imzalı bir güncelleme ile girmesi gerekir**. Yalnız bu eklenti deposunun değişmesi telefondaki APK'yı değiştirmez.

## Yayımlanmış depo ve paket

**Kitsugi'nin manga eklenti/depo bölümüne eklenecek indeks adresi:**

```text
https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/releases/download/native-mangadenizi-v1/index.json
```

Bu, web sitesinin adresi değil, gerçek **tek girişli V2 eklenti indeksidir**. Görünen **MangaDenizi** paketini Kitsugi içinden yükleyin. İndeksin `resources.apkUrl` alanı, adına rağmen `.apk` değil native `.mex` dosyasına gider; başka Türkçe eklenti yayımlamaz. Daha önce başka depolardan kurulmuş kaynakları yalnız bu indeksin eklenmesi uzaktan kapatmaz.

- [MEX'i doğrudan indir](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/releases/download/native-mangadenizi-v1/com_kitsugi_extension_tr_mangadenizi.mex) · [yayın ve uyumluluk uyarısı](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/releases/tag/native-mangadenizi-v1)
- Aynı paket bu dalda [`packages/mangadenizi.mex`](packages/mangadenizi.mex) olarak da bulunur: **33.218 bayt**. MEX'i Android'in APK yükleyicisinde açmayın.
- [Paket özeti ve SHA-256](docs/mangadenizi-installation.json) · [Android 10 kanıtı](docs/audits/mangadenizi-android-29.json) · [Android 14, düzeltilmiş yükleyici kanıtı](docs/audits/mangadenizi-android-34.json)
- [27 JUnit + iki Android kabul çalıştırması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/40396276343) · [kanıta bağlı yayımlama](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/40397411890)

**Android 10 / test edilen v2.4.381:** mevcut uygulama yükleyicisiyle normal MEX yüklemesi doğrulandı. **Android 14 / orijinal v2.4.381:** bu bağlantının verilmesi tek başına uygulama hatasını düzeltmez. Aynı imzalı Kitsugi güncellemesi bu yayında **yoktur**; Android 14'te "şimdi kur ve çalışır" sonucu verilmez. Bu nedenle yayın ön-sürüm olarak işaretlidir.

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
