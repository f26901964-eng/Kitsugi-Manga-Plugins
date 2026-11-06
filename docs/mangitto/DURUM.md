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
Bu çalışma ortamında Java/Android SDK yok; Kotlin testleri ve yeni APK burada
çalıştırılmadı. Canlı manga/CDN alanlarına ağ erişimi de bulunmuyor; HTTP
başarısı, API sözleşmeleri, Cloudflare ve cihaz içi okuma doğrulanmadı.

Eski 1.6.1 APK yeni kodu içermez. Bu yüzden eski APK'yı etkinleştirip düzeltme
bitmiş gibi göstermiyoruz. `.github/workflows/mangitto-release.yml`, ilgili
Arena dalına push olduğunda (veya workflow_dispatch ile) modülü seçer, Kotlin
testlerini çalıştırır, imzalı 1.6.2 APK'yı derler; ancak başarılı olduktan sonra
katalogları yeni APK'dan üretip aynı dala kaydeder. Derleme başarısızsa eski
APK'nın etkinleştirildiği bir commit gönderilmez. Otomasyon henüz çalışmadan
`enabled_plugins.txt` içindeki yorum gerçek etkinleştirme değildir.

Kullanıcıya ulaşan `builds` kataloğuna yayın ayrı aşamadır: doğrulanmış APK ve
katalog değişiklikleri main'e alındıktan sonra mevcut yayın iş akışı devreye
girer. Bu dalın kendisi main/builds'e otomatik birleştirilmez.
