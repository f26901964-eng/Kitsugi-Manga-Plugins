# Kitsugi Manga Plugins (TR)

Etkin eklenti listesinde **Manga-TR**, **MangaciX**, **Mangitto**, **OkuToon**, **SadScans**, **Turkmanga**, **Tortuga Çeviri**, **MangaDenizi**, **MerlinToon**, **DuckScans**, **LunariScans**, **AiluraScans** ve **Oblivion Scans** vardır. Turkmanga kaynağı `turkmanga.org` içindir; aynı adlı olmayan `trmangaoku.com` eklentisiyle karıştırılmamalıdır. `Turkmanga parser verification and APK release` GitHub Actions çalışması başarılı oldu: parser testleri geçti, Turkmanga `1.6.2` (`106002`) APK'sı derlendi ve o çalışmada altı etkin kaynak için bu dala ait kataloglar eşitlendi. Genel `builds` dağıtım dalı, değişiklikler `main` dalına alınıp ana katalog yayın iş akışı çalışınca güncellenir. **Manga Diyarı devre dışıdır** (aşağıya bakın). Manga-TR, incelenen liste sayfasında 10.637 seri gösterdiği için geniş bir Türkçe arşiv adayı olarak seçildi; bu sayı manga dışı içerikleri de kapsayabilir ve zamanla değişir. Başka bir kaynağın daha büyük veya daha güvenilir olmadığı doğrulanmış değildir.

## MerlinToon

Bu çalışma dalında MerlinToon `1.6.9` etkinleştirildi; arama ve dilimli okuyucu
HTML'i için uyarlama yapıldı ve APK CI'da derlendi. Canlı site kontrolü GitHub
runner'ında HTTP 403 aldığı için gerçek cihazda çalışma henüz doğrulanmadı.
Katalog yayını ve ayrıntılar: [MerlinToon durumu](docs/merlintoon/DURUM.md).

## Tortuga Çeviri

Bu çalışma dalında Tortuga Çeviri `1.6.58` (`106058`) etkinleştirildi ve APK/kataloglar eşitlendi. GitHub Actions canlı kontrolünde keşif için ilk iki sayfa, son güncellemeler, Doom araması, seri açıklaması ve 14 bölüm görüldü; 48 sayfadan ilk üç görsel indirilip doğrulandı. Bu, eklentinin Android cihazında uçtan uca test edildiği veya bütün serilerin çalıştığı anlamına gelmez. [İnceleme ve imza uyarısı](docs/tortugaceviri/INCELEME.md). Genel `builds` dağıtımına yansıması için değişikliklerin `main` dalına alınması ve katalog yayın iş akışının tamamlanması gerekir.

## GitHub deposunda etkinleştirme / devre dışı bırakma

`enabled_plugins.txt` etkin eklentileri `tr/mangatr` biçiminde listeler. Bu dalda Manga-TR, MangaciX, Mangitto, OkuToon, SadScans, Turkmanga, Tortuga Çeviri, MangaDenizi, MerlinToon ve Epik Novel seçilidir; Manga Diyarı devre dışıdır. Epik Novel (`tr/epiknovel`) 7 Kasım 2026'da eklendi: keşfet/arama/detay/açıklama/çevirmen/görsel sözleşmesi dump'larla (`docs/html-dumps/epiknovel/`) ve canlı koşuyla (`tests/epiknovel_runtime/`) doğrulanır; metin bölümleri eklenti içinde PNG sayfaya çevrilerek görüntü okuyucuda açılır (`docs/epiknovel/DURUM.md`). Turkmanga'nın kaynak modülü, etkinleştirmesi ve `1.6.2` APK'sı CI tarafından doğrulanıp bu dala eklendi. Yerel çalışma alanında Java/Android SDK olmasa da GitHub Actions derlemesi başarılıdır. Diğer eklentiler kaynak kodu ve `prebuilt/apk/` arşivinde saklanır; **arşivde bulunmaları etkin oldukları anlamına gelmez**.

