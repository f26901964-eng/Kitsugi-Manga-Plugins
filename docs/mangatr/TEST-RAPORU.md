# Manga-TR inceleme / test raporu — 3 Kasım 2026

## İncelenen örnekler
Drive'dan beş metin dosyasının tamamı geçici çalışma alanına indirildi ve HTML yapıları ayrıştırıldı. Bunlar ana sayfa (15), Lady Liar detay (16), Lady Liar bölümler 1 ve 2 (17–18), Chainsaw Man detay (19). Bu set, arama sonucu sayfası, sayfalı seri listesi ve bölüm listesi AJAX yanıtını **içermiyor**. Örnekler oturum/tek kullanımlık anahtarlar içerebildiğinden depoya eklenmedi.

## Bulgular
- Ana sayfada 18 `article.media-card` var; önceki kod `div.media-card` arıyordu. Kod, etiket türünden bağımsız `.media-card` kullanacak şekilde değiştirildi. Bu örnek ana sayfaya aittir; eklentinin kullandığı sayfalı liste yanıtında da işe yaradığı ayrıca doğrulanmalı.
- Her iki detay örneğinde başlık, kapak ve açıklama seçicileri mevcut. Ancak her ikisinde de eklentinin bölüm listesini istemek için aradığı `listKey` yok. Kaydedilmiş HTML ile mevcut kod bölüm listesini başlatamaz. Sunucunun ham cevabında/dinamik JavaScript'te farklı bir durum olup olmadığı ve güncel AJAX sözleşmesi henüz bilinmiyor; bölüm alma düzeltilmiş değildir.
- İki okuyucu örneğinde yapılandırma, `/cek/...` biçiminde kök yolu ve farklı çeviriler için ikişer bölüm bağlantısı var. Eski kod, yolun başına ikinci `/` ekliyordu; köke göre çözümleme ve aynı köken kontrolü eklendi.
- Her iki bölüm örneğinde, örnekteki sayfa anahtarı ile resim adresi öznitelikleri bağımsız Python kontrolünde çözüldü: her birinde 17 öznitelik ve 0–16 sıra değerleri bulundu. Bu, görsellerin indirildiği, doğru gösterildiği veya şerit işlemenin çalıştığı anlamına gelmez. Gate yanıtı gerektiğinden tam yapılandırma çözülemedi.
- Bölüm çevirisi seçiminde ayrı URL'ler gözlemlendi, ancak bölüm AJAX cevabı eksik olduğundan bu seçeneklerin listeye nasıl dahil edileceği doğrulanmadı. Mevcut kaynak `scanlator` alanını doldurmuyor.
- Kitsugi-Beta ana dalındaki `MihonSourceWrapper` statik incelemesinde güncelleme, bölüm, resim API'leri ve scanlator aktarımı görüldü. Gerçek kurulum ve sürüm uyumluluğu test edilmedi.

## Çalıştırılan testler
`MANGATR_SNAPSHOT_DIR=google_drive python3 -m unittest discover -s tests -v`: 4 test geçti. Biri katalog tutarlılığı, üçü yukarıdaki örneklerin HTML ve şifrelenmiş öznitelik yapısı içindir. Python testleri Kotlin eklentisini çalıştırmaz. Özel HTML dosyaları depoya dahil değildir; ortam değişkeni verilmezse örnek testleri atlanır.

## Yapılamayanlar
Kotlin değişiklikleri derlenmedi; Java/Android SDK mevcut değil ve Android derleme bağımlılıklarına sandbox üzerinden erişim kısıtlı. Yeni APK oluşturulmadı; mevcut 1.6.24 APK bu kod değişikliklerini içermez. Gerçek ağdan AJAX/reader gate/görsel indirme, uygulamada kurulum ve uçtan uca test yapılmadı. Sıfır hata ve tüm manga erişimi garanti edilmez.
