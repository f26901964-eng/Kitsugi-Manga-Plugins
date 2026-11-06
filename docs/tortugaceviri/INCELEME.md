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