1. GitHub'da **main** dalındaki `enabled_plugins.txt` dosyasına eklentiyi `tr/afroditscans` gibi ekleyin; kapatmak için satırı kaldırın veya `#` ile yorum yapın. Boş liste hiçbir eklenti yayınlamaz.
2. Değişiklikler `main` dalına alındığında **Manga eklenti kataloğunu yayınla** iş akışı katalog ve APK dosyalarını `builds` dağıtım dalına gönderir. Turkmanga'nın test/derlemesi bu özellik dalında başarıyla tamamlandı; APK ve eşitlenmiş kataloglar dalda mevcut. Actions sekmesinde release çalışmasını ve `main` sonrasındaki katalog yayınını doğrulayın. GitHub Actions yazma izni veya dal koruması engellerse yayın başarısız olur.
3. Uygulamanın katalog adresi `builds` dalını kullanmalıdır; `main` içindeki katalog dosyaları otomatik güncellenmez. Daha önce cihazlara kurulmuş eklentiler bu işlemle devre dışı bırakılmaz.

Yerelde kontrol için `python3 tools/sync_enabled_plugins.py` ve `python3 -m unittest discover -s tests` komutları kullanılabilir.
Arşiv, önceki yayımlanmış sürümlerin bir kopyasıdır; bir eklentiyi tekrar açmak onun güncel veya çalışır olduğunu garanti etmez. Yeni APK derlendiğinde `prebuilt/apk/`, `registry/extensions.json` ve `registry/extensions.v2.json` birlikte güncellenmeli, ardından senkronizasyon ve testler çalıştırılmalıdır.

## Kapsam ve sınırlamalar

- `index.json`, `plugins.json`, `builds/index.json`, `builds/plugins.json` ve `builds/index.v2.json` bu daldaki dokuz etkin kaynağı duyurur. `1.6.2` Turkmanga APK'sı ile katalog/registry sürüm bilgileri CI tarafından üretildi, doğrulandı ve bu dala eklendi. Resmî `builds` dağıtım dalına aktarım için değişikliklerin `main` dalına alınması ve ana katalog yayın iş akışının çalışması gerekir. Yayın dizinleri yalnızca etkin eklentileri içerir; arşivde bulunmaları tek başına etkin oldukları anlamına gelmez. Bu değişiklik, kullanıcı cihazlarında önceden kurulmuş eklentileri kaldırmaz veya Kitsugi uygulamasının kendi yerleşik kaynaklarını devre dışı bırakmaz.
- Turkmanga'nın güncel release'i `1.6.2` (`106002`) olarak derlendi. CI parser birim testlerini çalıştırdı, release APK'sını oluşturdu ve altı etkin kaynaklı katalog eşitliğini doğruladı. Canlı `turkmanga.org`/cihaz kurulumu uçtan uca testi bu iş akışında yapılmadı.
- Manga-TR'nin arama, bölüm ve görsel uç noktaları; bot koruması, giriş gereksinimi, erişim engelleri ve site değişikliklerinden etkilenebilir. Her serinin mevcut olması veya her cihazda sorunsuz çalışması garanti edilemez. Kitsugi uygulamasının kaynak kodu bu depoda bulunmaz; GitHub üzerindeki Kitsugi-Beta ana dalındaki ilgili sarmalayıcı incelendi, ancak uygulama içi uyumluluk cihazda doğrulanmış değildir.
- Kaynak kodu `keiyoushi-extensions-tr/src/tr/mangatr` dizinindedir. Kod değişirse APK yeniden derlenip uygun sürüm ve katalog bağlantılarıyla yayımlanmalıdır; yalnızca Kotlin dosyasını değiştirmek kurulan APK'yı güncellemez.

## Katalog doğrulaması

Python 3 ile `python3 -m unittest discover -s tests` komutunu çalıştırın. Test, katalogların seçili paket ve sürüm bakımından tutarlı olduğunu ve APK kopyalarının eşleştiğini kontrol eder. Canlı siteyi veya Android uygulamasını test etmez.

İnceleme ve kalan işler: `docs/mangatr/PLAN-VE-DURUM.md`.

## Manga Diyarı (mangadiyari.com) — DEVRE DIŞI

