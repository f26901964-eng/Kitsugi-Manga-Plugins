# Manga Diyarı (mangadiyari.com) — doğrulama raporu

Bu belge, eklentinin **yeni siteye göre gerçekten çalıştığının** kanıtını ve sınırlarını içerir.
Tahmin yok: her satır ya canlı istek/ölçüm ya da geçmiş bir GitHub Actions koşusuna dayanır.

## Sürüm

| | |
| --- | --- |
| Eklenti sürümü | **1.6.5** (`libVersion 1.6` + kaynak `versionCode 5`) |
| Katalog kodu | **106005** (önceki: 106004 → 106001) |
| APK | `apk/`, `builds/apk/`, `prebuilt/apk/` → `tachiyomi-tr.mangadiyari-v1.6.5.apk` (154.551 bayt) |
| Katalog kayıtları | `index.json`, `plugins.json`, `registry/extensions.json`, `registry/extensions.v2.json`, `builds/*` — hepsi 1.6.5 / 106005 |
| Yayın koşusu | `mangadiyari-release.yml` ✓ (imza kontrolü + APK derleme + katalog commit) |
| Test koşusu | `mangadiyari-ci.yml` ✓ — unit testler + APK + canlı sonda |

Doğrulanan koşular: CI `42191961013`, release `42191960745` (main), öncesinde `42191531184` / `42191531253`.

## İmza (güncellemenin çalışması için kritik)

Android, kurulu bir eklentinin üzerine **yalnız aynı anahtarla imzalanmış** APK'yı kurar. Ölçüm:

| Dosya | Sertifika sha256 (ilk 32) |
| --- | --- |
| Yayındaki eski 1.6.1 | `a53115764ff5e259e0e5acec3c58a851` |
| CI'da üretilen 1.6.4 | `ee44a50b2de270fdfde82bc8d41e8f3a` |

İki dosya farklı anahtarlarla imzalıydı; bu yüzden katalog güncellemesi kurulamıyordu. Düzeltme:
imza anahtarı `tools/signing/mangadiyari.p12` olarak repoda tutuluyor (alias `kitsugi`, parola
`kitsugi-manga-plugins` — herkese açık bir kimlik doğrulama anahtarı, sır değil) ve
`ExtensionPlugin.kt` release derlemesinde bu anahtarı kullanıyor. `tools/verify_mangadiyari_signature.py`
yayından önce APK sertifikasını keystore ile karşılaştırır; uyuşmazsa iş **durur**.

1.6.5 doğrulaması: yayındaki APK sertifikası keystore ile **eşleşiyor** (`İmza keystore ile eşleşiyor`).
1.6.1 farklı anahtarla imzalı olduğu için son geçişte eski eklentiyi kaldırıp 1.6.5'i kurmak gerekir;
sonraki sürümler normal güncelleme olarak kurulur.

## Canlı sonda (CI'da her koşuda çalışır)

`tools/mangadiyari_probe.py`, eklentinin kullandığı uçların **aynısını** çağırır. Son koşuda
**32 kontrol geçti, 0 hata, 2 uyarı**. Uyarılar hata değil, aşağıda açıklanmıştır.

| Alan | Kontrol | Sonuç |
| --- | --- | --- |
| Popüler | `GET /api/series?sort=popular&limit=24&page=1` | 24 seri, `hasMore=true` |
| Son güncellemeler | `GET /api/series/latest-updates?limit=24&page=1` | 24 kayıt; alanlar `slug, cover_url, chapters, status, type, views, last_update` |
| Arama | tam başlık / küçük harf / aksansız / kelime | hepsi sonuç buldu; olmayan sorgu `200` + boş liste |
| Sıralama | popular / rating / title | 24'er sonuç |
| Durum | ongoing / completed | 24 / 2 |
| Tür (`type`) | manhwa / manhua / manga | 11 / 4 / 15 |
| Sayfalama | `page=2` | 6 sonuç (`hasMore=false`) |
| Seri detayı | `/api/series/ben-olumsuzum` | 793 bölüm, numaralar 1..793, tekrar 0, boşluk 0 |
| Bölüm sayfaları | ilk bölüm (9 sayfa) ve son bölüm (4 sayfa) | tüm görsel yolları mutlak |
| Görseller | ilk/son sayfa gerçekten indirildi | JPEG 1000×563 ve 1920×1080 |
| Kapak | `Referer` ile ve referersiz | ikisi de `200 image/jpeg` (referer zorunlu değil) |
| Web adresleri | `/series/{slug}` ve `/seri/{slug}/bolum/1` | `200`, sayfa içinde `/api/` işaretçisi var |

## Tür filtreleri — ölçüme dayalı düzeltme

