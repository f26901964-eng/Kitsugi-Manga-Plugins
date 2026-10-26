# MangaDenizi — native Kitsugi MEX

Paket `MangaSource` arayüzünü doğrudan uygular. Gerekli gerçek host API, v2.4.381
kaynak pininden blob SHA denetimiyle indirilir ve **compile-only** kullanılır.
İki host API dosyası, Kotlin/OkHttp/coroutines/Jsoup çalışma zamanları ve bölüm
resimleri ZIP'e eklenmez. Arayüzün bütün varsayılan üyeleri açıkça uygulanır.

Kullanıcı kurulum durumu: [ana README](../../README.md). Android 14+ için host
MEX/APK yükleyicisinin salt-okunur dosya düzeltmesini içermesi gerekir; bu MEX
kurulu uygulamanın yükleyicisini içeriden onaramaz.

Geliştirici derlemesi (JDK17, Android SDK, Maven erişimi ve GitHub CLI):

```sh
python3 extensions/mangadenizi/prepare-contract.py
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar" \
  bash kotatsu-parsers-tr/gradlew -p extensions/mangadenizi :extension:test :extension:exportClasspath
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar" \
  python3 extensions/mangadenizi/build-mex.py
```

Bu komutlar son kullanıcı kurulum talimatı değildir. Son kullanıcıya CI'da test
edilmiş MEX ve tek girişli V2 `index.json` yayımlanır. Kaynak ağacındaki modern
Keiyoushi APK'sı bu native MEX'in yerine önerilmez.
