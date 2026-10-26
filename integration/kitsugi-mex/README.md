# Kitsugi MEX yükleyici düzeltmesi — uygulama tarafı

Bu yama **Kitsugi-Beta uygulamasına** aittir; `.mex` paketinin içinden uygulanamaz.
Android 14+ ve targetSdk 34+ için dinamik kod dosyası salt okunur olmak zorundadır.
Mevcut v2.4.381 yükleyicisi bunu yapmıyor. `setReadOnly()` okuma iznini kaldırmaz.

`loader-readonly.patch`, pinlenmiş `fd5c3387c2e5f69d00b9e9dee43cc667796556aa`
revizyonundaki iki sınıf yükleme girişine `file.setReadOnly()` ekler. İndirilen
ve dosyadan yüklenen MEX/APK'lar aynı yükleyiciden geçtiği için iki kurulum yolu
korunur. Eski eklentiler, kütüphane, veritabanı ve okuma ilerlemesi silinmez.

## Testin kapsamı

`../kitsugi-loader-tests` gerçek yayımlanmış APK'nın yalnız bu iki metoduna aynı
çağrıyı ekleyen **imzasız bir CI test kopyası** üretir. Sahte host API modeli
kullanmaz. Bu kopya telefona kurulacak bir güncelleme olarak yayımlanmaz.
Android 10 orijinal APK ile; Android 14 düzeltilmiş yükleyici test kopyası ile
sınanır. Her ikisinde bütün 53 sayfa gerçek Android bitmap'i olarak çözümlenir
ve tarayıcıda doğrulanmış referans pikselleriyle karşılaştırılır.

**Bu yamanın repoda bulunması, telefondaki APK'nın güncellenmiş olması değildir.**
Android 14 için normal uygulama güncellemesinin, kurulu uygulamayla aynı imza
anahtarıyla uygulama deposundan üretilmesi gerekir. İmzası farklı bir APK'yı
“verileri koruyan güncelleme” diye sunmuyoruz; kaldırma veya güvenlik denetimini
kapatma önermiyoruz. Gölge'ye ait eski `single-source.patch` uygulanmamalıdır:
harici MEX yüklemesini tamamen kapatır.
