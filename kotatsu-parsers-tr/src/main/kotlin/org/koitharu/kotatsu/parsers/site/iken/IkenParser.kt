package org.koitharu.kotatsu.parsers.site.iken

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Base parser for sites running the "iken" manga platform (Next.js + tRPC frontend,
 * JSON API under /api). Listing, details and chapter images are read from the JSON
 * API; as a fallback chapter pages can be extracted from the reader page itself
 * (embedded NEXT/RSC data or plain img tags).
 */
internal abstract class IkenParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 18,
	protected val useAPI: Boolean = false,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected val defaultDomain: String
		get() = if (useAPI) "api.$domain" else domain

	protected open val apiHeaders: Headers by lazy {
		getRequestHeaders().newBuilder()
			.set("Accept", "application/json, text/plain, */*")
			.set("Origin", "https://$domain")
			.set("Referer", "https://$domain/")
			.build()
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

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
			ContentType.MANHUA,
			ContentType.MANHWA,
			ContentType.OTHER,
		),
	)

	/** Path segment of a series page on this site, e.g. "series" -> /series/{slug}. */
	protected open val seriesPath: String = "series"

	/** Path segment of a chapter (reader) page, e.g. "series" -> /series/{slug}/{chapter}. */
	protected open val chapterPath: String = "series"

	protected open fun seriesUrl(slug: String): String = "/$seriesPath/$slug"

	protected open fun chapterUrl(seriesSlug: String, chapterSlug: String, chapterId: String): String =
		"/$chapterPath/$seriesSlug/$chapterSlug/$chapterId"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(defaultDomain)
			append("/api/query?page=")
			append(page)
			append("&perPage=")
			append(pageSize)
			append("&searchTerm=")
			filter.query?.trim()?.let { append(it.urlEncoded()) }
			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}
			append("&seriesType=")
			filter.types.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						ContentType.OTHER -> "RUSSIAN"
						else -> ""
					},
				)
			}
			append("&seriesStatus=")
			filter.states.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.UPCOMING -> "COMING_SOON"
						MangaState.ABANDONED -> "DROPPED"
						else -> ""
					},
				)
			}
			append("&orderBy=")
			append(orderByParam(order))
			append("&orderDirection=")
			append(orderDirectionParam(order))
		}
		return parseMangaList(webClient.httpGet(url, apiHeaders).parseJson())
	}

	protected open fun orderByParam(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "updatedAt"
		SortOrder.POPULARITY -> "totalViews"
		SortOrder.NEWEST -> "createdAt"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "postTitle"
		else -> "updatedAt"
	}

	protected open fun orderDirectionParam(order: SortOrder): String = when (order) {
		SortOrder.ALPHABETICAL_DESC -> "desc"
		SortOrder.ALPHABETICAL -> "asc"
		else -> "desc"
	}

	protected open fun parseMangaList(json: JSONObject): List<Manga> {
		return json.getJSONArray("posts").mapJSONNotNull { jo ->
			if (jo.getBooleanOrDefault("isNovel", false) ||
				jo.getStringOrNull("seriesType").equals("NOVEL", ignoreCase = true)
			) {
				return@mapJSONNotNull null
			}
			val slug = jo.getString("slug")
			val url = seriesUrl(slug)
			val isAdult = jo.getBooleanOrDefault("hot", false) || jo.getBooleanOrDefault("adult", false)
			val author = jo.getStringOrNull("author")?.trim()?.nullIfEmpty()
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = jo.getStringOrNull("featuredImage")?.nullIfEmpty(),
				title = jo.getString("postTitle").trim(),
				altTitles = parseAltTitles(jo.getStringOrNull("alternativeTitles")),
				description = jo.getStringOrNull("postContent")
					?: jo.getStringOrNull("description")
					?: "",
				rating = RATING_UNKNOWN,
				tags = parseGenres(jo),
				authors = setOfNotNull(author),
				state = parseSeriesState(jo.getStringOrNull("seriesStatus")),
				source = source,
				contentRating = if (isAdult) ContentRating.ADULT else null,
			)
		}
	}

	protected open val datePattern = "yyyy-MM-dd"

	override suspend fun getDetails(manga: Manga): Manga {
		val rawSlug = manga.url.removePrefix("/$seriesPath/").substringBefore('/').trimEnd('/')
		val postJson = webClient.httpGet(
			"https://$defaultDomain/api/post?postSlug=${rawSlug.urlEncoded()}",
			apiHeaders,
		).parseJson().getJSONObject("post")

		val postId = postJson.opt("id")?.toString().orEmpty()
		val slug = postJson.getStringOrNull("slug")?.nullIfEmpty() ?: rawSlug
		val url = seriesUrl(slug)

		val chaptersResp = webClient.httpGet(
			"https://$defaultDomain/api/chapters?postId=$postId&skip=0&take=900&order=desc&userid=",
			apiHeaders,
		).parseJson().getJSONObject("post")
		val rawChapters = chaptersResp.getJSONArray("chapters").asTypedList<JSONObject>()
			.filter { !it.getBooleanOrDefault("isLocked", false) && it.opt("isAccessible") != false }

		val dateFormat = SimpleDateFormat(datePattern, Locale.ENGLISH)
		val chapters = rawChapters.mapChapters(reversed = true) { i, ch ->
			val chapterSlug = ch.getString("slug")
			val chapterId = ch.opt("id")?.toString().orEmpty()
			val num = ch.getFloatOrDefault("number", 0f).takeIf { it != 0f } ?: (i + 1f)
			val chTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val dateStr = ch.getStringOrNull("createdAt")?.substringBefore('T')
			MangaChapter(
				id = generateUid(chapterUrl(slug, chapterSlug, chapterId)),
				title = chTitle,
				number = num,
				volume = 0,
				url = chapterUrl(slug, chapterSlug, chapterId),
				scanlator = null,
				uploadDate = if (dateStr != null && ISO_DATE_REGEX.matches(dateStr)) {
					dateFormat.parseSafe(dateStr)
				} else {
					0L
				},
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			title = postJson.getStringOrNull("postTitle")?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = parseAltTitles(postJson.getStringOrNull("alternativeTitles")).ifEmpty { manga.altTitles },
			coverUrl = postJson.getStringOrNull("featuredImage")?.nullIfEmpty() ?: manga.coverUrl,
			description = postJson.getStringOrNull("postContent")
				?: postJson.getStringOrNull("description")
				?: manga.description.orEmpty(),
			tags = parseGenres(postJson).ifEmpty { manga.tags },
			authors = setOfNotNull(
				postJson.getStringOrNull("author")?.trim()?.nullIfEmpty(),
				postJson.getStringOrNull("artist")?.trim()?.nullIfEmpty(),
			).ifEmpty { manga.authors },
			state = parseSeriesState(postJson.getStringOrNull("seriesStatus")) ?: manga.state,
			chapters = chapters,
		)
	}

	protected open fun parseGenres(jo: JSONObject): Set<MangaTag> {
		val arr = jo.optJSONArray("genres") ?: return emptySet()
		return arr.mapJSONNotNull { g ->
			val id = g.optInt("id", -1)
			val name = g.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaTag(
				key = if (id > 0) id.toString() else name.lowercase(sourceLocale),
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}.toSet()
	}

	protected open fun parseAltTitles(raw: String?): Set<String> {
		if (raw.isNullOrBlank()) return emptySet()
		return raw.split(',', ';', '|', '/')
			.mapNotNull { it.trim().nullIfEmpty() }
			.toSet()
	}

	protected open fun parseSeriesState(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROPPED", "CANCELLED" -> MangaState.ABANDONED
		"COMING_SOON" -> MangaState.UPCOMING
		else -> null
	}

	/** CSS selector used as a last-resort page image source on the reader HTML. */
	protected open val selectPages = "main section img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// 1) JSON API for the chapter images.
		val chapterId = chapter.url.substringAfterLast('/')
		val apiPages = runCatching { readChapterImages(chapterId) }.getOrElse { error ->
			if (error.message?.contains("unlock", ignoreCase = true) == true) {
				throw error
			}
			emptyList()
		}
		if (apiPages.isNotEmpty()) {
			return apiPages
		}

		// 2) Reader page: images embedded in the NEXT/RSC payload or plain img tags.
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		if (doc.selectFirst("svg.lucide-lock") != null) {
			throw Exception("Need to unlock chapter!")
		}

		val embedded = runCatching { parseImagesJson(doc.getNextJson("images")) }.getOrNull()
		if (!embedded.isNullOrEmpty()) {
			return embedded.toPageList()
		}

		return doc.select(selectPages)
			.mapNotNull { img -> img.attrOrNull("src", "data-src")?.toAbsoluteUrl(domain) }
			.ifEmpty { doc.select("img[src*='/api/uploads']").mapNotNull { it.attrOrNull("src")?.toAbsoluteUrl(domain) } }
			.toPageList()
	}

	protected open suspend fun readChapterImages(chapterId: String): List<MangaPage> {
		if (chapterId.isBlank()) return emptyList()
		val json = webClient.httpGet(
			"https://$defaultDomain/api/chapter?chapterId=${chapterId.urlEncoded()}",
			apiHeaders,
		).parseJson()
		val chapterJson = json.optJSONObject("chapter") ?: return emptyList()
		if (chapterJson.optBoolean("isLocked", false) || chapterJson.opt("isAccessible") == false) {
			throw Exception("Need to unlock chapter!")
		}
		val images = chapterJson.optJSONArray("images") ?: return emptyList()
		val pages = (0 until images.length()).mapNotNull { index ->
			when (val item = images.opt(index)) {
				is JSONObject -> {
					val url = item.getStringOrNull("url")
						?: item.getStringOrNull("src")
						?: item.getStringOrNull("image")
						?: return@mapNotNull null
					PageImage(
						order = item.opt("order")?.toString()?.toIntOrNull() ?: Int.MAX_VALUE,
						url = url.replace("/public//", "/public/"),
					)
				}
				else -> item?.toString()?.nullIfEmpty()?.let { PageImage(Int.MAX_VALUE, it) }
			}
		}.sortedBy { it.order }

		return pages.toPageList()
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val array = webClient.httpGet("https://$defaultDomain/api/genres", apiHeaders).parseJsonArray()
		return array.mapJSONToSet { jo ->
			MangaTag(
				key = jo.getInt("id").toString(),
				title = jo.getString("name").trim().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	protected fun List<String>.toPageList(): List<MangaPage> = map { p ->
		val url = p.toAbsoluteUrl(domain)
		MangaPage(
			id = generateUid(url),
			url = url,
			preview = null,
			source = source,
		)
	}

	protected fun List<PageImage>.toPageList(): List<MangaPage> = map { image ->
		val url = image.url.toAbsoluteUrl(domain)
		MangaPage(
			id = generateUid(url),
			url = url,
			preview = null,
			source = source,
		)
	}

	/** Extracts a JSON array/object stored after [key] inside a script tag (NEXT/RSC data). */
	protected fun Document.getNextJson(key: String): String {
		val scripts = select("script")
		val scriptData = scripts.find { script ->
			script.data().contains(key)
		}?.data() ?: throw Exception("Unable to retrieve NEXT data")

		val keyIndex = scriptData.indexOf(key)
		if (keyIndex == -1) throw Exception("Key $key not found in script data")

		val start = scriptData.indexOf('[', keyIndex)
		if (start == -1) {
			val objStart = scriptData.indexOf('{', keyIndex)
			if (objStart == -1) throw Exception("No JSON data found after key")

			var depth = 1
			var i = objStart + 1
			while (i < scriptData.length && depth > 0) {
				when (scriptData[i]) {
					'{' -> depth++
					'}' -> depth--
				}
				i++
			}
			return scriptData.substring(objStart, i)
		}

		var depth = 1
		var i = start + 1
		while (i < scriptData.length && depth > 0) {
			when (scriptData[i]) {
				'[' -> depth++
				']' -> depth--
			}
			i++
		}

		val jsonStr = scriptData.substring(start, i)
		return jsonStr.replace("\\/", "/").replace("\\\"", "\"")
	}

	protected fun parseImagesJson(json: String): List<String> {
		val trimmed = json.trim()
		if (trimmed.startsWith("[")) {
			val jsonArray = JSONArray(trimmed)
			return List(jsonArray.length()) { index ->
				when (val item = jsonArray.opt(index)) {
					is JSONObject -> item.getStringOrNull("url")
						?: item.getStringOrNull("src")
						?: item.getStringOrNull("image")
						else -> item?.toString()
				} ?: ""
			}.filter { it.isNotEmpty() }
		}
		val obj = JSONObject(trimmed)
		val images = obj.optJSONArray("images") ?: obj.optJSONArray("pages") ?: return emptyList()
		return parseImagesJson(images.toString())
	}

	protected data class PageImage(
		val order: Int,
		val url: String,
	)

	private companion object {
		val ISO_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
	}
}
