plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 1
    libVersion = "1.6"

    // Initlise-Next platformu: Next.js App Router + /api/series REST ucu.
    // Kanıt: docs/html-dumps/{duckscans,oblivionscans}/ ve
    // docs/yeni-kaynaklar/2026-11-07-ARASTIRMA.md
    deeplink {
        path("/seri/..*")
    }
}
