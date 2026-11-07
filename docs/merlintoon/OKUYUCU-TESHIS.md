# MerlinToon: Büyücü Çoban okuyucu teşhisi

Durum: **çözüldüğü doğrulanmadı**. 1.6.11'de okuyucu kodu 1.6.10 ile aynıdır;
sonraki katalog düzenlemesi de okuyucuyu değiştirmemiştir.

## İncelenen çağrı zinciri

Kitsugi-Beta `b78cbc4` kaynak kodunda detay ekranı seçilen kaynaktan bölüm listesini
alır. Okuyucu, aynı kaynağa seçilen bölümle `MangaPageLoaderV2.loadPageList` üzerinden
gider. `MihonSourceWrapper.fetchPageList`, `SChapter` oluşturup
`mihonSource.getPageList` sonucunu `MangaPage` listesine dönüştürür; bir hata olursa
iptal ve hata yeniden fırlatılır. Loader'ın `Kaynak bu bölüm için sayfa döndürmedi`
mesajı, bu çağrı **boş liste döndürdüğünde**, herhangi bir görsel indirilmeye
başlanmadan üretilir.

MerlinToon 1.6.11'de `MerlinScans.pageListParse`, ortak InitManga ayrıştırıcısının
sonucu boşsa boş liste döndürmek yerine `IOException` fırlatır. Bu nedenle incelenen
uygulama ve eklenti sürümleri gerçekten bu yoldan çalışıyorsa aynı boş-liste
mesajı beklenmez. Bu gözlem, cihazda eski bir kaynak nesnesi/APK olması veya farklı
uygulama/yol çalışması ihtimalini düşündürür; **hangisinin geçerli olduğunu
kanıtlamaz**. Eklentinin sayfa listesi oluşup daha sonra görsel indirmesinde hata
alınması ise farklı bir aşamadır.

İlk inceleme `main` üzerindeki `b78cbc4` kaynağındaydı ve onun elle kurma yolunda
bellek önbelleği temizlenmiyordu. **Düzeltme:** yayınlanan v2.4.454 ve v2.4.455
etiketlerindeki kaynak kodu `main` ile aynı değildir; bu yayınların `installMangaApk`
yolu yüklemeden önce `unloadExtension` çağırır. Dolayısıyla önceki tespit bu iki
yayının katalogdan elle kurma yolu için geçerli değildir. Kullanıcının kurulu
uygulama sürümü ve izlediği kurulum yolu bilinmemektedir.

## İstek ve HTML sınırları

Güncel upstream InitManga da REST API'nin bölüm `slug` değerini seri yoluna ekleyip
bölüm HTML'ini okuyarak çalışır. Upstream bölüm isteğinde `Fetch.DOCUMENT` kullanır;
bu depoda `client.get(chapterUrl)` HttpSource başlıklarıyla çalışır. Sunucunun farklı
bir yanıt verdiğine veya bu farkın bildirilen hata olduğuna dair kanıt yoktur.

Drive örnekleri yalnızca Tembel Soylunun Değişimi Bölüm 1 okuyucu HTML'ini içerir.
O örnekte 42 görsel adresinin çıkarılması JVM üzerinde test edilmiştir; Büyücü
Çoban, canlı HTTP, görsel indirme veya APK/Android test edilmiş değildir. Büyücü
Çoban Bölüm 1'e GitHub Actions isteği Cloudflare HTTP 403, web sayfası getirme
isteği HTTP 500 döndürdü.

## Eksik kanıt

- Büyücü Çoban Bölüm 1'in tarayıcıda site açıldığında alınan **tam HTML kaynak
  kodu**; mevcut Drive klasörüne eklenebilir. Mümkünse oturum açılmamış bir sekmede
  alın; çerezleri, şifreleri ve kişisel erişim belirteçlerini paylaşmayın.
- Hata yeniden oluştuktan sonra görünen tam mesaj/ekran görüntüsü ve uygulamada
  kurulu MerlinToon sürümü. Bu, boş liste ile görsel indirme hatasını ve gerçekten
  hangi APK'nın kullanıldığını ayırmak için gereklidir.

Bu kanıtlar olmadan yeni bir sürümün bu hatayı düzelttiğini söylemek veya tekrar
varsayıma dayalı yayın yapmak mümkün değildir.

## Gerçek tarayıcı denemesi

GitHub Actions üzerinde TLS profilleri Chrome/Safari/Firefox ve gerçek Chromium/Firefox
pencereleri ayrı ayrı denendi. Beşi de HTTP 403 Cloudflare challenge döndürdü; test
sunucu yanıtını alamadı ve başarısız olarak işaretlendi. Bu bir görsel/okuyucu testi
veya Cloudflare geçişi değildir. Kullanıcının ağından sonuç farklı olabilir.
