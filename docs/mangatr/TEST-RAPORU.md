# Manga-TR inceleme / test raporu — 5 Kasım 2026

## Kapsam ve sürüm

Bu çalışma Manga-TR'nin güncel ana sayfa, arama, detay, imzalı bölüm-listesi API'si, çoklu çeviri bağlantıları ve şifreli okuyucu yapısını hedefler. Yayın sürümü `1.6.29`, katalog sürüm kodu `106029`, kaynak `versionCode` değeri `29`'dur.

Drive'daki özel HTML örnekleri oturum/tek kullanımlık anahtarlar içerebildiği için Git'e eklenmez. Kalıcı genel fixture'lar yalnız gerçek Chrome'da anonim kullanıcıya sunulan bölüm-listesi HTML parçaları ve güvenli probe raporlarıdır.

## Doğrulanan güncel sözleşmeler

- Bölümler `POST /cek/fetch_pages_manga.php` üzerinden `manga`, `tur`, `offset`, `_t`, `_sig` alanlarıyla alınır. İmza `SHA256(slug_seed_timestamp)`; seed güncel CSS'ten okunur.
- Gerçek Chrome fixture'ları offset `0`, `20`, `120` için 200 döndü. İlk iki yanıtta toplam 109 kart ve 153 benzersiz çeviri/reader-ID ayrıştırıldı; offset `120` boş bitiş yanıtıdır.
- Chainsaw Man bölüm 2 için dört ayrı çeviri korunur: `123931` Bağımsız / Invictus Fansub, `125989` MangaTR / AnimeWho, `135286` Pied Piper ve `136245` Şimarik Fansub. Çeviriler tek bir karta indirgenmez ve reader-ID'ye göre tekilleştirilir.
- Güncel okuyucudaki gerçek sayfalar şifreli `data-*` özniteliklerinden çözülür; noscript ve 0×0 tuzak görselleri kullanılmaz. Sıra değerleri korunur ve şifreli parça sırası varsa URL fragment'ına eklenir.
- Okuyucu `_fpx` gate'i, tarayıcıdaki gibi POST ve gerekirse GET fallback ile çalıştırılır. Görsel istekleri document/AJAX isteklerinden ayrılır; site kökü Referer ve image `Sec-Fetch` header'ları kullanılır.

## Otomatik test sonuçları

- CI run `41963390762`: Spotless, JVM fixture/contract testleri, debug/release derlemesi ve release APK üretimi başarılı.
- Live run `41963754037`: imzalı bölüm istekleri, dört chapter-2 reader-ID'si, gate yanıtları, sıralı ve benzersiz sayfa listeleri başarılı. Dört okuyucunun tamamı çalıştırıldı; test ilk görselde erken durmadı.
- Aynı live run'da dört ilk görsel de runner'a Cloudflare `403 Attention Required` döndürdü. Bu dış engel açık bir JUnit assumption skip ve GitHub warning olarak raporlandı; görsel indirme başarılı sayılmadı. Cloudflare dışı görsel hatası gözlenmedi.
- Headed Android Chrome probe run `41962731386`: gerçek okuyucu scripti ve gate 200 çalıştı; görsel yüklemeleri `net::ERR_BLOCKED_BY_ORB` ile engellendi. Bu, sorunun yalnız OkHttp header'larından ibaret olmadığını ve GitHub runner ortamındaki Cloudflare cevabını doğrular.
- Python testleri özel Drive snapshot ortam değişkeni olmadan ilgili örnekleri skip eder; katalog ve sürüm eşleme testleri her ortamda çalışır.

## Bilinen doğrulama sınırı

GitHub Actions ağında görsel byte doğrulaması tamamlanamadı. Gerçek Android cihazda APK kurulumu ve dört çevirinin ilk görsellerinin açılması kullanıcı tarafında ayrıca doğrulanmalıdır. Tüm manga ve tüm gelecek site değişiklikleri için sıfır hata garantisi verilmez. CI'daki Cloudflare skip'i, cihazda görsel doğrulaması yapılmış anlamına gelmez.
