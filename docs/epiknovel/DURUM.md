# Epik Novel — durum ve sözleşme

**Durum:** eklenti `tr/epiknovel` olarak eklendi; CI derleme + test + canlı
sözleşme koşusu + katalog yayını `.github/workflows/epiknovel-release.yml`
ile tetiklenir.

## Site kontrolü (7 Kasım 2026)

| Özellik | Site kanıtı | Eklenti davranışı |
|---|---|---|
| Keşfet | `/seri-listesi` — `#pop-href` içinde 50 kart, `sorting=views&sorting-type=DESC` | `getPopularManga` |
| Son eklenenler | `sorting=lastUpdate&sorting-type=DESC` | `getLatestUpdates` |
| Arama | `?q=…` (boş q = tarama); tek sonuçta `Sayfa=2` yok | `getSearchMangaList` + `SortFilter`/`DirectionFilter`/`StatusFilter` |
| Durum filtresi | `?durum=0` Devam ediyor, `?durum=1` Tamamlandı | `StatusFilter` |
| Kapak (kart) | `img.media-object` → `src=ajax-loader.gif`, **gerçek kapak `data-src`** | parser `data-src` öncelikli (kullanıcının gördüğü görsel bug'ı burada) |
| Seri detayı | `h1#tables` (+18+ `img` temizlenir), `img.manga-cover`, `h4` `Çevirmen:`/`Durum:` | başlık, kapak, `author`, `description` ilk satırı `Çevirmen: X`, durum eşlemesi |
| Açıklama | `h3 "Seri Özeti"` sonrası paragraflar (`<p><p>` iç içe; `&nbsp;` temiz) | `description` |
| Bölümler | `table.table tbody tr` → `/seri/<slug>/bolum/<n>` (eski→yeni), `Bölüm N` → `chapter_number`, `N yıl önce` → `date_upload` | `chaptersFromDetails` |
| Bölüm metni | `#icerik`: `center>h4>b` + `<p>` akışı; `script`/`ins`/`iframe` yok; boş `<p><br></p>` ayraç | `chapterContent` |
| Görseller | kart `data-src`, seri `img.manga-cover` doğrudan `src`, içerik görselleri `#icerik img` | tüm istekler `Referer`/`Origin` + tarayıcı UA ile |

## Metin → görüntü köprüsü

Tachiyomi/Keiyoushi okuyucusu görsel sayfa akışı bekler; Epik Novel bölümleri
düz metindir. Çözüm, uygulama katmanına dokunmadan eklenti içinde:

1. `getPageList` bölümü indirir, `EpikNovelContent.paginate` ile **saf ve
   deterministiktir** sayfa sayısını hesaplar ve `Page.imageUrls` olarak
   `/__epiknovel_textpage?chapter=…&page=N` sanal adreslerini döndürür.
2. `imageRequest` aynı adresi istediğinde `TextPageInterceptor` bölümü yeniden
   indirir, blokları 1080×1920 PNG'ye çizer (adapte yazı boyutu, üstbilgi =
   bölüm adı + sayfa numarası, içerik görselleri yerleştirilir).
3. PNG'ler uygulama diskinde `imageUrl` anahtarıyla önbelleklenir; bölüm LRU(4)
   ayrıştırma önbelleği yalnız ilk açılışta kullanılır.

Sayfa sınırları `PAGE_CHAR_BUDGET=1500` (paragraf ≤1100, görsel 1200, başlık
120 birim) ile `EpikNovelContent` içinde tanımlı; `getPageList` ve interceptor
aynı işlevi kullandığından sınırlar her zaman eşleşir.

## Testler

- Birim: `keiyoushi-extensions-tr/src/tr/epiknovel/src/test/.../EpikNovelTest.kt`
  — dump'lar `docs/html-dumps/epiknovel/` (Drive `message-97..100` + canlı
  `live-*.html`) üzerinden sistematik özellik testleri.
- Canlı: `tests/epiknovel_runtime/run.mjs` — katalog/arama/filtre/sıralama,
  detay (çevirmen/açıklama/durum), bölüm metni ve kapak görselleri sitenin
  kendisine sorulur; rapor `reports/epiknovel-runtime.json`.

## Sürüm

`versionCode = 1` → `1.6.1` (`106001`), `ContentWarning.MIXED` (`nsfw: 0`),
`libVersion 1.6`, dil `tr`, baseUrl `https://www.epiknovel.com`.
