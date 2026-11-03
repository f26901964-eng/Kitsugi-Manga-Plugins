# Manga-TR görev planı — 3 Kasım 2026

## Yapılanlar
- Drive'daki beş HTML örneği bütünüyle indirildi; HTML yapıları incelendi. Detaylar `TEST-RAPORU.md` dosyasında.
- Kaynakta genel kart seçicisi, okuyucu gate adresinin köke göre çözülmesi ve aynı köken kontrolü, tekrarlayan bölüm sayfası koruması, eksik bölüm adı ve görsel hata kontrolleri eklendi.
- HTML örneklerini ve katalogları kontrol eden Python testleri yazıldı. Eklentinin Android çalışma zamanını test etmezler.

## Öncelikli açık işler
- [ ] Bölüm listesi: verilen Lady Liar ve Chainsaw Man detay HTML'lerinde mevcut kodun beklediği `listKey` bulunmuyor. Ham ağ cevabı, sayfanın dış JavaScript'i ve bölüm listesini yükleyen HTTP istek/yanıtları incelenip gerçek sözleşme uygulanmalı. Her iki grup ayrı URL ve `scanlator` ile korunmalı.
- [ ] Keşif ve arama için eklentinin gerçekten çağırdığı `manga-list-sayfala.html` ve `arama.html` sonuç HTML'lerini ve sayfalamasını kontrol et. Ana sayfa örneği bunların yerine geçmez.
- [ ] Gate isteğini ve yanıtını gerçek oturumla doğrula; resimleri indir, sayfa sırasını ve karıştırılmış resim şeritlerini test et. Giriş/bot koruması, kapak, önbellek ve hata durumlarını kontrol et.
- [ ] JDK, Android SDK ve Gradle bağımlılıkları olan ortamda eklentiyi derle/test et; sürüm ve imzalama uyumunu doğrula. Yeni APK'yı uygun sürümle üret; yalnızca Kotlin değişikliği mevcut APK'yı değiştirmez.
- [ ] Kitsugi-Beta'nın gerçek derlemesinde eklenti kurulumu, arama, detay, bölüm ve farklı çeviri seçimi, görsel sırası ve önbelleği uçtan uca test et; gerekiyorsa uygulama deposunda düzelt.
- [ ] Katalog ve yeni APK'yı dağıtım dalına yayımla; çalışma dalı değişiklikleri otomatik yayınlanmaz.

## Sınırlar
Doğrudan manga-tr.com HTTP erişimi ve Android/JDK derleme ortamı sandbox'ta yok. Sayfa aracıyla HTML'in okunabilir metni ve dış JS'in bir kısmı görülebildi, ama oturumlu POST, gerçek görüntü ve cihaz testleri yapılamadı. Kaydedilmiş HTML geçici token barındırabilir: Git'e veya herkese açık rapora eklenmemeli.
