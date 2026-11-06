package eu.kanade.tachiyomi.extension.tr.mangadiyari

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * URL builders are kept separate from the source so the exact query contract can be unit tested
 * without a network call. Two rules matter here:
 *
 *  * `search` is matched by the site against the series title text, so the query is sent as typed.
 *    Slugifying it (`Şeytan` -> `seytan`) or trimming it to ASCII drops results.
 *  * `genre` is a comma separated list of the genre names as stored on the site, which are mostly
 *    Turkish (`Aksiyon`), while `sort`, `status` and `type` use the site's fixed English values.
 */
internal fun seriesListUrl(
    baseUrl: String,
    page: Int,
    query: String = "",
    sort: String = "latest",
    type: String = "",
    status: String = "",
    genres: String = "",
): HttpUrl = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
    if (query.isNotBlank()) addQueryParameter("search", query)
    if (genres.isNotBlank()) addQueryParameter("genre", genres)
    addQueryParameter("sort", sort)
    if (status.isNotBlank()) addQueryParameter("status", status)
    if (type.isNotBlank()) addQueryParameter("type", type)
    addQueryParameter("limit", MANGADIYARI_PAGE_SIZE.toString())
    addQueryParameter("page", page.toString())
}.build()

internal fun latestUpdatesUrl(baseUrl: String, page: Int): HttpUrl = "$baseUrl/api/series/latest-updates".toHttpUrl().newBuilder()
    .addQueryParameter("limit", MANGADIYARI_PAGE_SIZE.toString())
    .addQueryParameter("page", page.toString())
    .build()

internal fun seriesDetailsUrl(baseUrl: String, slug: String): String = "$baseUrl/api/series/$slug"

internal fun chapterPagesUrl(baseUrl: String, chapterId: String): String = "$baseUrl/api/chapters/$chapterId"

/** The site routes a chapter by its number; the chapter id alone sends the reader back to the series page. */
internal fun readerUrl(baseUrl: String, slug: String, number: String): String = "$baseUrl/seri/$slug/bolum/$number"

internal const val MANGADIYARI_PAGE_SIZE = 24
