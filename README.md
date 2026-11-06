# Kitsugi Manga Plugins (TR)

Depo kataloglarında yalnızca **Manga-TR** (`eu.kanade.tachiyomi.extension.tr.mangatr`, 1.6.24) listelenir. Manga-TR, incelenen liste sayfasında 10.637 seri gösterdiği için geniş bir Türkçe arşiv adayı olarak seçildi; bu sayı manga dışı içerikleri de kapsayabilir ve zamanla değişir. Başka bir kaynağın daha büyük veya daha güvenilir olmadığı doğrulanmış değildir.

## GitHub deposunda etkinleştirme / devre dışı bırakma

Video deposundaki gibi `enabled_plugins.txt` etkin eklentileri `tr/mangatr` biçiminde listeler. Şu an yalnızca Manga-TR etkindir. Diğer eklentiler kaynak kodu ve `prebuilt/apk/` arşivinde saklanır; **arşivde bulunmaları etkin oldukları anlamına gelmez**.

1. GitHub'da **main** dalındaki `enabled_plugins.txt` dosyasına eklentiyi `tr/afroditscans` gibi ekleyin; kapatmak için satırı kaldırın veya `#` ile yorum yapın. Boş liste hiçbir eklenti yayınlamaz.
2. `main` dalına kaydedilen değişiklik sonrasında **Manga eklenti kataloğunu yayınla** GitHub Actions işi listeyi doğrular, katalogları ve APK dizinlerini üretir, testleri çalıştırır ve sonuçları `builds` yayın dalına gönderir. Actions sekmesinde çalışmanın başarılı olduğunu kontrol edin. Manuel yeniden yayın için aynı iş akışı `workflow_dispatch` ile çalıştırılabilir. GitHub Actions yazma izni veya dal koruması engellerse yayın başarısız olur.
3. Uygulamanın katalog adresi `builds` dalını kullanmalıdır; `main` içindeki katalog dosyaları otomatik güncellenmez. Daha önce cihazlara kurulmuş eklentiler bu işlemle devre dışı bırakılmaz.

Yerelde kontrol için `python3 tools/sync_enabled_plugins.py` ve `python3 -m unittest discover -s tests` komutları kullanılabilir.
Arşiv, önceki yayımlanmış sürümlerin bir kopyasıdır; bir eklentiyi tekrar açmak onun güncel veya çalışır olduğunu garanti etmez. Yeni APK derlendiğinde `prebuilt/apk/`, `registry/extensions.json` ve `registry/extensions.v2.json` birlikte güncellenmeli, ardından senkronizasyon ve testler çalıştırılmalıdır.

## Kapsam ve sınırlamalar

- `index.json`, `plugins.json`, `builds/index.json`, `builds/plugins.json` ve `builds/index.v2.json` yalnızca bu eklentiyi duyurur. Yayın dizinleri yalnızca etkin eklentileri içerir; diğer eklentilerin kaynakları ve hazır dosyaları arşivde saklanır. Keiyoushi Gradle yapılandırması aynı etkin eklenti listesini kullanır. Bu değişiklik, kullanıcı cihazlarında önceden kurulmuş eklentileri kaldırmaz veya Kitsugi uygulamasının kendi yerleşik kaynaklarını devre dışı bırakmaz.
- Katalog mevcut 1.6.24 APK'sını gösterir; bu değişiklik yeni bir APK derlemez. APK ve ikon bağlantıları mevcut `builds` Git dalına işaret eder. Bu dosyaların bulunduğu dalda katalogların da yayımlanması gerekir; otomatik yayın, GitHub Actions işi başarıyla tamamlandıktan sonra `builds` dalına yansır.
- Manga-TR'nin arama, bölüm ve görsel uç noktaları; bot koruması, giriş gereksinimi, erişim engelleri ve site değişikliklerinden etkilenebilir. Her serinin mevcut olması veya her cihazda sorunsuz çalışması garanti edilemez. Kitsugi uygulamasının kaynak kodu bu depoda bulunmaz; GitHub üzerindeki Kitsugi-Beta ana dalındaki ilgili sarmalayıcı incelendi, ancak uygulama içi uyumluluk cihazda doğrulanmış değildir.
- Kaynak kodu `keiyoushi-extensions-tr/src/tr/mangatr` dizinindedir. Kod değişirse APK yeniden derlenip uygun sürüm ve katalog bağlantılarıyla yayımlanmalıdır; yalnızca Kotlin dosyasını değiştirmek kurulan APK'yı güncellemez.

## Katalog doğrulaması

Python 3 ile `python3 -m unittest discover -s tests` komutunu çalıştırın. Test, katalogların seçili paket ve sürüm bakımından tutarlı olduğunu ve APK kopyalarının eşleştiğini kontrol eder. Canlı siteyi veya Android uygulamasını test etmez.

İnceleme ve kalan işler: `docs/mangatr/PLAN-VE-DURUM.md`.

## Manga Diyarı (mangadiyari.com)

Manga Diyarı eklentisi sitenin JSON API'sini kullanır (HTML kazımaz) ve 2026-11 itibarıyla canlı
siteye karşı doğrulanmıştır: **1.6.5 / katalog 106005**. APK'lar repodaki kalıcı anahtarla
(`tools/signing/mangadiyari.p12`) imzalanır; böylece yeni sürüm kurulu eklentinin üzerine
güncelleme olarak kurulabilir. Popüler, son güncellemeler, arama, filtreler,
seri detayı, bölüm listesi ve sayfa görselleri her CI koşusunda canlı olarak ölçülür
(`tools/mangadiyari_probe.py`). Ölçüm sonuçları, tür filtrelerinin neden tek etiket gönderdiği ve
bilinen sınırlar: `docs/mangadiyari/DURUM-VE-KANIT.md`.
