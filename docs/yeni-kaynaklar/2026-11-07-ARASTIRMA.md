# Yeni Türkçe kaynak araştırması — 7 Kasım 2026

Amaç: aktif 9 eklentinin kapsamadığı, manga/manhwa/webtoon/novel sunan Türkçe
siteleri bulmak ve **çalışır durumdakileri** depoya uygun biçimde eklemek.
Bu dosya araştırmanın kanıtlarını ve kararlarını tutar; tahmine dayalı kod
depoya alınmamıştır (bkz. Manga Diyarı gerekçesi: `docs/mangadiyari/`).

## Yöntem

1. `enabled_plugins.txt` + `registry/` + tüm kaynak ağaçları taranarak her aday
   alan adının depoda gerçekten bulunmadığı doğrulandı (`grep`, 17/17 YOK).
2. Siteler iki bağımsız yoldan yoklandı: `fetch_page` (render edilmiş görünüm)
   ve GitHub runner'dan ham HTTP (`tools/yeni_kaynak_capture.py`, iş akışı:
   `.github/workflows/yeni-kaynak-capture.yml`). Ham HTML kanıtları
   `docs/html-dumps/<site>/` altında, özet `reports/yeni-kaynak-capture.json`.
3. Platform parmak izleri CSS/JS'ten çıkarıldı; okuyucu kilidi, arama ve
   liste davranışları sayfa bazında test edildi.

## Mevcut aktif 9 kaynak (listeye alınmadı)

Manga-TR (manga-tr.com), MangaciX (mangacix.net), Mangitto (mangtto.com),
OkuToon (okutoon.com), SadScans (sadscans.net), Turkmanga (turkmanga.org),
Tortuga Çeviri (tortugaceviri.com), MangaDenizi (mangadenizi.net),
MerlinToon (merlintoon.com). Depodaki ~75 pasif arşiv kaynağı da (uzaymanga,
tenshimanga, anikiga, mangaruhu, webtoonhatti, trmangaoku, hayalistic, …)
"var olan" sayıldı ve aday listeye alınmadı.

## Karar tablosu

