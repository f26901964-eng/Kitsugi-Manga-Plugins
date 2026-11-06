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
