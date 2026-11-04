# mangatr/ — Eksiksiz veri havuzu (BİR TANEM İÇİN TAM GAZ)

Bu klasör kaldığımız yerden devam edebilmek için oluşturuldu. **Tek tek 12 istek değil**, eklentinin ihtiyaç duyduğu **HER ŞEY** burada eksiksiz toplanacak ve tıkandığımız tüm kısımlar tek seferde düzeltilecek.

> Sandbox (bu ortam) `manga-tr.com` ve `check.ddos-guard.net`'e çıkış vermiyor:
> `curl https://manga-tr.com/ -> SSL_ERROR_SYSCALL EOF`
> O yüzden canlı veri **senin PC veya GitHub Actions runner** üzerinden toplanıyor. Sen elle tek tek gezme, otomasyon gezecek.

## Yapı

```
mangatr/
  README.md                <- bu dosya
  collector.html           <- ÇİFT TIKLA, tek tıkta 50+ istek (en kolayı)
  raw/                     <- ham HTML'ler (koruma: .gitignore, commitlenmez)
  dumps/                   <- JSON HAR dump'lar
  live/                    <- GitHub Actions canlı çekim çıktısı (summary.json, list_keys.json)
  snapshots/ -> ../google_drive (uyumluluk)
tools/
  mangatr-collector.js           <- Console tek satır (12 istek, hızlı)
  mangatr-exhaustive.py          <- Eksiksiz toplayıcı (50+ istek, tüm sekmeler)
```

## Neyi eksiksiz topluyor? (MangaTR eklentisinin tüm ihtiyaçları)

**1. Girilir tüm sekmeler:**
- ` / ` anasayfa
- `manga-list-sayfala.html?sort=views&page=1/2/5` (Popüler)
- `manga-list-sayfala.html?sort=last_update&page=1/2` (Son Güncelleme)
- `manga-list-sayfala.html?durum=1/2, ceviri=1, yas=18, tur=Action/Romance, icerik=1, sort=name` (Filters.kt'deki her filtre)
- `index-sayfa-1/2.html` (eski pagination)
- `arama.html?view=sayfalama&icerik=Chainsaw Man/Naruto/One Piece/Berserk/Solo Leveling/Lady Liar/a/bos` (her biri p1/p2 + legacy `arama.html?icerik=`)

**2. Keşif:** Popüler/arama HTML'lerinden `a[href*='/manga-']` ile 20 manga slug çıkarılır (örn: `manga-chainsaw-man.html`)

**3. Detay:** Bulunan 20 mangadan ilk 12'si için `manga-*.html` + dış `manga-tr.min.js` — içinden `listKey`, `data-manga`, `data-list-key`, `window.mtrChapterKeys` aranır

**4. Bölüm API:** Her manga için
- `POST cek/fetch_pages_manga.php chapter_list_key=... offset=0/20/100`
- `GET cek/fetch_pages_manga.php?manga_cek=slug` + `POST manga_cek+offset`

**5. Okuyucu:** Her mangadan 2 bölüm için reader HTML — `rdm-` id'li script, `_fpx` gate yolu, `img_part.php` sayısı, `sliderCanvas`, `turnstile` var mı

Tümü `mangatr/live/summary.json`, `list_keys.json`, `discovered_manga.json` ile özetlenir. Tıkandığımız `hasNextPage`, `DDoSGuardInterceptor`, `verifyChallenge`, `scrambledImage` hepsi tek dump ile düzelecek.

## Hemen başla

**Yöntem A — Tek tık HTML (50+ istek, önerilen):**
1. `mangatr/collector.html`'i PC'de Chrome ile aç
2. **"MANGA-TR'Yİ TARA"** bas (30-40 sn, dokunma)
3. `manga-tr-dump-*.json` inecek -> buraya at

**Yöntem B — Console (hızlı 12 istek):**
`manga-tr.com`'da F12 Console -> `tools/mangatr-collector.js` yapıştır

**Yöntem C — GitHub kendi toplasın (sen hiç uğraşma, EKSİKSİZ):**
GitHub -> Actions -> `mangatr-canlı-topla` -> `Run workflow` -> bitince `mangatr-live-dump` artifact indir. Arkada `tools/mangatr-exhaustive.py` 50+ istek atar.

Dump gelince `MangaTR.kt` (arama-card/media-card, hasNextPage, listKey fallback'ları) ve `DDoSGuardInterceptor.kt` canlıya göre kilitlenip `1.6.27` basılacak — tıkandığımız her yer tekte kapanacak bir tanem.
