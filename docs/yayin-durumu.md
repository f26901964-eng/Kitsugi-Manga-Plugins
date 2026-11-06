# Yayın durumu (6 Kasım 2026)

Depo adresi: `https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Manga-Plugins/builds/index.min.json`

## Bir eklenti güncellemesi nasıl yayınlanır

1. `keiyoushi-extensions-tr/src/tr/<eklenti>/build.gradle.kts` içinde `versionCode` değerini **bir artırın** (bu sayı kaynak numarasıdır; temanın `baseVersionCode` değeri derlemede üzerine eklenir).
2. Mevcut eklentiler için ilgili APK iş akışını çalıştırın. Turkmanga için `Turkmanga parser verification and APK release` iş akışı kullanılır.
3. İş akışı APK'yı derler, **katalog sürümünü doğrudan APK'nın manifestinden** okur ve katalogları eşitler.

Yayın betiği (`tools/prepare_extension_release.py`) şu iki durumda **durur**:

- APK'nın manifestindeki sürüm, Gradle hesabıyla uyuşmuyorsa,
- Yeni sürüm kodu, yayındaki sürüm kodundan büyük değilse (istemciler aksi hâlde güncellemeyi görmez).

## Etkin eklentiler

Mevcut genel `builds` dağıtım dalında hâlen dört eklenti var; bu özellik dalında beşinci kaynak olan Turkmanga etkinleştirildi. GitHub Actions `1.6.1` (`106001`) APK'sını derleyip parser testlerini ve katalog senkronizasyonunu başarıyla tamamladı. APK ile güncel katalog dosyaları bu dala kaydedildi; genel `builds` dağıtımı için değişikliklerin `main` dalına alınması ve ana katalog iş akışının çalışması gerekir.

| Eklenti | Site | Sürüm | Kod |
|---|---|---|---|
| Manga-TR | https://mangatr.net | 1.6.35 | 106035 |
| MangaciX | https://mangacix.com | 1.6.2 | 106002 |
| Mangitto | https://mangtto.com | 1.6.3 | 106003 |
| OkuToon | https://okutoon.com | 1.6.11 | 106011 |
| Turkmanga | https://turkmanga.org | 1.6.1 (CI doğrulandı) | 106001 |

## Turkmanga CI doğrulaması

[Turkmanga parser verification and APK release](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42239309902) çalışması başarılı oldu: parser birim testleri ve APK derlemesi geçti; katalog senkronizasyonu ve depo testleri de tamamlandı. APK `prebuilt/apk/tachiyomi-tr.turkmanga-v1.6.1.apk` yolunda bulunur (SHA-256 `b3d36ea77bc7c0efed116539d66db04dde30be10773ba5d4b64192221df4a9c3`). Testler paylaşılan HTML biçimlerinden alınan fixtures üzerindedir; canlı siteye veya uygulama cihazına uçtan uca istek yapılmadı.

## Canlı doğrulama

`tr-extension-release.yml` her yayından önce **iki** canlı test koşar.

### OkuToon — `tests/okutoon_runtime/run.mjs` (11/11 geçti)

Kaynağın gerçek adreslerini dener:

- **Keşfet/ana sayfa**: `/tur?sira=popular` 200 döner, 24 `a.series-card` kartı (başlık + kapak) bulunur, kapaklar `om-cdn.okutoon.com` üzerinden gelir.
- **Sayfalama**: `?sayfa=2` farklı 24 kart döner, sayfada `a.pagination-btn[rel=next]` vardır.
- **Arama**: `?q=chainsaw+man&sira=updated` "Chainsaw Man" sonucunu verir.
- **Seri detayı**: `.series-detail-title/-author/-cover/-genres`, `.series-status--*`, `[data-series-description-content]` ve `.tag-link` türleri dolu (One Piece: 5 tür).
- **Bölüm listesi**: One Piece'te 1225 `div.chapter-item` satırı; her satırda `a.chapter-item-link`, `.chapter-title`, Türkçe tarih (`.chapter-date` → "30 Ekim 2026").
- **Okuyucu**: Bot istemcilerine Cloudflare meydan okuması (403, `cf-mitigated: challenge`) döndüğü için canlı indirilemiyor; sayfa işaretlemesi kayıtlı dökümle doğrulandı — `#readerPages` içinde 53 `img.reader-page`, her biri `om-cdn.okutoon.com/chapters/.../chainsaw-man_bolum-1_001..053.webp` adresli.

### Diğer kaynaklar — `tests/tr_sources_runtime/run.mjs` (19/19 geçti)

- **Hayalistic**: seri sayfasında 124 bölüm (`li.wp-manga-chapter`), okuyucuda 29 sayfa (`div.page-break`), CDN görseli indi.
- **Koreli Manga**: 24 bölüm satırı (`div.chapter-item > a.uk-link-toggle`), `#chapter-content` içinde 23 düz görsel, görsel indi.
- **Türkçe Manga Oku**: `POST /ajax/chapters/` 92 bölüm, okuyucuda 10 sayfa, görsel indi.

Not: Hayalistic / Koreli Manga / Türkçe Manga Oku artık **yayında değil**; testleri yalnız kaynak kodu korunduğu için duruyor.

### Bilinen sınırlar

- **Koreli Manga** seri sayfası HTML'de yalnız 6 bölüm taşıyor (gerçekte 29); tema listeyi kaydırmayla yüklüyor, uygulama içi tarayıcıda tamamı görünür. `/bolum/page/2/` adresi "Bölüm bulunamadı" döndürüyor, yani bölüm listesi tek sayfa.
- **Hayalistic** tarihleri Türkçe uzun ay adıyla geliyor (`1 Kasım 2026`); ayrıştırılamayan tarihler boş kalır, bölüm listesi ve okuma etkilenmez.
- **OkuToon** okuyucu sayfası tarayıcı dışı istemcilere Cloudflare meydan okuması gösteriyor; uygulama içinde (WebView tabanlı OkHttp) sayfa açılıyor. Seçiciler kayıtlı HTML dökümleriyle (`docs/html-dumps/okutoon/`) sabitlendi.
