# Kitsugi Manga Plugins (TR)

Etkin eklenti listesinde **Manga-TR**, **MangaciX**, **Mangitto**, **OkuToon** ve bu çalışmada eklenen **Turkmanga** vardır. Turkmanga kaynağı `turkmanga.org` içindir; aynı adlı olmayan `trmangaoku.com` eklentisiyle karıştırılmamalıdır. Turkmanga'nın APK ve yayın kataloglarına eklenmesi `Turkmanga parser verification and APK release` GitHub Actions iş akışındaki test/derleme başarılı olduktan sonra tamamlanır. **Manga Diyarı devre dışıdır** (aşağıya bakın). Manga-TR, incelenen liste sayfasında 10.637 seri gösterdiği için geniş bir Türkçe arşiv adayı olarak seçildi; bu sayı manga dışı içerikleri de kapsayabilir ve zamanla değişir. Başka bir kaynağın daha büyük veya daha güvenilir olmadığı doğrulanmış değildir.

## GitHub deposunda etkinleştirme / devre dışı bırakma

`enabled_plugins.txt` etkin eklentileri `tr/mangatr` biçiminde listeler. Bu dalda Manga-TR, MangaciX, Mangitto, OkuToon ve yeni Turkmanga seçilidir; Manga Diyarı devre dışıdır. Turkmanga'nın kaynak/listedeki etkinleştirmesi eklendi. Çalışma alanında Java/Android SDK olmadığı için APK burada üretilemedi; yeni `Turkmanga parser verification and APK release` iş akışı parser testlerini, APK derlemesini ve katalog eşitlemesini yapar. Diğer eklentiler kaynak kodu ve `prebuilt/apk/` arşivinde saklanır; **arşivde bulunmaları etkin oldukları anlamına gelmez**.

1. GitHub'da **main** dalındaki `enabled_plugins.txt` dosyasına eklentiyi `tr/afroditscans` gibi ekleyin; kapatmak için satırı kaldırın veya `#` ile yorum yapın. Boş liste hiçbir eklenti yayınlamaz.
2. Genel eklenti seçimi `main` dalına alındığında **Manga eklenti kataloğunu yayınla** iş akışı yayın dosyalarını `builds` dalına gönderir. Yeni Turkmanga için önce **Turkmanga parser verification and APK release** iş akışı test/derlemeyi tamamlayıp APK'yı ve katalog dosyalarını aynı dala kaydeder; ardından ana katalog yayını çalıştırılmalıdır. Actions sekmesinde her iki işin başarılı olduğunu kontrol edin. GitHub Actions yazma izni veya dal koruması engellerse yayın başarısız olur.
3. Uygulamanın katalog adresi `builds` dalını kullanmalıdır; `main` içindeki katalog dosyaları otomatik güncellenmez. Daha önce cihazlara kurulmuş eklentiler bu işlemle devre dışı bırakılmaz.

Yerelde kontrol için `python3 tools/sync_enabled_plugins.py` ve `python3 -m unittest discover -s tests` komutları kullanılabilir.
Arşiv, önceki yayımlanmış sürümlerin bir kopyasıdır; bir eklentiyi tekrar açmak onun güncel veya çalışır olduğunu garanti etmez. Yeni APK derlendiğinde `prebuilt/apk/`, `registry/extensions.json` ve `registry/extensions.v2.json` birlikte güncellenmeli, ardından senkronizasyon ve testler çalıştırılmalıdır.

## Kapsam ve sınırlamalar

- `index.json`, `plugins.json`, `builds/index.json`, `builds/plugins.json` ve `builds/index.v2.json` yalnızca bu eklentiyi duyurur. Yayın dizinleri yalnızca etkin eklentileri içerir; diğer eklentilerin kaynakları ve hazır dosyaları arşivde saklanır. Keiyoushi Gradle yapılandırması aynı etkin eklenti listesini kullanır. Bu değişiklik, kullanıcı cihazlarında önceden kurulmuş eklentileri kaldırmaz veya Kitsugi uygulamasının kendi yerleşik kaynaklarını devre dışı bırakmaz.
- Turkmanga için beklenen ilk sürüm `1.6.1`'dir. Bu çalışma ortamında Java/Android SDK bulunmadığı için kurulumluk APK henüz derlenmedi; kaynak `enabled_plugins.txt` listesine alındı, registry kaydı eklendi ve CI release iş akışı hazırlandı. APK/katalog yayınını doğrulamak için bu iş akışının başarılı olması gerekir. `index.json`/`builds` katalogları yalnızca üretilmiş APK ile eşitlendiğinde kullanıcıya Turkmanga'yı sunar.
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
