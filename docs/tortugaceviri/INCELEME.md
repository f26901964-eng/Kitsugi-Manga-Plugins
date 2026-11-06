# Tortuga Çeviri incelemesi

Drive'daki 6 Kasım 2026 tarihli örnekler: 85 ana sayfa, 86 `sa` araması,
87 Doom seri sayfası, 88 Doom 1. bölüm. Seri sayfasında açıklama Madara'nın
`description-summary`/`summary__content` alanında, bölümler `li.wp-manga-chapter`
öğelerinde ve tarih örneği `28 Haziran 2026` biçimindedir. Okuyucuda sıralı
`div.page-break` içinde `img.wp-manga-chapter-img` ve gerçek `src` değerleri vardır.
Önceki `MMM d, yyy` tarih biçimi bu örneklerle uyuşmuyordu; `d MMMM yyyy` olarak
düzeltildi. Madara altyapısı Türkçe göreli tarihleri de destekler.

Önceki `reports/candidate-live-probe.json` raporunda siteye erişim, One Piece
seri/bölüm sayfası ve üç görsel kontrolü başarılıydı; bu rapor eklentinin kendisini,
arama AJAX uç noktasını, sayfalama veya cihaz uyumluluğunu kanıtlamaz.
Arşivdeki 1.6.57 APK'sı kaynak değişikliğini içermez. Yeni APK derlenip doğrulanmadan
ve katalog güncellenmeden kaynak değişikliği kullanıcı cihazlarına ulaşmaz.

## Canlı kontrol ve yayın durumu

[GitHub Actions 42300817236](https://github.com/f26901964-eng/Kitsugi-Manga-Plugins/actions/runs/42300817236): AJAX popüler/son listelerinde 25'er kart ve kapak, popüler listede farklı ikinci sayfa, `Doom` aramasında doğru seri, seri sayfasında açıklama ve 14 bölüm, okuyucuda 48 görsel bağlantısı tespit edildi; ilk üç görsel indirilip JPEG/PNG olarak doğrulandı. Ham sayılara `reports/tortugaceviri-live.json` dosyasından bakılabilir. Bu kontrol, Android içindeki gerçek Kotlin parser'ı veya her seriyi çalıştırmaz.

APK 1.6.58 (106058) CI'da derlendi; bu çalışma dalında etkin listeye ve kataloglara eklendi. Katalog testleri başarılıdır. Genel `builds` dağıtım dalı ancak bu değişiklikler `main`e alınıp yayın iş akışı çalıştığında güncellenir. Cihazda kurulum/okuma testi yapılmadı.

**İmza farkı:** Arşivdeki 1.6.57 ile yeni APK farklı sertifikalarla imzalıdır. Eski Tortuga APK'sı cihazda kuruluysa Android doğrudan güncellemeyi reddedebilir; önce kullanıcı verilerini yedekleyip eski eklentiyi kaldırmak ve yeni sürümü kurmak gerekebilir. Kaldırma ve yeniden kurmanın uygulamanın kaynak/kütüphane verilerine etkisi kullanılan uygulamaya bağlıdır.
