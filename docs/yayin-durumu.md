# Yayın durumu (6 Kasım 2026)

Depo adresi: `https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Manga-Plugins/builds/index.min.json`

## Bir eklenti güncellemesi nasıl yayınlanır

1. `keiyoushi-extensions-tr/src/tr/<eklenti>/build.gradle.kts` içinde `versionCode` değerini **bir artırın** (bu sayı kaynak numarasıdır; temanın `baseVersionCode` değeri derlemede üzerine eklenir).
2. `tr-extension-release.yml` iş akışını çalıştırın (`modules` girdisine eklentiyi yazın).
3. İş akışı APK'yı derler, **katalog sürümünü doğrudan APK'nın manifestinden** okur ve katalogları eşitler.

Yayın betiği (`tools/prepare_extension_release.py`) şu iki durumda **durur**:

- APK'nın manifestindeki sürüm, Gradle hesabıyla uyuşmuyorsa,
- Yeni sürüm kodu, yayındaki sürüm kodundan büyük değilse (istemciler aksi hâlde güncellemeyi görmez).

## Etkin eklentiler

| Eklenti | Site | Sürüm | Kod |
|---|---|---|---|
| Manga-TR | mangatr.net | 1.6.35 | 106035 |
| MangaciX | mangacix.com | 1.6.2 | 106002 |
| MangaDenizi | mangadenizi.net | 1.6.8 | 106008 |
| Hayalistic | hayalistic.site | 1.6.62 | 106062 |
| Koreli Manga | korelimanga.com | 1.6.3 | 106003 |
| Türkçe Manga Oku | trmangaoku.com | 1.6.56 | 106056 |
| Amanga Planet | amangaplanet.com.tr | 1.6.1 | 106001 |

## Canlı doğrulama

`tests/tr_sources_runtime/run.mjs` üç kaynağı eklentinin gittiği yollardan dener (19/19 geçti):

- **Hayalistic**: seri sayfasında 124 bölüm (`li.wp-manga-chapter`), okuyucuda 29 sayfa (`div.page-break`), CDN görseli indi.
- **Koreli Manga**: 24 bölüm satırı (`div.chapter-item > a.uk-link-toggle`), `#chapter-content` içinde 23 düz görsel, görsel indi.
- **Türkçe Manga Oku**: `POST /ajax/chapters/` 92 bölüm, okuyucuda 10 sayfa, görsel indi.

### Bilinen sınırlar

- **Koreli Manga** seri sayfası HTML'de yalnız 6 bölüm taşıyor (gerçekte 29); tema listeyi kaydırmayla yüklüyor, uygulama içi tarayıcıda tamamı görünür. `/bolum/page/2/` adresi "Bölüm bulunamadı" döndürüyor, yani bölüm listesi tek sayfa.
- **Hayalistic** tarihleri Türkçe uzun ay adıyla geliyor (`1 Kasım 2026`); ayrıştırılamayan tarihler boş kalır, bölüm listesi ve okuma etkilenmez.
