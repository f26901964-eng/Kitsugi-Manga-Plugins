# Turkmanga (`turkmanga.org`) HTML sözleşmesi

Turkmanga eklentisinin ilk parser sözleşmesi, kullanıcı tarafından paylaşılan Drive klasöründeki dört HTML kaydından çıkarıldı (`message (81).txt`–`message (84).txt`). Büyük HTML gövdeleri reklam/analitik betikleri ve dinamik içerik taşıdığı için depoya kopyalanmadı; `src/test` altında yalnız ayrıştırmayı doğrulayan küçük, temsili parçalar tutuluyor.

| İşlev | Sayfa / veri | Parser'ın kullandığı alanlar |
|---|---|---|
| Keşfet / katalog | `/mangalar` ve ana sayfa; Next.js `__NEXT_DATA__` | `slug`, `name`, `type`, `finished`, `description`, `episode.date`; kart HTML'si yedek yol |
| Arama | `/search?input=...` | `__NEXT_DATA__` içindeki seri kayıtları; sayfalama için Next verisi/aktif sonraki bağlantı ve sayfa boyutu |
| Tür filtresi | `/mangalar?genre=...` | `kategori_data` ile görülen `genre` slug'ları (aksiyon, fantastik, webtoon, `nsfw`, `-yaoi`, `-yuri` vb.) |
| Seri bilgisi ve bölümler | `/manga/{slug}` | JSON-LD `ComicSeries`: `name`, `description`, `genre`, `author`, `translator`, `creativeWorkStatus`, `image`, `hasPart[]` |
| Bölüm/çevirmen | `hasPart[]` içindeki `ComicIssue` | `url`, `name`, `issueNumber`, `datePublished`, `translator`; çevirmen `SChapter.scanlator` alanına taşınır |
| Okuyucu sayfaları | `/manga/{slug}/{chapter}` | JSON-LD `ComicIssue.image[]`; yedek olarak Next verisi ve tembel yüklenen görseller |
| Kapak | `api.turkmanga.org/api/media/manga/{slug}?width=600&height=900` | SSR verisinde kapak bulunmadığında kullanılan URL |
| Bölüm görseli | `cdnapi.turkmanga.org/image/{id}` | Okuyucu sırasını koruyan görsel URL listesi; görsel isteğinde bölüm sayfası `Referer` olarak gönderilir |

Paylaşılan kayıtlar ana sayfa, arama, seri detayı ve bir bölüm okuyucusunu içeriyor. Seri/bölüm detaylarında yapılandırılmış JSON-LD, sayfada kullanıcı arayüzü değişse bile açıklama, yazar, çevirmen ve görselleri alabilmek için birincil kaynak; Next.js state ve DOM kartları ise liste ekranlarında ve yedek ayrıştırmada kullanılıyor.

`TurkmangaParserTest` bu sözleşmenin liste, arama, metadata, durum, bölüm numarası/tarihi/çevirmeni, kapak, reader görselleri, URL doğrulaması ve HTML yedek yollarını sınar. Canlı ağ ve Android APK testi ayrı CI adımında yapılmalıdır; bu çalışma ortamında Java/Android SDK kurulu değil.
