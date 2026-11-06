package org.koitharu.kotatsu.parsers.site.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.iken.IkenParser
import java.util.EnumSet

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" platformu.
 *
 * Güncel site yapısı (bkz. Drive'daki HTML dökümleri):
 *  - Ana sayfa / keşfet: `/` üzerinde popüler seriler ve son bölümler (API ile aynı veri)
 *  - Seri listesi / arama: `/seriler` + `GET /api/query?page=&perPage=&searchTerm=&genreIds=&seriesType=&seriesStatus=&orderBy=&orderDirection=`
 *  - Seri detayları: `/seriler/{slug}` + `GET /api/post?postSlug={slug}`
 *  - Bölüm listesi: `GET /api/chapters?postId={id}&skip=0&take=900&order=desc&userid=`
 *  - Bölüm okuma: `/reader/{seriesSlug}/{chapterSlug}/{chapterId}`
 *  - Sayfa resimleri: `GET /api/chapter?chapterId={id}` (images dizisi), alternatif olarak
 *    okuyucu sayfasındaki NEXT/RSC verisi veya HTML içindeki img etiketleri.
 *
 * Görseller `/api/uploads/assets/...` altında sunuluyor; site Cloudflare arkasında.
 */
@MangaSourceParser("SADSCANS", "SadScans", "tr")
internal class SadScans(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.SADSCANS, "sadscans.net", pageSize = 24, useAPI = false) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	// iken API'sinin sıralama alanları: updatedAt / totalViews / createdAt / postTitle
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	// sadscans.net'te seriler "/seriler/{slug}" altında, bölümler "/reader/..." altında
	override val seriesPath: String = "seriler"

	override val chapterPath: String = "reader"

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.OTHER,
		),
	)
}
