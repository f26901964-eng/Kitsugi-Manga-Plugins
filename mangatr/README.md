# mangatr/ — Eksiksiz veri havuzu

Bu klasör kaldığımız yerden devam edebilmek için oluşturuldu. Uygulama mı eklenti mi tartışması bitsin diye **girilir-çıkılır tüm sekmelerin** ham verisi burada toplanacak.

## Yapı

```
mangatr/
  README.md                <- bu dosya
  collector.html           <- ÇİFT TIKLA, manga-tr.com'da tek tık toplama (en kolayı)
  raw/                     <- ham HTML'ler (koruma: .gitignore'da, commitlenmez)
  dumps/                   <- JSON HAR dump'lar (collector.js çıktısı)
  live/                    <- GitHub Actions canlı çekim çıktısı
  snapshots/ -> ../google_drive (uyumluluk)
```

## Neden burası lazım?

Sandbox (bu ortam) `manga-tr.com` ve `check.ddos-guard.net`'e çıkış vermiyor:

```
curl https://manga-tr.com/ -> SSL_ERROR_SYSCALL EOF
```

O yüzden canlı veri **senin PC veya GitHub Actions runner** üzerinden toplanmalı. Testler buna göre yazıldı:

* `tests/test_mangatr_snapshots.py` -> `MANGATR_SNAPSHOT_DIR=google_drive` için 5 dosya bekler: `message (15).txt` .. `message (19).txt`
* Yeni toplayıcı -> `mangatr/dumps/*.json` ve `mangatr/live/*` üretir

Ham HTML'ler oturum anahtarı içerebildiği için `.gitignore`'a eklidir, depoya itilmez. Sadece sen ve CI görür.

## Hemen başla (hangisi kolayına gelirse)

### Yöntem A — Tek tık HTML (önerilen, 30 sn)
1. `mangatr/collector.html` dosyasını PC'de Chrome ile aç
2. Sayfadaki **"MANGA-TR'Yİ TARA"** butonuna bas
3. `manga-tr-dump-*.json` ve `manga-tr-pack.zip` inecek -> buraya at

### Yöntem B — Console tek satır
`manga-tr.com`'da `F12 -> Console` aç, `tools/mangatr-collector.js` içeriğini yapıştır, `Enter`.

### Yöntem C — GitHub kendi toplasın (sen hiç uğraşma)
GitHub -> Actions -> `mangatr-canlı-topla` -> `Run workflow` -> bitince `mangatr-live-dump` artifact'ini indir.

Üçü de aynı veriyi üretir:
- `popular_p1/p2` : `manga-list-sayfala.html?sort=views`
- `arama_*` : `arama.html?view=sayfalama&icerik=Chainsaw Man / Lady Liar / xyzabc123notfound`
- `detail_*` : `manga-chainsaw-man.html`, `manga-lady-liar.html`
- `external_js` : `manga-tr.min.js` içinden `listKey` arama
- `posts` : `cek/fetch_pages_manga.php` POST `chapter_list_key + offset 0/20/100` ve `manga_cek` fallback
- `sample_reader` : ilk bölüm linkinden `rdm-` / `_fpx` / `sliderCanvas` / `turnstile` / `img_part.php` sayısı

Sonrası bende: dump gelince `MangaTR.kt`, `DDoSGuardInterceptor.kt`, `hasNextPage` ve `TurnstileHelper` entegrasyonu canlıya göre kilitlenecek, `1.6.27` basılacak.
