# SadScans HTML incelemesi — 2026-11-06

## İncelenen kaynaklar

Drive klasörü: `15IQuEOebDt9w82wTgOlBM4IkPbs3zg_F`.
Yerel kopyalar: `google_drive/message (77).txt` (ana sayfa), `(78)` (/seriler), `(79)` (Chainsaw Man detay), `(80)` (Chainsaw Man okuyucu).

HTML içindeki `self.__next_f.push(...)` kayıtları JSON olarak açılarak incelendi. Ana sayfa RSC verisinde `initialData` ve sekiz `series_sef_link` alanı var; /seriler dökümünde `initialData` yok. Dolayısıyla /seriler HTML'inden doğrudan tüm listeyi çıkarabileceğimiz iddiası doğrulanmadı.

## HTML'nin işaret ettiği canlı JS

- `1vy0fm3wxax8_.js`: `_s4d7g2k9m0.getSeries.useQuery({search,type,status,sortBy,turIds,page,limit:15})`; liste doğrudan dizi olarak kullanılıyor. Türler: `tur.getAll`.
- `22yc01h34_ye1.js`: `_n3p6q1v8x5.getSeriesData.useQuery({sef,page,limit:10})`; `series`, `chapters`, `pagination.totalPages`; bölüm kimliği `chap_id`.
- Okuyucuya özgü `2d3einoagz1gi.js` obfuscation içeriyor. Okuyucu API prosedürü henüz doğrulanmadı. Mevcut parser'daki aday prosedürler kanıtlanmış endpoint değildir.

Mevcut parser'ın liste ve detay router adlarıyla bu adlar aynı. Router adı değişikliğini kök neden saymak için kanıt yok.

## İstek denemeleri ve sınırları

Fetch aracı doğru batch biçimiyle `/api/trpc/_s4d7g2k9m0.getSeries` için HTTP 500 bildirdi. Ayrı `/api/gif?type=trending` isteği de aynı sonucu verdi; JS dosyaları okunabildi. Bu sonuç uygulamanın aldığı HTTP yanıtını kanıtlamaz ve Cloudflare engelini kesinleştirmez. Sunucu hatası, araç kısıtı veya istek gereksinimi ayrıştırılmalı.

Keiyoushi kodunda batch URL kodlaması mevcut; Accept ve Referer başlıkları da zaten mevcut. Bunları yeni bir çözüm olarak eklemek tek başına gerekçelendirilmiş değil. Liste dönüşümünde DTO decode hataları `runCatching(...).getOrNull()` ile sessizce atılıyor; detay hataları da bastırılıyor. Bu nedenle DTO'nun canlı API yanıtıyla eşleştiği henüz söylenemez.

Okuyucu HTML'sinde görünen `/api/uploads/.../images/thumb_1780281234413.png` URL'si structured-data içinde bulunuyor. Tek bir schema/kapak görselinin varlığı bölümün tüm sayfalarının HTML içinde olduğu anlamına gelmez.

## Durum

Bu inceleme bir çalışma doğrulaması değildir. Canlı API yanıtı veya uygulama hata kaydı olmadan kök neden kesinleşmedi. Sürüm artırılmadı, APK üretilmedi ve yeni sürüm yayımlanmadı.

## Sonraki doğrulama ve v1.6.4 değişiklikleri

- İlk düzeltme v1.6.3 olarak derlendi; canlı Python HTTP testi GitHub runner üzerinde Cloudflare 403 blok sayfası aldı (`reports/sadscans-live.json`). Önceki "sürüm artırılmadı" bölümü ilk inceleme anını anlatır.
- v1.6.4: JSON URL'sini host Cloudflare interceptor'una tekrar tekrar açtırmak yerine, önce kısıtlı süreli doğrudan API isteği yapılır. Açık Cloudflare challenge/blok yanıtında `/seriler` WebView'de açılır; API çağrısı aynı browser identity ve same-origin cookie oturumunda `fetch` ile yapılır. Cookie'yi farklı TLS istemcisine taşıyarak çözülmüş sayılmaz.
- WebView fetch kodu doğrudan Kotlin kaynak string'inden Node VM içinde test edildi: tek istek, bekleyen sonuç, site hazır değilken bekleme, HTTP 403 ve ağ hatası davranışları geçti. Bu bir Android entegrasyon testi değildir.
- v1.6.4 APK ve katalog derlemesi geçti: Actions run `42250260141`.
- Chromium canlı testi çalıştı (kurulum hatası değil): run `42251356158`, `reports/sadscans-browser-live.json`. `/seriler` HTML isteği bile Cloudflare'ın `Attention Required!` / `Sorry, you have been blocked` sayfasına düştü; API aşamasına ulaşılamadı.
- Bu kanıt yalnızca GitHub runner erişimini gösterir. Telefonda aynı sonucu varsaymak da, telefonda sorunsuz çalıştığını iddia etmek de doğru değildir. Runner üzerindeki hard block için sadece parser/header değişikliğiyle başarılı erişim sağlandığı kanıtlanmadı.
- Yeni APK test amacıyla çalışma dalında hazırdır. Main/builds kanalına başarılı çalışma iddiasıyla yayımlanmadı. Reader'ın tahmini API adayları henüz canlı doğrulanmış değildir; tüm okuma akışının tamamlandığı iddia edilmez.