Site tür etiketini **birebir** eşleştiriyor ve eski seriler İngilizce, yeniler Türkçe etiket taşıyor.
Ölçülen davranış:

* `genre=Aksiyon` → 16 seri, `genre=Action` → 3 seri (ikisi ayrı kümeler).
* `genre=Dram,Drama` → **0** (virgüllü liste **VE / AND** demek).
* `?genre=Aksiyon&genre=Fantezi` → yalnız **ilk** parametre kullanılıyor (16), yani "VEYA" ifade edilemiyor.
* Bazı türler yalnız tek dilde var: `Reincarnation` 3 / `Reenkarnasyon` 0, `Slice of Life` 1 / `Günlük Yaşam` 0.

Bu yüzden her grup **tek** etiket gönderir ve etiket, sitede gerçekten seri döndüren adla seçilir:

```
Aksiyon 16 · Macera 10 · Komedi 6 · Dram 8 · Fantezi 20 · Tarihi 1 · Isekai 1 · Dövüş Sanatları 11
Reincarnation 3 · Romantizm 6 · Okul Hayatı 1 · Doğaüstü 5 · Ecchi 1 · Harem 5 · Müstehcen 3
Seinen 2 · Shoujo 6 · Shounen 7 · Slice of Life 1 · Webtoon 1 · Manhwa 2 · Manhua 1 · Murim 2
Sistem 1 · Şeytan 1 · Ters Harem 1
```

Sitede **hiç serisi olmayan** gruplar (Korku, Gizem, Bilim Kurgu, Gerilim, Josei, Mecha, Psikolojik,
Spor, Trajedi) listede kalır; siteye seri eklendiğinde çalışır. Bu durum uyarı olarak raporlanır,
derlemeyi durdurmaz. Buna karşılık **eklentinin gönderdiği etiket boş dönüp sitede başka bir ad
sonuç veriyorsa** sonda bunu hata sayar (`genre-label-mismatch`) ve derleme kırılır — filtre sessizce
ölü kalmasın diye.

## Bilinen sınırlar (dürüst liste)

1. **Tür seçimi AND'dır.** Birden fazla kategori işaretlenirse site hepsini birlikte taşıyan serileri
   döndürür (örn. `Aksiyon,Fantezi` → 14). Bu sitenin davranışı; eklenti tek etiketle aynı sonucu verir.
2. **Aynı türün iki dildeki kaydı tek filtrede birleştirilemiyor.** API'de OR yok. `Aksiyon` seçildiğinde
   yalnız Türkçe etiketli 16 seri gelir; eski 3 seri `Action` etiketiyle durur. İkisini birden görmek
   için iki ayrı sorgu gerekir; eklenti filtresi tek sorgu ürettiği için bu yapılamıyor.
3. **`comic` türü sitede boş** (0 seri) — seçenek duruyor ama sonuç dönmez.
4. **`type` filtresi** çalışıyor, ancak site birçok seriyi `manga` olarak işaretlemiş (15) — katalog
   gerçekliği, eklenti hatası değil.
5. **Web sayfası işaretçisi:** iki `web-*` kontrolü "uyarı" verir çünkü Next.js sayfası `/api/` metnini
   yalnız istemci tarafında kullanır; sayfa yine de `200` ve doğru rotada açılır.

## Testler

* `python3 -m unittest discover -s tests` → **21 test** (4'ü ağ/ortam gerektirdiği için skip).
* Kotlin: `MangaDiyariTest` — fixture'lar canlı siteden alınmış JSON biçimleri; seri listesi, detay,
  bölüm sıralama, sayfa görseli çözümleme, bozuk satır toleransı ve tür etiketi davranışı.
* `tests/test_mangadiyari_probe.py` — sonda ile eklentinin tür tablosu/uçları/sayfa boyutu sessizce
  ayrışmasın diye ikisini karşılaştırır.
* Opt-in canlı test: `MANGADIYARI_LIVE_TEST=1` + `MANGADIYARI_LIVE_REPORT=<probe json>` ile çalışır ve
  sonda raporundaki hataları test hatasına çevirir. CI'da her koşuda etkindir.

## Komutlar

```bash
python3 -m unittest discover -s tests
python3 tools/mangadiyari_probe.py --output mangadiyari-probe.json --summary
MANGADIYARI_LIVE_TEST=1 MANGADIYARI_LIVE_REPORT=$PWD/mangadiyari-probe.json \
  ./gradlew :src:tr:mangadiyari:testDebugUnitTest :src:tr:mangadiyari:assembleRelease
python3 tools/prepare_mangadiyari_release.py   # APK + katalog senkronu (release işiyle aynı)
python3 tools/verify_mangadiyari_signature.py \
  apk/tachiyomi-tr.mangadiyari-v1.6.5.apk tools/signing/mangadiyari.p12
```
