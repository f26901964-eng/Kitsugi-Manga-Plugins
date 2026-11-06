package org.koitharu.kotatsu.parsers.site.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.iken.IkenParser

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" platformu.
 *
 * Güncel site yapısı (canlı siteden çıkarılan JS chunk'ları + Drive HTML dökümleri):
 *  - Seri listesi / arama: `GET /api/trpc/_s4d7g2k9m0.getSeries?batch=1&input={"0":{"json":{"search":...,"page":...,"limit":15}}}`
 *    -> `[{result:{data:{json:[{id,href,thumb,name,status,views,ratingAverage,ratingCount,type}]}}}]`
 *  - Türler:               `GET /api/trpc/tur.getAll` -> `[{id,name,slug}]`
 *  - Seri detay + bölümler: `GET /api/trpc/_n3p6q1v8x5.getSeriesData?batch=1&input={"0":{"json":{"sef":<slug>,"page":1,"limit":10}}}`
 *    -> `{series:{...}, chapters:[{chap_id,name,no,...}], pagination:{totalPages,...}}`
 *  - Bölüm okuma: `/reader/{seriesSlug}/{chapterSlug}/{chapterId}` (kısa form `/reader/{chapterId}` de çalışır)
 *  - Sayfa resimleri: `/api/uploads/assets/series/{seriesId}/{chapterId}/images/...` altında;
 *    bölüm görselleri tRPC chapter endpoint'inden (bkz. IkenParser.chapterImageProcedures),
 *    yedek olarak okuyucu sayfasının HTML/RSC verisinden alınır.
 *
 * Router adları canlı sitede build-time hash (örn. `_s4d7g2k9m0`); site Cloudflare arkasında.
 */
@MangaSourceParser("SADSCANS", "SadScans", "tr")
internal class SadScans(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.SADSCANS, "sadscans.net", pageSize = 15) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	// seriler "/seriler/{slug}", bölümler "/reader/..." altında (IkenParser varsayılanları)
	override val seriesPath: String = "seriler"

	override val chapterPath: String = "reader"
}
