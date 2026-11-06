# MerlinToon — 1.6.8 (106008)

`tr/merlinscans` bu çalışma dalının etkin listesine ve kataloglarına eklendi. Paket kimliği
`eu.kanade.tachiyomi.extension.tr.merlinscans` ve eski kaynak kimliği korunur; katalog adı
MerlinToon'dur. APK `apk/tachiyomi-tr.merlinscans-v1.6.8.apk` dosyasıdır. Ana dağıtımın
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

- [GitHub Actions derleme/test çalışması](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42313892142) Kotlin/JUnit testlerini, release APK derlemesini ve katalog testlerini geçirdi. Test HTML'leri küçük, örneklerden türetilmiş parçalar; bütün Drive belgeleri veya Android üzerinde gerçek cihaz testi değildir.
- Yerel `python3 -m unittest discover -s tests`: 64 test, 12 atlandı.
- [Canlı ağ kontrolü](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42314139686): arşiv, arama, seri detayı, bölüm API'si ve okuyucu GitHub runner'ına HTTP 403 döndürdü. Dolayısıyla canlı arama, tam bölüm listesi ve resim indirme doğrulanamadı. Kullanıcının ağında ve uygulamanın Cloudflare oturumunda sonuç farklı olabilir; çalışma garantisi verilmez.
- Bölüm API'si, farklı seri/arama sayfaları ve sonraki sayfalar gerçek sunucuda ayrıca sınanmalı. APK'nın eski arşiv sürümüyle imza uyumluluğu ve Android/Kitsugi kurulumu doğrulanmadı; farklı imzalı eski kurulum güncellemeyi reddedebilir. Kaldırma işlemi yerel verileri etkileyebilir.
