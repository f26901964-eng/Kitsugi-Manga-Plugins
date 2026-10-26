package com.kitsugi.extension.tr.mangadenizi

import com.kitsugi.animelist.data.manga.MangaChapter
import com.kitsugi.animelist.data.manga.MangaDetails
import com.kitsugi.animelist.data.manga.MangaSourceResult
import com.kitsugi.animelist.data.manga.MangaStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MangaDeniziApi(private val client: OkHttpClient, private val endpoint: String = SITE) {
    companion object {
        const val SITE = "https://mangadenizi.net"
        const val NAME = "MangaDenizi"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val MAX_BYTES = 32 * 1024 * 1024
        private val SLUG = Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}._-]*")
        private val LAYOUT = Regex("md-tiled-v1:(\\d{1,2}):(\\d{1,10})")
    }
    data class ChapterRef(val manga: String, val chapter: String)
    data class ImageRef(val url: String, val grid: Int? = null, val seed: Long? = null)
    data class PageRef(val number: Int, val imageUrl: String)
    private class InvalidData(message: String) : IOException(message)
    private class HttpFailure(val status: Int) : IOException("MangaDenizi HTTP $status")

    fun mangaUrl(slug: String): String = "$SITE/manga/${validSlug(slug)}"
    fun chapterUrl(ref: ChapterRef): String = "$SITE/read/${validSlug(ref.manga)}/${validSlug(ref.chapter)}"

    fun mangaSlug(value: String): String {
        val parts = sourcePath(value)
        if (parts.size != 2 || parts[0] != "manga") invalid("Geçersiz seri adresi")
        return validSlug(parts[1])
    }
    fun chapterRef(value: String): ChapterRef {
        val parts = sourcePath(value)
        if (parts.size != 3 || parts[0] != "read") invalid("Geçersiz bölüm adresi")
        return ChapterRef(validSlug(parts[1]), validSlug(parts[2]))
    }
    private fun sourcePath(value: String): List<String> {
        val url = (if (value.startsWith('/')) SITE + value else value).toHttpUrl()
        if (url.scheme != "https" || url.host != "mangadenizi.net" || url.username.isNotEmpty() || url.password.isNotEmpty()) invalid("Başka kaynağa ait adres")
        return url.pathSegments.filter { it.isNotEmpty() }
    }
    private fun validSlug(value: String): String {
        if (!SLUG.matches(value)) invalid("Geçersiz kaynak kimliği")
        return value
    }

    suspend fun listing(page: Int, query: String? = null): MangaSourceResult {
        require(page >= 1) { "Sayfa numarası en az 1 olmalı" }
        val url = "$endpoint/api/v1/web/manga".toHttpUrl().newBuilder().addQueryParameter("page", page.toString())
        if (query.isNullOrBlank()) url.addQueryParameter("sort", "popular") else url.addQueryParameter("q", query.trim())
        val result = json(url.build()).getJSONObject("data").getJSONObject("manga")
        val current = integer(result, "current_page")
        val last = integer(result, "last_page")
        if (current != page || last < 1) invalid("Bozuk katalog sayfalaması")
        val items = result.getJSONArray("data").objects().map { toManga(it) }
        if (items.map { it.url }.distinct().size != items.size) invalid("Katalogda yinelenen seri")
        return MangaSourceResult(items, current < last)
    }
    private suspend fun series(url: String): JSONObject {
        val slug = mangaSlug(url)
        val result = json("$endpoint/api/v1/web/manga/$slug".toHttpUrl()).getJSONObject("data").getJSONObject("manga")
        if (required(result, "slug") != slug) invalid("İstenen seri yerine başka seri döndü")
        return result
    }
    suspend fun details(url: String): MangaDetails = toManga(series(url))

    suspend fun chapters(url: String): List<MangaChapter> {
        val slug = mangaSlug(url)
        val chapters = series(url).getJSONArray("chapters").objects().map { chapter ->
            val number = nullable(chapter, "number") ?: invalid("Bölüm numarası eksik")
            val numeric = number.toFloatOrNull()?.takeIf { it.isFinite() } ?: invalid("Bozuk bölüm numarası")
            val ref = ChapterRef(slug, validSlug(required(chapter, "slug")))
            MangaChapter(
                url = chapterUrl(ref),
                name = "Bölüm $number" + (nullable(chapter, "title")?.let { ": $it" } ?: ""),
                chapterNumber = numeric,
                scanlator = chapter.optJSONObject("publishing_team")?.let { nullable(it, "name") },
                uploadDate = nullable(chapter, "published_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L,
                mangaUrl = mangaUrl(slug),
            )
        }
        if (chapters.map { it.url }.distinct().size != chapters.size) invalid("Yinelenen bölüm adresi")
        return chapters.sortedWith(compareByDescending<MangaChapter> { it.chapterNumber }.thenByDescending { it.uploadDate })
    }
    suspend fun pages(chapter: MangaChapter): List<PageRef> {
        val ref = chapterRef(chapter.url)
        if (chapter.mangaUrl.isNotBlank() && mangaSlug(chapter.mangaUrl) != ref.manga) invalid("Bölüm seçilen seriye ait değil")
        val result = json("$endpoint/api/v1/reader/${ref.manga}/${ref.chapter}".toHttpUrl())
        if (required(result.getJSONObject("manga"), "slug") != ref.manga || required(result.getJSONObject("chapter"), "slug") != ref.chapter) invalid("İstenen bölüm yerine başka bölüm döndü")
        val expected = integer(result.getJSONObject("chapter"), "pages_count")
        val pages = result.getJSONArray("pages").objects().map { page ->
            val number = integer(page, "page_number")
            val image = checkedImageUrl(required(page, "image_url")).newBuilder()
            val scramble = page.optJSONObject("scramble")
            val method = scramble?.let { nullable(it, "method") }
            if (scramble != null) {
                if (method != "tiled-v1") invalid("Desteklenmeyen görsel düzeni: $method")
                val grid = integer(scramble, "grid")
                val seed = long(scramble, "seed")
                if (grid !in 1..32 || seed !in 0..0xFFFFFFFFL) invalid("Bozuk görsel düzeni")
                image.fragment("md-tiled-v1:$grid:$seed")
            }
            PageRef(number, image.build().toString())
        }.sortedBy { it.number }
        if (expected !in 1..2000 || pages.size != expected || pages.withIndex().any { (i, p) -> p.number != i + 1 }) invalid("Bölüm sayfaları eksik, yinelenmiş veya sırasız")
        if (pages.map { it.imageUrl.substringBefore('#') }.distinct().size != pages.size) invalid("Bölümde yinelenen görsel")
        return pages
    }
    fun imageRef(value: String): ImageRef {
        val url = checkedImageUrl(value)
        val fragment = url.fragment
        val clean = url.newBuilder().fragment(null).build().toString()
        if (fragment.isNullOrBlank()) return ImageRef(clean)
        val match = LAYOUT.matchEntire(fragment) ?: invalid("Geçersiz sayfa görseli bilgisi")
        val grid = match.groupValues[1].toInt()
        val seed = match.groupValues[2].toLong()
        if (grid !in 1..32 || seed !in 0..0xFFFFFFFFL) invalid("Geçersiz sayfa düzeni")
        return ImageRef(clean, grid, seed)
    }
    suspend fun imageBytes(url: String, referer: String): ByteArray {
        val reader = chapterUrl(chapterRef(referer))
        return request(checkedImageUrl(url), reader)
    }
    private fun checkedImageUrl(value: String): HttpUrl {
        val url = value.toHttpUrl()
        if (url.scheme != "https" || !(url.host == "mangadenizi.net" || url.host.endsWith(".mangadenizi.net")) || url.username.isNotEmpty() || url.password.isNotEmpty()) invalid("Güvenilmeyen görsel adresi")
        return url
    }
    private fun toManga(value: JSONObject): MangaDetails {
        fun names(key: String): List<String> = value.optJSONArray(key)?.objects()?.mapNotNull { nullable(it, "name") } ?: emptyList()
        val authors = value.optJSONArray("authors")?.objects() ?: emptyList()
        val description = nullable(value, "description")?.let { if (Regex("<[^>]+>").containsMatchIn(it)) Jsoup.parseBodyFragment(it).text() else it }
        val alternatives = value.optJSONArray("alternative_titles")?.let { array -> (0 until array.length()).mapNotNull { i -> array.opt(i)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() } } } ?: emptyList()
        val genres = (names("categories") + names("genres") + names("formats") + names("themes") + listOfNotNull(value.optJSONObject("type")?.let { nullable(it, "name") })).distinct()
        return MangaDetails(
            url = mangaUrl(required(value, "slug")),
            title = required(value, "title"),
            author = authors.filter { nullable(it, "role") != "artist" }.mapNotNull { nullable(it, "name") }.distinct().joinToString().ifBlank { null },
            artist = authors.filter { nullable(it, "role") == "artist" }.mapNotNull { nullable(it, "name") }.distinct().joinToString().ifBlank { null },
            description = listOfNotNull(description, alternatives.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Diğer adlar: ")).joinToString("\n\n").ifBlank { null },
            genre = genres,
            thumbnailUrl = (nullable(value, "cover_url") ?: nullable(value, "cover_thumb_url"))?.let { checkedImageUrl(it).toString() },
            status = when (nullable(value, "status")) {
                "ongoing" -> MangaStatus.Ongoing
                "completed" -> MangaStatus.Completed
                "hiatus" -> MangaStatus.OnHiatus
                "cancelled", "dropped" -> MangaStatus.Cancelled
                else -> MangaStatus.Unknown
            },
            source = NAME,
        )
    }
    private suspend fun json(url: HttpUrl): JSONObject = try {
        JSONObject(request(url, "$SITE/manga").toString(Charsets.UTF_8))
    } catch (e: org.json.JSONException) { throw IOException("MangaDenizi geçerli seri/bölüm verisi döndürmedi", e) }

    private suspend fun request(url: HttpUrl, referer: String): ByteArray {
        for (attempt in 0..1) {
            try { return requestOnce(url, referer) } catch (e: IOException) {
                if (attempt == 1 || e is InvalidData || (e is HttpFailure && e.status < 500 && e.status != 429)) throw e
                delay(600L)
            }
        }
        error("Unreachable")
    }
    private suspend fun requestOnce(url: HttpUrl, referer: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", referer).header("Origin", SITE).header("Accept", "application/json,image/avif,image/webp,image/*,*/*").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw HttpFailure(it.code)
                        val body = it.body ?: invalid("Boş sunucu yanıtı")
                        if (body.contentLength() > MAX_BYTES) invalid("Sunucu yanıtı çok büyük")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw IOException("İstek iptal edildi")
                                val read = input.read(buffer)
                                if (read == -1) break
                                if (output.size().toLong() + read > MAX_BYTES) invalid("Sunucu yanıtı çok büyük")
                                output.write(buffer, 0, read)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun nullable(value: JSONObject, key: String): String? = if (value.isNull(key)) null else value.optString(key).trim().takeIf { it.isNotEmpty() }
    private fun required(value: JSONObject, key: String): String = nullable(value, key) ?: invalid("Eksik alan: $key")
    private fun long(value: JSONObject, key: String): Long = value.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull() ?: invalid("Bozuk tam sayı: $key")
    private fun integer(value: JSONObject, key: String): Int = long(value, key).let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid("Tam sayı taşması: $key") else it.toInt() }
    private fun invalid(message: String): Nothing = throw InvalidData(message)
}
