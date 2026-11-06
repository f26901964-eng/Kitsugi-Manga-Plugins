package org.koitharu.kotatsu.parsers.site.iken

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Base parser for sites running the current "iken" manga platform as a Next.js + tRPC app
 * (e.g. sadscans.net). All data is read from the tRPC HTTP API under `/api/trpc`:
 *
 *  - series list / search: `<searchRouter>.getSeries?batch=1&input={"0":{"json":{search,page,limit,...}}}`
 *    -> `[{result:{data:{json:[{id,href,thumb,name,status,views,ratingAverage,ratingCount,type}]}}}]`
 *  - genres:               `tur.getAll` -> `[{id,name,slug}]`
 *  - series + chapters:    `<seriesRouter>.getSeriesData` with `{sef,page,limit}`
 *    -> `{series:{...}, chapters:[{chap_id,name,no,...}], pagination:{totalPages,...}}`
 *  - chapter pages:        chapter router (see [chapterImageProcedures]); as a fallback the
 *    reader page HTML is scraped for `img[src*='/api/uploads']`.
 *
 * tRPC batch envelope: request query `?batch=1&input=<urlencoded {"0":{"json":...}}>`,
 * response is a JSON array whose first element is `{result:{data:{json:<payload>}}}`.
 * Router names are build-time hashes on the live site, so they are configurable.
 */
