# SadScans (sadscans.net) — güncel site desteği

Tarih: 2026-11-06

## Durum

`kotatsu-parsers-tr/src/main/kotlin/org/koitharu/kotatsu/parsers/site/tr/SadScans.kt` tamamen yeniden yazıldı.
Eski sürüm eski `sadscans.com` yapısını (`.series-list`, `.chap-section`, `.swiper-slide`) hedef alıyordu;
site artık `https://sadscans.net` üzerinde Next.js + tRPC tabanlı "iken" platformunda çalışıyor.

Yeni koleksiyon: `site/iken/IkenParser.kt` (Kotatsu-Redo'nun SanaScans/IkenParser yaklaşımının bu repoya portu).

## Kanıt — güncel site yapısı (kullanıcının Drive klasöründeki HTML dökümleri, `message (77-80).txt`)

- Ana sayfa: `https://sadscans.net` (`<title>SADSCANS - Türkçe Manga Oku...`), nav: `/`, `/seriler`, `/duyurular`...
- Seri listesi: `https://sadscans.net/seriler` (`<title>Seriler - SADSCANS ...`)
- Seri sayfası: `/seriler/{slug}` (örn. `/seriler/one-piece`)
- Okuyucu: `/reader/{seriesSlug}/{chapterSlug}/{chapterId}`
  (örn. `/reader/chainsaw-man/1-bolum-kopek-ve-testere/609850233655f`, dökümde RSC parametreleriyle doğrulandı)
- Görseller: `/api/uploads/assets/series/{seriesId}/.../thumb_*.jpg`, bölüm kapakları
  `/api/uploads/assets/series/{seriesId}/{chapterId}/images/thumb_*.png`
- Site Cloudflare arkasında (dökümlerde `cdn-cgi/challenge-platform` ve `cloudflareinsights` betikleri var)
- Sayfalar client-side render ediliyor; içerik tRPC üzerinden geliyor.

## Kullanılan API (iken platformu, Kotatsu-Redo kotatsu-parsers-redo SanaScans PR #234 ile aynı)

- Liste/arama: `GET /api/query?page=&perPage=&searchTerm=&genreIds=&seriesType=&seriesStatus=&orderBy=&orderDirection=` → `{posts:[{id,slug,postTitle,featuredImage,author,postContent,alternativeTitles,genres,seriesStatus,isNovel,seriesType,hot}]}`
- Türler: `GET /api/genres` → `[{id,name}]`
- Detay: `GET /api/post?postSlug={slug}` → `{post:{...}}`
- Bölümler: `GET /api/chapters?postId={id}&skip=0&take=900&order=desc&userid=` → `{post:{chapters:[{id,slug,number,title,createdAt,isLocked,isAccessible}]}}`
- Sayfa resimleri: `GET /api/chapter?chapterId={id}` → `{chapter:{images:[{order,url}]}}`;
  yedek olarak okuyucu HTML'indeki NEXT/RSC JSON'u (`getNextJson("images")`) ve `img` etiketleri.

## Eklenti aktif mi?

Kitsugi-Beta `KotatsuExtensionAdapter.kt` içinde `if (parserSource.isBroken()) continue` ve dil filtresi var;
SadScans `@Broken` değil ve `lang="tr"`, bu yüzden aktif. Sorun sitenin eskimesiydi, parser güncellendi.

## Notlar

- Sıralama: UPDATED (updatedAt), POPULARITY (totalViews — ana sayfa/keşfet), NEWEST (createdAt), ALPHABETICAL(_DESC) (postTitle).
- Roman (novel) kayıtları `isNovel`/`seriesType=NOVEL` ile eleniyor.
- Kilitli bölümler (`isLocked`/`isAccessible=false`) liste dışı bırakılıyor.
- Cloudflare: `ConfigKey.InterceptCloudflare(defaultValue = true)` eklendi.
- Bu dalda derleme yapılmadı (sandbox'ta JDK/Android SDK yok); Kotlin kaynaklari yerel `kotatsu-parsers-tr` API'lerine göre elle doğrulandı.