Manga Diyarı eklentisi **yayın kataloğundan çıkarıldı** (`enabled_plugins.txt` içinde yorum satırı).
Kaynak kodu ve APK arşivi (`prebuilt/apk/`) duruyor; yayın kataloğunda (`index.json`,
`builds/index.json`, `builds/index.v2.json`) görünmüyor ve uygulamaya kurulmuyor.

Neden: sitenin arama uç noktası **yalnız Türkçe başlık metnine** bakıyor, `alt_names` alanındaki
orijinal (İngilizce/Asya) isimleri hiç aramıyor. Ölçüm: `search=Cultivator` → 0, `search=Masin` → 0,
`search=Pinnacle` → 0; `q=` parametresi tamamen yok sayılıyor. Kullanıcı bir mangayı orijinal adıyla
aradığında sonuç dönmediği için eklenti pratikte kullanılamıyordu. Site yalnız 30 seri barındırıyor.

Tekrar açmak için `enabled_plugins.txt` dosyasına `tr/mangadiyari` satırını ekleyin; katalog yayını
onu yeniden listeler. Teknik ayrıntılar: `docs/mangadiyari/DURUM-VE-KANIT.md`.

## SadScans (sadscans.net) — 1.6.10 (106010)

- Arama, tür/durum/sıralama filtreleri, seri detayı ve okuyucu, sitenin güncel HTML/JS sözleşmesine
  göre yeniden yazıldı; üç kademeli taşıma (tRPC → arayüz → WebView) ve Cloudflare davranışı
  belgelendi.
- Kök nedenler, yapılan düzeltmeler, testler ve dürüst sınırlar: `docs/sadscans/COZUM-2026-11-06.md`.
- Derleme CI'da yeşil; APK `prebuilt/apk/tachiyomi-tr.sadscans-v1.6.10.apk` (60.463 bayt),
  katalog kayıtları `1.6.10` / `106010`.

## Yeni kaynak araştırması ve Initlise-Next ailesi (7 Kasım 2026)

Aktif 9 kaynağın kapsamadığı 17 Türkçe site incelendi; dördü aynı platform
(Initlise-Next — Next.js App Router + `/api/series` REST ucu) olduğu için ortak
`lib-multisrc/initlisenext` temasıyla **DuckScans, LunariScans, AiluraScans ve
Oblivion Scans (NSFW)** eklendi ve derlendi (`1.6.2` / `106002`):

- Katalog/arama: `/api/series?page=N`, `?sort=createdAt`, `?q=<sorgu>` — uçlar
  GitHub runner'ından 200 ile doğrulandı.
- Bölümler ve sayfalar: seri sayfasının Next.js uçuş verisindeki
  `initialChapters` dizisi tüm bölümleri sayfa yollarıyla birlikte gömer.
  Sitenin okuyucu kilidi ("Giriş Yapmanız Gerekiyor") yalnız okuyucu
  arayüzünü ilgilendirir; gömülü veri anonim oturumda da gelir. Bu yüzden
  Oblivion Scans için ayrı hesap girişi gerekmez — giriş Discord OAuth'a
  bağlıdır ve yalnız coin ile kilitlenip uçuşta sayfası bulunmayan
  bölümlerde açıklayıcı hata gösterilir.
- Kanıtlar: `docs/html-dumps/{duckscans,lunariscans,ailurascans,oblivionscans}/browser`
  (kullanıcının kendi tarayıcı kayıtları; 79 ve 114 bölümlük serilerde
  sayfaların tam gömülmesi doğrulandı) ve `docs/yeni-kaynaklar/2026-11-07-ARASTIRMA.md`.
- Sınırlar: AiluraScans ve LunariScans GitHub runner IP'sine 403 verir
  (Cloudflare); derleme CI'da yeşildir, canlı doğrulama cihazdadır.
  Novel siteleri için depoda metin kaynağı altyapısı yoktur (bkz. araştırma belgesi).
- Yayın iş akışı: `initlise-release.yml` (spotlessApply → derleme+test →
  `prepare_extension_release.py` çok modüllü yayın → katalog senkronu).