internal abstract class IkenParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 15,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected val defaultDomain: String = domain

	/** tRPC router (possibly a build hash) serving series list + search via `getSeries`. */
	protected open val seriesListRouter: String = "_s4d7g2k9m0"

	/** tRPC router (possibly a build hash) serving series detail + chapter list via `getSeriesData`. */
	protected open val seriesDetailRouter: String = "_n3p6q1v8x5"

	/** tRPC router serving the genre list via `getAll`. */
	protected open val genreRouter: String = "tur"

	/** Candidate `router.procedure` pairs used to fetch chapter page images, tried in order. */
	protected open val chapterImageProcedures: List<String> = listOf(
		"$seriesDetailRouter.getChapterData",
		"$seriesDetailRouter.getChapter",
		"$seriesDetailRouter.getPages",
		"$seriesListRouter.getChapterData",
		"chapterSingle.getChapterData",
		"chapterSingle.getChapter",
		"chapter.getChapterData",
		"chapter.getById",
		"chapters.getById",
	)

	protected open val apiHeaders: Headers by lazy {
		getRequestHeaders().newBuilder()
			.set("Accept", "application/json, text/plain, */*")
			.set("Referer", "https://$domain/")
			.build()
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.OTHER,
		),
	)

	/** Path segment of a series page on this site, e.g. "seriler" -> /seriler/{slug}. */
	protected open val seriesPath: String = "seriler"

	/** Path segment of a chapter (reader) page. */
	protected open val chapterPath: String = "reader"

	protected open fun seriesUrl(slug: String): String = "/$seriesPath/$slug"

	protected open fun chapterUrl(seriesSlug: String, chapterSlug: String, chapterId: String): String =
		"/$chapterPath/$seriesSlug/$chapterSlug/$chapterId"

	// ============================== tRPC plumbing ==============================

	/**
	 * Performs a tRPC batch GET call: `/api/trpc/<procedure>?batch=1&input={"0":{"json":<input>}}`
	 * and returns the unwrapped `json` payload (object or array).
	 */
	protected suspend fun trpcCall(procedure: String, input: Any?): Any {
		val inputObj = JSONObject()
			.put("0", JSONObject().put("json", input ?: JSONObject.NULL))
		val url = "https://$defaultDomain/api/trpc/$procedure?batch=1&input=${inputObj.toString().urlEncoded()}"
		val response = webClient.httpGet(url, apiHeaders).parseJsonArray()
		val first = response.optJSONObject(0) ?: throw Exception("tRPC: empty response for $procedure")
		val result = first.optJSONObject("result") ?: throw Exception("tRPC: no result for $procedure")
		val data = result.optJSONObject("data") ?: throw Exception("tRPC: no data for $procedure")
		return data.opt("json") ?: throw Exception("tRPC: no json payload for $procedure")
	}

	protected suspend fun trpcCallObject(procedure: String, input: Any?): JSONObject =
		(trpcCall(procedure, input) as? JSONObject)
			?: throw Exception("tRPC: expected object from $procedure")

	protected suspend fun trpcCallArray(procedure: String, input: Any?): JSONArray =
		(trpcCall(procedure, input) as? JSONArray)
			?: throw Exception("tRPC: expected array from $procedure")

	// ============================== List / search ==============================

	protected open fun sortByParam(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "-views"
		SortOrder.NEWEST -> "-id"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		SortOrder.UPDATED -> "-updatedAt"
		else -> "-views"
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val input = JSONObject()
			.put("page", page)
			.put("limit", pageSize)
			.put("sortBy", sortByParam(order))
		filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { input.put("search", it) }
		if (filter.tags.isNotEmpty()) {
			input.put("turIds", JSONArray(filter.tags.map { it.key }))
		}
		filter.types.firstOrNull()?.let {
			input.put("type", when (it) {
				ContentType.MANGA -> "Manga"
				ContentType.MANHWA -> "Manhwa"
				ContentType.MANHUA -> "Manhua"
				else -> "Manga"
			})
		}
		filter.states.firstOrNull()?.let {
			input.put("status", when (it) {
				MangaState.ONGOING -> "devam-ediyor"
				MangaState.FINISHED -> "tamamlandi"
				MangaState.ABANDONED -> "birakildi"
				else -> "devam-ediyor"
			})
		}
		val items = trpcCallArray("$seriesListRouter.getSeries", input)
		return parseSeriesList(items)
	}

	protected open fun parseSeriesList(items: JSONArray): List<Manga> = items.mapJSONNotNull { jo ->
		val href = jo.getStringOrNull("href")?.nullIfEmpty()
			?: jo.getStringOrNull("slug")?.let { seriesUrl(it) }
			?: return@mapJSONNotNull null
		val url = href.toAbsoluteUrl(defaultDomain).let { "/" + it.removePrefix("https://$defaultDomain").removePrefix("http://$defaultDomain").trimStart('/') }
		Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			coverUrl = jo.getStringOrNull("thumb")?.nullIfEmpty()?.toAbsoluteUrl(domain),
			title = jo.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null,
			altTitles = emptySet(),
			description = "",
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			authors = emptySet(),
			state = parseSeriesState(jo.getStringOrNull("status")),
			source = source,
			contentRating = null,
		)
	}

	// ============================== Details + chapters ==============================

	protected open val chapterPageLimit: Int = 50

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/$seriesPath/").substringBefore('/').trimEnd('/')
		var page = 1
		var data: JSONObject? = null
		var chapters = mutableListOf<JSONObject>()
		while (true) {
			val input = JSONObject()
				.put("sef", slug)
				.put("page", page)
				.put("limit", chapterPageLimit)
			val json = trpcCallObject("$seriesDetailRouter.getSeriesData", input)
			if (data == null) data = json
			chapters += json.optJSONArray("chapters")?.let { arr ->
				List(arr.length()) { arr.optJSONObject(it) }.filterNotNull()
			} ?: emptyList()
			val totalPages = json.optJSONObject("pagination")?.optInt("totalPages", 1) ?: 1
			if (page >= totalPages || chapters.isEmpty()) break
			page++
		}
		val root = data ?: throw Exception("Seri bulunamadı: $slug")
		val seriesJson = root.optJSONObject("series") ?: root

		val resolvedSlug = seriesJson.getStringOrNull("slug")?.nullIfEmpty()
			?: seriesJson.getStringOrNull("sef")?.nullIfEmpty() ?: slug
		val url = seriesUrl(resolvedSlug)

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
		val isoDateRegex = Regex("""\d{4}-\d{2}-\d{2}""")
		val chaptersParsed = chapters.mapChapters(reversed = false) { i, ch ->
			val chapterId = ch.opt("chap_id")?.toString()?.ifBlank { null }
				?: ch.opt("id")?.toString()?.ifBlank { null }
				?: return@mapChapters null
			val num = ch.opt("no")?.toString()?.toFloatOrNull()?.takeIf { it != 0f } ?: (i + 1f)
			val chTitle = ch.getStringOrNull("name")?.trim()?.nullIfEmpty()
			val chapterSlug = ch.getStringOrNull("slug")?.nullIfEmpty()
				?: ch.getStringOrNull("href")?.substringAfterLast('/')?.nullIfEmpty()
				?: slugifyChapter(resolvedSlug, num, chTitle)
			val dateStr = ch.getStringOrNull("createdAt")
				?: ch.getStringOrNull("date") ?: ch.getStringOrNull("created_at")
			MangaChapter(
				id = generateUid(chapterUrl(resolvedSlug, chapterSlug, chapterId)),
				title = chTitle,
				number = num,
				volume = 0,
				url = chapterUrl(resolvedSlug, chapterSlug, chapterId),
				scanlator = null,
				uploadDate = dateStr?.let { raw ->
					isoDateRegex.find(raw)?.let { dateFormat.parseSafe(it.value) } ?: 0L
				} ?: 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			title = seriesJson.getStringOrNull("name")?.trim()?.nullIfEmpty()
				?: seriesJson.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = parseAltTitles(
				seriesJson.getStringOrNull("alternativeTitles") ?: seriesJson.getStringOrNull("altNames"),
			).ifEmpty { manga.altTitles },
			coverUrl = firstNonBlank(
				seriesJson.getStringOrNull("cover"),
				seriesJson.getStringOrNull("thumb"),
				seriesJson.getStringOrNull("image"),
				seriesJson.getStringOrNull("featuredImage"),
			)?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			description = firstNonBlank(
				seriesJson.getStringOrNull("description"),
				seriesJson.getStringOrNull("desc"),
				seriesJson.getStringOrNull("content"),
				seriesJson.getStringOrNull("summary"),
			)?.trim() ?: manga.description.orEmpty(),
			tags = parseGenres(seriesJson).ifEmpty { manga.tags },
			authors = setOfNotNull(
				seriesJson.getStringOrNull("author")?.trim()?.nullIfEmpty(),
				seriesJson.getStringOrNull("artist")?.trim()?.nullIfEmpty(),
			).ifEmpty { manga.authors },
			state = parseSeriesState(firstNonBlank(seriesJson.getStringOrNull("status"), seriesJson.getStringOrNull("durum"))) ?: manga.state,
			chapters = chaptersParsed,
		)
	}

	protected open fun parseGenres(jo: JSONObject): Set<MangaTag> {
		val arr = jo.optJSONArray("genres")
			?: jo.optJSONArray("turs")
			?: jo.optJSONArray("turler")
			?: jo.optJSONArray("tags")
			?: return emptySet()
		return arr.mapJSONNotNull { g ->
			when (g) {
				is JSONObject -> {
					val name = g.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
					MangaTag(
						key = g.opt("id")?.toString() ?: g.getStringOrNull("slug") ?: name.lowercase(sourceLocale),
						title = name.toTitleCase(sourceLocale),
						source = source,
					)
				}
				else -> g.toString().trim().nullIfEmpty()?.let {
					MangaTag(key = it.lowercase(sourceLocale), title = it.toTitleCase(sourceLocale), source = source)
				}
			}
		}.toSet()
	}

	protected open fun parseAltTitles(raw: String?): Set<String> {
		if (raw.isNullOrBlank()) return emptySet()
		return raw.split(',', ';', '|', '/')
			.mapNotNull { it.trim().nullIfEmpty() }
			.toSet()
	}

	protected open fun parseSeriesState(status: String?): MangaState? {
		if (status.isNullOrBlank()) return null
		val normalized = status.trim().lowercase(sourceLocale)
		return when {
			normalized.contains("devam") -> MangaState.ONGOING
			normalized.contains("tamamland") || normalized == "tamamlandi" || normalized == "completed" -> MangaState.FINISHED
			normalized.contains("birak") || normalized == "dropped" || normalized == "cancelled" -> MangaState.ABANDONED
			normalized.contains("ara ver") || normalized.contains("hiatus") || normalized.contains("bekle") -> MangaState.PAUSED
			normalized.contains("yakında") || normalized == "coming_soon" -> MangaState.UPCOMING
			else -> null
		}
	}

	protected open fun slugifyChapter(seriesSlug: String, no: Float, title: String?): String {
		val base = buildString {
			append(no.trimNumber())
			if (!title.isNullOrBlank()) append(" - ").append(title.trim())
		}
		return base.lowercase(sourceLocale)
			.replace(Regex("[^a-z0-9\\s-]")) { "" }
			.trim()
			.replace(Regex("\\s+"), "-")
			.replace(Regex("-+"), "-")
			.trim('-')
			.ifBlank { "bolum-${no.trimNumber()}" }
	}

	// ============================== Pages ==============================

	/** CSS selector used as a last-resort page image source on the reader HTML. */
	protected open val selectPages = "img.reader-img, main img[src*='/api/uploads']"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast('/')
		val seriesSlug = chapter.url.removePrefix("/$chapterPath/").substringBefore('/')
		val chapterSlug = chapter.url.removePrefix("/$chapterPath/$seriesSlug/").substringBefore('/')

		// 1) tRPC chapter endpoint candidates.
		for (procedure in chapterImageProcedures) {
			val images = runCatching {
				fetchChapterImages(procedure, chapterId, chapterSlug)
			}.getOrNull()
			if (!images.isNullOrEmpty()) return images.toPageList()
		}

		// 2) Reader page HTML (works when images are server-rendered or embedded in RSC data).
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return extractImagesFromDocument(doc).toPageList()
	}

	protected open suspend fun fetchChapterImages(procedure: String, chapterId: String, chapterSlug: String): List<String> {
		val inputVariants = listOf(
			JSONObject().put("id", chapterId),
			JSONObject().put("chap_id", chapterId),
			JSONObject().put("chapterId", chapterId),
			JSONObject().put("sef", chapterSlug),
		)
		for (input in inputVariants) {
			val payload = runCatching { trpcCall(procedure, input) }.getOrNull() ?: continue
			val urls = extractImageUrls(payload)
			if (urls.isNotEmpty()) return urls
		}
		return emptyList()
	}

	/** Pulls image URL strings out of an arbitrary tRPC payload shape. */
	protected open fun extractImageUrls(payload: Any?): List<String> = when (payload) {
		is JSONArray -> payload.mapJSONNotNull { item -> imageUrlFromJson(item) }
		is JSONObject -> {
			for (key in listOf("images", "pages", "files")) {
				val arr = payload.optJSONArray(key) ?: continue
				val urls = arr.mapJSONNotNull { item -> imageUrlFromJson(item) }
				if (urls.isNotEmpty()) return urls
			}
			// maybe wrapped: {chapter:{images:[...]}} etc. — search nested containers
			payload.keys().asSequence().flatMap { key ->
				when (val value = payload.opt(key)) {
					is JSONObject, is JSONArray -> extractImageUrls(value)
					else -> emptyList()
				}
			}.toList()
		}
		else -> emptyList()
	}

	protected open fun imageUrlFromJson(item: Any?): String? = when (item) {
		is JSONObject -> listOf("url", "src", "image", "path", "file")
			.firstNotNullOfOrNull { item.getStringOrNull(it) }
		else -> item?.toString()?.takeIf { it.startsWith("http") || it.startsWith("/") }
	}

	protected open fun extractImagesFromDocument(doc: Document): List<String> {
		val fromAttrs = doc.select(selectPages).mapNotNull { img ->
			img.attrOrNull("src", "data-src")?.toAbsoluteUrl(domain)
		}
		if (fromAttrs.isNotEmpty()) return fromAttrs.distinct()
		// RSC/flight payload fallback: any uploads URL embedded in script data
		val embedded = doc.select("script").flatMap { script ->
			Regex("/api/uploads/assets/[^\"\\\\ ]+").findAll(script.data()).map { it.value }.toList()
		}.map { it.toAbsoluteUrl(domain) }
		return embedded.distinct()
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> = runCatching {
		trpcCallArray("$genreRouter.getAll", null).mapJSONNotNull { jo ->
			val name = jo.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaTag(
				key = jo.opt("id")?.toString() ?: jo.getStringOrNull("slug") ?: name.lowercase(sourceLocale),
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}.toSet()
	}.getOrElse { emptySet() }

	protected fun List<String>.toPageList(): List<MangaPage> = map { p ->
		val url = p.toAbsoluteUrl(domain)
		MangaPage(
			id = generateUid(url),
			url = url,
			preview = null,
			source = source,
		)
	}

	protected fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

	protected fun Float.trimNumber(): String =
		if (this == this.toLong().toFloat()) this.toLong().toString() else this.toString()

	private val sourceLocale: Locale get() = Locale("tr")
}
