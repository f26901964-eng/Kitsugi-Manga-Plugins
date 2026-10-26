# 📚 Kitsugi Manga Plugins & Sources (TR)

Kitsugi Android uygulaması için tüm Türkçe manga, manhwa ve webtoon eklentilerinin / kaynak ayrıştırıcılarının (parser) merkezi kaynak deposudur.

Bu depo, Türkiye'deki manga ve webtoon okuyucularının kullandığı **tüm Türkçe kaynakları** iki ana açık kaynak ekosisteminden (Kotatsu ve Keiyoushi / Mihon) eksiksiz olarak bir araya getirir.

---

## 📂 Dizin Yapısı

```
Kitsugi-Manga-Plugins/
├── kotatsu-parsers-tr/          # 🤖 Kotatsu-Redo tabanlı 121 Türkçe Parser
│   ├── MangaParser.kt           # Temel Kotatsu ayrıştırıcı arayüzü
│   └── site/
│       ├── madara/tr/           # WordPress + WP-Manga tabanlı 70 TR kaynak
│       ├── mangareader/tr/      # MangaReader temalı 38 TR kaynak
│       ├── tr/                  # Özel kodlanmış 7 TR kaynak (ElderManga, SadScans vb.)
│       ├── zeistmanga/tr/       # Blogspot/Blogger tabanlı 4 TR kaynak
│       ├── mmrcms/tr/           # MMRCMS tabanlı 1 TR kaynak (MangaDenizi)
│       └── all/MangaBall.kt     # Çok dilli MangaBall TR ayrıştırıcısı
│
└── keiyoushi-extensions-tr/     # ⚡ Keiyoushi / Mihon tabanlı 76 Türkçe Eklenti
    ├── lib-multisrc/            # Ortak motor kütüphaneleri (UzayManga, Madara, Themesia)
    └── src/tr/                  # 76 bağımsız eklenti paketi (.apk derlenebilir)
        ├── afroditscans/        # AfroditScans (SvelteKit / UzayManga motoru)
        ├── golgebahcesi/        # Gölge Bahçesi
        ├── mangatr/             # Manga-TR
        ├── tortugaceviri/       # Tortuga Çeviri
        ├── hayalistic/          # Hayalistic
        ├── mangaruhu/           # MangaRuhu
        ├── sadscans/            # SadScans
        └── ... (76 eklenti)
```

---

## 🏆 En Sağlam ve En Çok Kullanılan TR Manga Kaynakları Analizi

| Kaynak Adı | Alan Adı | Motor / Altyapı | Durum | Açıklama |
|---|---|---|---|---|
| **Gölge Bahçesi** | `golgebahcesi.com` | Özel (Next.js / React) | 🟢 Aktif | Türkiye'nin en geniş manhwa/aksiyon arşivi. Eski MangaReader kodundan Next.js altyapısına geçti. |
| **Manga-TR** | `manga-tr.com` | Özel API / HTML | 🟢 Aktif | En köklü ve en zengin klasik manga kütüphanesi. |
| **Tortuga Çeviri** | `tortugaceviri.com` | WordPress (Madara) | 🟢 Aktif | One Piece ve Shonen odaklı, en istikrarlı ve kesintisiz kaynak. |
| **AfroditScans** | `afroditscans.com` | SvelteKit (`__data.json`) | 🟢 Aktif | Çok hızlı bölüm güncellemeleri; Kitsugi'nin yerel `SvelteMangaEngine` motoruyla tam uyumlu. |
| **MangaRuhu** | `mangaruhu.com` | WordPress (Madara) | 🟢 Aktif | Popüler romantik, dram ve fantastik manhwa arşivi. |
| **SadScans** | `sadscans.com` | Özel HTML | 🟢 Aktif | Kaliteli çevirileriyle tanınan bağımsız ekip. |

---

## ⚙️ Teknik Hata Notları ve Çözümler (Neden Bazı Kaynaklar Bozulur?)

1. **Kotatsu MangaReader 404 (`KTS-SRC-9004`):**
   - Siteler (örneğin Gölge Bahçesi veya AfroditScans) WordPress MangaReader temasından SvelteKit veya Next.js'e taşındığında eski AJAX adresleri 404 verir.
   - **Çözüm:** Bu siteler için Keiyoushi UzayManga/SvelteKit scraper'ı veya Kitsugi içindeki `SvelteMangaEngine` kullanılmalıdır.

2. **Cloudflare & SSL Handshake (`KTS-SRC-9008` / HTTP 525):**
   - AlucardScans ve ZenithScans gibi sitelerin sunucu taraflı SSL sertifikaları veya alan adı yönlendirmeleri periyodik olarak çöker.
   - **Çözüm:** Domain aynalama (mirror) ve Cloudflare WebView Cookie desteği devreye alınır.
