# Manga-TR inceleme / test raporu — 3 Kasım 2026

## İncelenen örnekler
Drive'dan beş metin dosyasının tamamı geçici çalışma alanına indirildi ve HTML yapıları ayrıştırıldı. Bunlar ana sayfa (15), Lady Liar detay (16), Lady Liar bölümler 1 ve 2 (17–18), Chainsaw Man detay (19). Bu set, arama sonucu sayfası, sayfalı seri listesi ve bölüm listesi AJAX yanıtını **içermiyor**. Örnekler oturum/tek kullanımlık anahtarlar içerebildiğinden depoya eklenmedi.

## Bulgular
- Ana sayfada 18 `article.media-card` var; önceki kod `div.media-card` arıyordu. Kod, etiket türünden bağımsız `.media-card` kullanacak şekilde değiştirildi. Bu örnek ana sayfaya aittir; eklentinin kullandığı sayfalı liste yanıtında da işe yaradığı ayrıca doğrulanmalı.
- Her iki detay örneğinde başlık, kapak ve açıklama seçicileri mevcut. Ancak her ikisinde de eklentinin bölüm listesini istemek için aradığı `listKey` yok. Kaydedilmiş HTML ile mevcut kod bölüm listesini başlatamaz. Sunucunun ham cevabında/dinamik JavaScript'te farklı bir durum olup olmadığı ve güncel AJAX sözleşmesi henüz bilinmiyor; bölüm alma düzeltilmiş değildir.
- Detaylarda iki örnekte de durum, yazar ve sanatçı değerleri HTML üzerinden doğrulandı: Lady Liar `Tamamlandı`, `Maripaz Villar`; Chainsaw Man `Devam Ediyor`, `Tatsuki Fujimoto`. Bu kontrol Kotlin kodunu çalıştırmaz.
- Ana sayfanın 18 kartının tamamında eklentinin aradığı başlık bağlantısı, kapak ve rozet sınıfları var. Bu, farklı sayfa olan gerçek keşif/arama DOM doğrulaması değildir.
- Dış `chapter-navbar.js` okuyucu içi bölüm seçicisi için `GET /cek/ajax_chapters_endpoint.php?ajax=chapters` ve `manga`, `page=0/current` veya `offset` kullandığını, `rows` ve `offset` okuduğunu gösteriyor. Bu seçicinin seri detayındaki tüm grupları ve bölümleri sağladığı doğrulanmadı. Web sayfası aracından üç doğrudan endpoint denemesi `Doğrudan erişim yasaktır.` yanıtı verdi; gerçek AJAX başlıkları/oturumuyla yanıt alınamadı. Bu nedenle bu endpoint çözüm olarak uygulanmadı.
- İki okuyucu örneğinde yapılandırma, `/cek/...` biçiminde kök yolu ve farklı çeviriler için ikişer bölüm bağlantısı var. Eski kod, yolun başına ikinci `/` ekliyordu; köke göre çözümleme ve aynı köken kontrolü eklendi.
- Her iki bölüm örneğinde, örnekteki sayfa anahtarı ile resim adresi öznitelikleri bağımsız Python kontrolünde çözüldü: her birinde 17 öznitelik ve 0–16 sıra değerleri bulundu. Bu, görsellerin indirildiği, doğru gösterildiği veya şerit işlemenin çalıştığı anlamına gelmez. Gate yanıtı gerektiğinden tam yapılandırma çözülemedi.
- Bölüm çevirisi seçiminde ayrı URL'ler gözlemlendi, ancak bölüm AJAX cevabı eksik olduğundan bu seçeneklerin listeye nasıl dahil edileceği doğrulanmadı. Mevcut kaynak `scanlator` alanını doldurmuyor.
- Kitsugi-Beta ana dalındaki `MihonSourceWrapper` statik incelemesinde güncelleme, bölüm, resim API'leri ve scanlator aktarımı görüldü. Gerçek kurulum ve sürüm uyumluluğu test edilmedi.

## Çalıştırılan testler
`MANGATR_SNAPSHOT_DIR=google_drive python3 -m unittest discover -s tests -v`: 13 test geçti; bunların dördü yukarıdaki özel örneklerin HTML ve şifrelenmiş öznitelik yapısı içindir, diğerleri katalog/yayın kontrolleridir. Python testleri Kotlin eklentisini çalıştırmaz. Özel HTML dosyaları depoya dahil değildir; ortam değişkeni verilmezse örnek testleri atlanır.

## Yapılamayanlar
Kotlin değişiklikleri derlenmedi; Java/Android SDK mevcut değil ve Android derleme bağımlılıklarına sandbox üzerinden erişim kısıtlı. Yeni APK oluşturulmadı; mevcut 1.6.24 APK bu kod değişikliklerini içermez. Gerçek ağdan AJAX/reader gate/görsel indirme, uygulamada kurulum ve uçtan uca test yapılmadı. Sıfır hata ve tüm manga erişimi garanti edilmez.

## Kullanıcı ekran görüntüleri: KTS-SRC-9003
- Aramada `Chainsaw Man` için 0 sonuç ve `KTS-SRC-9003`, kaynak sağlık ekranında `CaptchaRequired`, uygulama WebView penceresinde Manga-TR ana sayfası görülüyor. WebView ana sayfasının açılması, ayrı HTTP istemcisinin arama isteğinin de aynı yanıtı aldığı anlamına gelmez. Sitede aynı arama web aracıyla kontrol edildi ve Chainsaw Man sonucu görüldü.
- Kitsugi-Beta **main** statik kodunda KTS-SRC-9003 manga captcha kategorisidir. `SourceFailureClassifier` exception adları/mesajlarındaki anahtar sözcükleri sınıflandırır; kod tek başına HTTP yanıtını ya da captcha türünü belirlemez. Ekran görüntüsündeki kurulu uygulamanın aynı kodu kullandığı ayrıca doğrulanmış değildir.
- Main kodunda WebView sistem varsayılan User-Agent'ını kullanırken `NetworkHelper` OkHttp için sabit Chrome/124 User-Agent kullanır. Çerezlerin `onPageFinished` aşamasında `NetworkHelper` cookie jar'ına aktarılması amaçlanmış. UA, TLS/istemci ve çerez ayrışması bot korumasında uyumsuzluk yaratabilir, fakat bu vakadaki neden log/cihaz testi olmadan kanıtlanmış değildir. Başarılı bir WebView görünümü aynı arama URL'sine OkHttp erişimi veya oturum eşleşmesinin kanıtı değildir.
- Kaynak kodundaki DDoSGuardInterceptor her HTTP 403'te DDoS-Guard kontrolüne gider; 403'ün nedeni her zaman DDoS-Guard olmayabilir. Bu potansiyel sorun, kullanıcı cihazında gerçekten çalıştığı veya hatayı oluşturduğu doğrulanmadan kök neden diye ilan edilmemelidir. Yayımlı 1.6.24 APK yerel kaynak değişikliklerini de içermiyor.
- Kullanıcının ana sayfa ve detay örneklerinin ilk 10.240 baytında uygulamanın CloudflareKiller HTML gövde tetikleyicileri bulunmadı; gerçek arama yanıtı ve kurulu sürüm farklı olabilir. Uygulama tarafında UA/çerez ve sınıflandırma için birim + cihaz testi, kaynakta gerçek arama/403 kontrolü ve yeni APK şarttır.
