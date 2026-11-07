# MerlinToon — 1.6.9 (106009)

`tr/merlinscans` bu çalışma dalının etkin listesine ve kataloglarına eklendi. Paket kimliği
`eu.kanade.tachiyomi.extension.tr.merlinscans` ve eski DSL adından hesaplanan kaynak kimliği açıkça sabitlenmiştir; katalog adı
MerlinToon'dur. APK `apk/tachiyomi-tr.merlinscans-v1.6.9.apk` dosyasıdır. Ana dağıtımın
`builds` dalındaki katalog otomatik olarak bu çalışma dalını okumaz: main'e alınıp katalog
yayın iş akışı çalışmalıdır. Bu çalışma hiçbir cihazda kurulum yapmadı.

## Yapılanlar

6 Kasım 2026 tarihli Drive HTML örnekleri (ana sayfa, `tembel` arama, Tembel Soylunun
Değişimi seri detayı ve Bölüm 1) incelendi. Arama, sitenin `/search/{terim}/` sayfasındaki
`article` sonuçlarını okur; `/seri-oner/` gibi seri olmayan önerileri ve yan paneli dışlar.
Boş arama ve filtreler ortak InitManga kodundadır. Popüler liste `/seri/` arşivini kullanır.
Seri özetini/durumunu ve bölüm API'sini ortak tema okur, kapak `og:image` ile tamamlanır;
tür listesine yalnız `/tur/` bağlantıları dahil edilir. Seri yükleyicisi yazar veya çevirmen
olarak varsayılmaz. Bölüm API'si mevcut kodda çevirmen alanı sunmadığından burada çevirmen
bilgisi doğrulanmış değildir.

Örnek okuyucu `#chapter-content` içinde ilk görselin `src`, sonraki dilimlerin
`data-original-src` adresini sunar. Dilimler HTML sırasıyla ayrı sayfalar olarak korunur;
`data:` placeholder'ları atlanır. Bilinen kilitli bölümlerde ve şifreli okuyucularda ortak
InitManga davranışı korunur; giriş veya ücret gerektiren içeriğe erişim garanti edilmez.

## Doğrulama ve sınırlar

- [GitHub Actions derleme/test çalışması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42314400910) Kotlin/JUnit testlerini, release APK derlemesini ve katalog testlerini geçirdi. Test HTML'leri küçük, örneklerden türetilmiş parçalar; bütün Drive belgeleri veya Android üzerinde gerçek cihaz testi değildir.
- Yerel `python3 -m unittest discover -s tests`: 64 test, 12 atlandı.
- [Canlı ağ kontrolü](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42314139686): arşiv, arama, seri detayı, bölüm API'si ve okuyucu GitHub runner'ına HTTP 403 döndürdü. Dolayısıyla canlı arama, tam bölüm listesi ve resim indirme doğrulanamadı. Kullanıcının ağında ve uygulamanın Cloudflare oturumunda sonuç farklı olabilir; çalışma garantisi verilmez.
- Bölüm API'si, farklı seri/arama sayfaları ve sonraki sayfalar gerçek sunucuda ayrıca sınanmalı. Eski arşivdeki 1.6.4 APK ile yeni 1.6.9 APK v2 sertifikaları karşılaştırıldı ve farklı bulundu. Eski sürüm cihazda kuruluysa Android 1.6.9'u üzerine güncelleme olarak kabul etmez. Sertifika SHA-256 değerleri sırasıyla `a53115764ff5e259e0e5acec3c58a85166e88aad110cd8a20674eefea5d6c016` ve `a12ba4b532bd7fa9c6f2e7eb05c841bca6560ffa871252e915ba72c429fe9dba`. Android/Kitsugi kurulumu ayrıca doğrulanmadı. Eski sürümü kaldırmak yerel verileri etkileyebilir; önce uygun yedekleme yapılmalı.

### 1.6.10 okuyucu düzeltmesi

- InitManga'nın bilinen korumalı `canvas[data-enc]` okuyucu biçimi ve normal/lazy görseller DOM sırasıyla okunur; şifrelenmiş içerikte de aynı ayrıştırıcı kullanılır. İlgisiz base64 scriptleri artık bölüm verisi sanılmaz.
- Testler bu biçimleri sentetik örneklerle doğrular. Büyücü Çoban Bölüm 1'in HTML'i veya görselleri bu ortamda doğrulanamadı: GitHub Actions isteği Cloudflare HTTP 403 döndürdü. Bu nedenle ilgili bölümdeki hatanın kesin nedeninin bu olduğu veya cihazda çözüldüğü henüz doğrulanmış değildir.
- 1.6.10 APK imza sertifikası, CI'da 1.6.9 ile karşılaştırılarak eşleşti.

### 7 Kasım: tekrar inceleme (henüz düzeltme doğrulanmadı)

- Drive'daki tam **Tembel Soylunun Değişimi Bölüm 1** HTML'i (Büyücü Çoban değil), geçici dosya olarak test ortamına alınıp 1.6.10 kaynak kodundaki `MerlinScans.pageListParse` metoduna verildi. Okuyucudaki 42 `img` öğesinin 42 adresi, HTML sırasıyla ve placeholder olmadan döndü. Test, Android'in HttpSource stub kurucusunu atlar; ağ, APK yükleme, görsel indirme veya gerçek cihaz testi değildir. [Test çalışması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42317036936). Kaynak HTML depoya eklenmedi; otomatik tekrarda `MERLIN_FIXTURE_URL` sırrı veya yerel `MERLIN_REAL_READER_HTML` dosyası gerekir.
- Kitsugi-Beta'nın incelenen [b78cbc4](https://github.com/f26901964-eng/Kitsugi-Beta/tree/b78cbc4cdc39808cb0ec475026edef6b464b71ed) sürümünde, [elle kurma/güncelleme](https://github.com/f26901964-eng/Kitsugi-Beta/blob/b78cbc4cdc39808cb0ec475026edef6b464b71ed/app/src/main/java/com/kitsugi/animelist/ui/app/MangaViewModel.kt#L270-L287) dosyayı değiştirip `loadExtension` çağırıyor, ancak [APK yükleyicisi](https://github.com/f26901964-eng/Kitsugi-Beta/blob/b78cbc4cdc39808cb0ec475026edef6b464b71ed/app/src/main/java/com/kitsugi/animelist/data/manga/MangaExtensionLoader.kt#L206-L208) bellekte önceden yüklenmiş örneği döndürüyor. Bu yolda eski eklenti uygulama yeniden başlatılana kadar kullanılabilir. Otomatik güncelleme yolu farklıdır: yüklemeden önce `unloadExtension` çağırır. Bu, kullanıcının cihazında kesin neden olarak doğrulanmış değildir.
- Bildirilen Büyücü Çoban bölümünün gerçek HTML'i ve kullanıcının cihazında çalışan APK sürümü hâlâ bilinmiyor. HTTP 403 nedeniyle yeni bir kaynak kodu değişikliği veya sürüm yayınlayarak bu bölümün düzeldiğini iddia etmek mümkün değil.

### 1.6.11 yeniden derleme

Kullanıcının uygulamadan güncelleyip yeniden deneyebilmesi için 1.6.10'daki aynı okuyucu
mantığı sürüm artırılarak yeniden derlenmiştir. Bu sürüm Büyücü Çoban hatasına yönelik yeni
bir okuyucu düzeltmesi içermez; söz konusu bölümün HTML'i ve cihazdaki sonuç doğrulanmamıştır.
