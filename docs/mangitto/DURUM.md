# Mangitto 1.6.2 — HTML uyarlaması ve yayın sınırları

## Kanıtlar

Kullanıcının Drive klasöründen indirilen `message (74).txt` ve `message (75).txt`
aynı ana sayfadır. `home.html` bu ana sayfanın, `reader.html` ise
`message (76).txt` (Berserk 0.01) dosyasının değiştirilmemiş kopyasıdır.
Ana sayfa Nuxt referans tablosunda 93 farklı seri bulunur. Okuyucuda aynı
çeviriye ait sıralı 92 gerçek görsel vardır. Ayrı bir seri detay HTML'i yoktur.

## Değişiklikler

- Keşfet/popüler listesi ana sayfanın Nuxt verisinden üretilir; kapak URL'leri
  `coverImage` alanından alınır. Ana sayfa seçkisi sonlu olduğundan sayfalama
  bittiğinde durur; 93 seri sitenin tüm arşivi olarak sunulmaz.
- Ayrı arama HTML sayfası kullanılmaz. Mevcut `/api/manga/search` JSON araması
  tam arşiv için korunur. Filtre listesinin boş olduğu ilk arama sayfasında
  ana sayfanın title/romaji/english/native/slug alanlarından eşleşmeler eklenir.
  Aktif filtreleri delmemek için filtreli aramada bu ekleme yapılmaz.
- HTML, arama çubuğunun JavaScript olay işleyicisini içermiyor; güncel JS paketi
  veya bir HAR olmadan mevcut API'nin çubuğun kullandığı uç nokta olduğu
  doğrulanmış değildir. API hatası eksik ana sayfa sonuçlarıyla gizlenmez.
- Okuyucu önce HTML'deki o seriye/bölüme ait gerçek görselleri, DOM sırasıyla
  alır. Avatar, kapak, başka bölüm ve tekrarlar dışlanır. HTML görsel içermiyorsa
  mevcut upload API'si kullanılır; boş upload listesi açıklayıcı hata verir.
- Bölümler URL'ye göre tekilleştirilir ve sayısal olarak büyükten küçüğe
  sıralanır. Aynı sayfayı döndüren API'nin sonsuz döngüsü engellenir.
- Açıklamadaki HTML etiketleri temizlenir.

## Test / etkinleştirme

Python katalog testleri: 38 test, 12 atlandı; kalan 26 başarılı.
Snapshot incelemesi: 93 farklı seri, Solo Leveling alternatif adı ve Berserk
0.01'in 92 görseli doğrulandı. Kotlin JUnit testleri aynı ham dosyaları kullanır.

## GitHub derlemesi tamamlandı

- Başarılı çalışma: https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42211663263
- Kotlin fixture testleri ve imzalı release APK derlemesi başarılı.
- Sürüm: 1.6.2 / 106002; APK: `tachiyomi-tr.mangitto-v1.6.2.apk`.
- `enabled_plugins.txt` içinde `tr/mangitto` etkin.
- Yeni APK, arşiv ve yayın dizinlerine kopyalandı; registry ve kataloglar
  APK manifestinden okunan sürümle güncellendi.
- Derleme sonrası yerel Python testleri tekrar başarılı (26 geçti, 12 atlandı).
- PR: https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/pull/27

Bu çalışma ortamında Java/Android SDK yok; Kotlin testleri GitHub Actions'ta
çalıştırıldı. Canlı manga/CDN alanlarına yerel ağ erişimi bulunmuyor; canlı
HTTP başarısı, API sözleşmeleri, Cloudflare ve cihaz içi okuma doğrulanmadı.
İlk Actions denemesindeki Android SDK kurulum adımı başarısız oldu; Ubuntu
22.04 imajının hazır SDK'sı kullanılarak ikinci çalışmada derleme tamamlandı.

Kullanıcıya ulaşan `builds` kataloğuna yayın ayrı aşamadır: PR main'e
birleştirildikten sonra mevcut yayın iş akışı devreye girer. Bu dalın kendisi
main/builds'e otomatik birleştirilmez. Uygulamadaki güncelleme, PR birleşip
main → builds yayın işi başarıyla bittikten sonra katalog yenilenince görünür.
