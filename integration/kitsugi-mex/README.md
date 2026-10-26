# Kitsugi MEX yükleyici ve geçici tek-Türkçe-kaynak düzeltmesi

Bu yama **Kitsugi-Beta uygulamasına** aittir; `.mex` paketinin içinden uygulanamaz.
Android 14+ ve targetSdk 34+ için dinamik kod dosyası salt okunur olmalıdır.
Mevcut v2.4.381 yükleyicisi bunu yapmıyor. `setReadOnly()` okuma iznini kaldırmaz.

`loader-readonly.patch`, pinlenmiş `fd5c3387c2e5f69d00b9e9dee43cc667796556aa`
revizyonundaki APK ve MEX yükleme girişlerine `file.setReadOnly()` ekler.
İndirme işlemi geçici dosya + yeniden adlandırma kullandığından salt-okunur eski
paket güncellemeyi engellemez. Geliştirici bakım notu: yama Git ile bu revizyona
uygulanabilir; ayrıca eklenti paketinin çalıştırılması gerekmez.

Yama ayrıca `MangaDeniziRecoveryPolicy.java` ekler. `getLoadedSources`,
`getSource` ve `loadExtension` üzerinden diğer Türkçe kaynakların aktif listeye
ve doğrudan kaynak erişimine geçmesini önler; yalnız
`com.kitsugi.extension.tr.mangadenizi` kalır. Diğer diller, eklenti dosyaları,
kütüphane, veritabanı ve okuma ilerlemesi silinmez. İki kopya varsa aktif listede
tek MangaDenizi gösterilir. Bu geçici kurtarma politikası uygulama kodunda
merkezidir; kaynak dosyalarını topluca silme veya telefon verilerini sıfırlama
yöntemi değildir.

## Testin kapsamı

`../kitsugi-loader-tests` gerçek yayımlanmış APK'nın yalnız bu çağrılarını
somut bytecode'unda düzelten **imzasız bir CI test kopyası** üretir. Orijinal APK
SHA-256'sı kontrol edilir. Sahte host API modeli kullanılmaz. Değişen gerçek
yükleyici ve Java seçim yardımcısı ek DEX'e taşınır; orijinal sınıf eski DEX'ten
çıkarılır. Bytecode'un dönüş dallarının filtreyi atlamadığını JUnit sınar.

Bu test kopyası telefona kurulacak bir güncelleme olarak yayımlanmaz.
Android 10 orijinal APK ile; Android 14 düzeltilmiş yükleyici test kopyası ile
sınanır. Her ikisinde bütün 53 sayfa gerçek Android bitmap'i olarak çözümlenir
ve tarayıcıda doğrulanmış referans pikselleriyle karşılaştırılır. Android 14'te
başlangıçta yazılabilir MEX'i yükleyicinin kendisi salt okunur yapmalıdır.
Ayrıca sahte ikinci bir TR kaynak ve bir EN kaynak gerçek yükleyici kayıt
tablosuna verilerek TR'nin elenmesi, EN'nin korunması ve önbellekteki kapalı
TR kaynağın doğrudan erişimle tekrar açılamaması kontrol edilir.

**Bu yamanın repoda bulunması, telefondaki APK'nın güncellenmiş olması değildir.**
Android 14 için normal uygulama güncellemesinin, kurulu uygulamayla aynı imza
anahtarıyla uygulama deposundan üretilmesi gerekir. İmzası farklı bir APK'yı
“verileri koruyan güncelleme” diye sunmuyoruz; kaldırma veya güvenlik denetimini
kapatma önermiyoruz. Gölge'ye ait eski `single-source.patch` uygulanmamalıdır:
harici MEX yüklemesini tamamen kapatır.