| Site | Platform | Katalog | Bölüm | Okuma | Karar |
|---|---|---|---|---|---|
| duckscans.com | Initlise-Next | SSR ✅ | SSR ✅ (JSON-LD `ComicIssue`, 114 link) | ❌ **giriş zorunlu** (Bölüm 1 ve 114'te "Giriş Yapmanız Gerekiyor", 0 Sayfa) | **Eklenemez** — okuma üyeliğe kapalı (Manga Diyarı ilkesi) |
| oblivionscans.com | Initlise-Next | client-render (runner'a kapalı), detay SSR ✅ | SSR ✅ (`/seri/<slug>/bolum/<N>`) | ❌ görseller JS sonrası + `reader-guard`/coins koruması | **Aday (Faz 2)** — WebView katmanı gerekli; 18+ (NSFW) |
| ailurascans.com | Initlise-Next | client-render | SSR ✅ (aynı desen) | render sonrası açık görünüyor (4 Sayfa geldi) ama **CI runner'a 403** | **Aday (Faz 2)** — cihaz/WebView doğrulaması şart |
| lunariscans.com | Initlise-Next | runner 403; `/seri/<slug>` bota 404 kabuğu | desen bilinmiyor | bilinmiyor | **Beklemede** — doğrulanamadı |
| mtlscans.com | bilinmiyor | HTTP 500 (erişilemez) | — | — | **Beklemede** — site erişilemez; 18+ ağırlıklı |
| shadowfansub.com.tr | bilinmiyor | erişilemez (bot koruması) | — | — | **Beklemede** — shadowceviri.blogspot ile karıştırılmamalı |

### Novel siteleri (11 aday) — ayrı yol haritası

Fenrir Scans (fenrirscans.com, Madara benzeri `/novel/` + `/chapter/`,
canlı doğrulandı), Novebo, Epik Novel, Noveloku, Türk Novel, Novel Alem,
Novel Günleri, WebNovelOku, NovelTürk, PYG-KIT, Anka Novel (e-kitaplar.com
ile birleşti). **Bu depoda novel altyapısı yok**: Tachiyomi/Keiyoushi
eklentileri görsel bölüm akışı bekler; Kotatsu ağacındaki parserlar da manga
parserıdır ve metin bölümleri sayfalara çevirmez. Novel desteği yalnızca
ayrı bir metin-okuyucu hattıyla (Kotatsu novel parserları veya Kitsugi
tarafında yeni kaynak tipi) eklenebilir; kod yazmak çalışmayacak kod
olmak demekti, bu yüzden yazılmadı.

## Initlise-Next platformu — doğrulanan teknik bulgular

Aynı beyaz-etiket yazılım: duckscans, oblivionscans, ailurascans, lunariscans.
(Mevcut InitManga temasındaki WordPress siteleriyle akraba ama **wp-json API'si
yok** — `wp-json/wp/v2/manga` ve `wp-json/initlise/v1/search` 404 döner; bu
yüzden `theme = "initmanga"` bu ailede çalışmaz.)

- Seri: `/seri/<slug>`, bölüm: `/seri/<slug>/bolum/<N>` (N sayısal).
- Kapaklar: `/uploads/seri-kapak/<slug>.webp?v=…`, banner `/uploads/seri-banner/…`.
- Next.js App Router: `self.__next_f` uçuş verisi, turbopack chunk'ları,
  `?dpl=` dağıtım kimliği, `jsx-*` + Tailwind sınıfları.
- Seri detay sayfası SSR: bölüm linkleri ve (duck'ta) JSON-LD `ComicIssue`
  listesi sunucuda geliyor; kart başlığı `img[alt]` içinde.
- `/series` listesi anonim istekte client-render (runner boş görüyor).
- Okuyucu: anonim HTML'de görsel URL'si yok; chunk'larda `"/api/reader-guard/verify"`,
  `"/api/coins/unlock"`, `"/api/vip/status"` bulundu → görseller hidrasyon
  sonrası korumalı uçlardan geliyor.
- Sitemap: `/sitemap.xml` → `sitemap-series.xml` + `sitemap-chapters.xml?page=N`
  (oblivion ve ailura'da doğrulandı) → katalog taraması için hazır veri.
- Arama uç noktası bulunamadı; küçük katalogda liste üzerinden yerel filtre
  uygulanabilir.

Erişim matrisi (7 Kasım 2026):

| Site | fetch_page | GitHub runner |
|---|---|---|
| duckscans | 200 | 200 |
| oblivionscans | 200 | 200 |
| ailurascans | 200 | **403** |
| lunariscans | 200 (ana sayfa) | **403** |
| mtlscans | 500 | (denenmedi) |
| shadowfansub | hata | (denenmedi) |

## Neden bugün hiçbiri etkinleştirilmedi

- DuckScans'te okuma girişe kapalı → kataloğa girmesi kullanıcıyı boşuna
  kaynak listesinde tutar (Manga Diyar'ın arama gerekçesiyle aynı ilke).
- Oblivion/Ailura'da görseller JS/guard arkasında → HTML parser'ı tek başına
  siyah sayfa üretir. Repodaki emsal (SadScans, OkuToon, MerlinToon), bu tür
  sitelerin ancak WebView katmanı + canlı cihaz doğrulamasıyla eklendiğini
  gösteriyor. Seçicileri görmeden yazmak çalışmayan kod olur; depo kültürü
  bunu kabul etmiyor.
- Etkinleştirme akışı ayrıca registry + APK + `enabled_plugins.txt`
  üçlüsünü tutarlı gerektirir (`tools/sync_enabled_plugins.py`).

## Faz 2 planı (bir sonraki iş)

1. **Oblivion Scans** ile başla (runner'dan tamamen erişilebilir tek aday):
   - `sitemap-series.xml` tabanlı katalog + `/seri/<slug>` SSR detay/bölüm
     parser'ı (yukarıdaki desenlerle, kanıt `docs/html-dumps/oblivionscans/`).
   - Okuyucu için OkuToon/SadScans emsallerindeki gibi üç kademeli taşıma:
     RSC uçuş verisi → korumalı API (reader-guard davranışı) → WebView.
   - `ContentWarning.NSFW` + `nsfw: 1` ile.
2. Ailura'da aynı kodu dene; runner 403 verdiği için doğrulama yalnız
   cihaz/WebView oturumuyla yapılabilir.
3. Lunari/MTL/Shadow için dönemsel yeniden yoklama
   (`gh workflow run yeni-kaynak-capture.yml` benzeri; dispatch izni
   açılırsa input ile site seçilebilir).
4. Novel için ayrı karar: metin kaynağı altyapısı (Kotatsu novel parserları
   veya Kitsugi'de yeni kaynak tipi).

## Bu araştırmada depoya girenler

- `tools/yeni_kaynak_capture.py` + `.github/workflows/yeni-kaynak-capture.yml`
  (kanıt yakalama; rapor `reports/yeni-kaynak-capture.json`).
- `docs/html-dumps/{oblivionscans,duckscans}/` ham HTML kanıtları.
- `tools/candidate_live_probe.py`: `/bolum/<N>` sayısal bölüm deseni ve
  Initlise-Next okuyucu kapsayıcıları için güncellendi.
- Bu dosya.
