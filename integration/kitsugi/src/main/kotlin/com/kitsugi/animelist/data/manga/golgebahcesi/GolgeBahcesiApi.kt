package com.kitsugi.animelist.data.manga.golgebahcesi

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
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No Android/KeiSource dependency: compatible with Kitsugi's OkHttp 4.12 runtime. */
internal class GolgeBahcesiApi(
    private val client: OkHttpClient,
    private val userAgent: () -> String,
    siteUrl: String = "https://golgebahcesi.com",
    apiUrl: String = "https://api.golgebahcesi.com/api/",
) {
    private val site = siteUrl.toHttpUrl()
    private val api = apiUrl.trimEnd('/').plus('/').toHttpUrl()

    data class Series(
        val slug: String,
        val title: String,
        val description: String?,
        val coverUrl: String?,
        val author: String?,
        val artist: String?,
        val status: String?,
        val genres: List<String>,
    )

    data class Listing(val series: List<Series>, val hasNextPage: Boolean)
    data class ChapterRef(val id: String, val seriesSlug: String, val slug: String)
    data class Chapter(
        val ref: ChapterRef,
        val title: String,
        val number: Float,
        val uploadDate: Long,
        val isLocked: Boolean,
    )
    data class Image(val index: Int, val url: String)
    data class ChapterContent(
        val ref: ChapterRef,
        val isSecure: Boolean,
        val images: List<Image>,
        val expectedPageCount: Int? = null,
    )

    suspend fun list(page: Int, query: String = "", popular: Boolean = false): Listing {
        require(page >= 1) { "Page must start at 1" }
        val url = endpoint("series").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", if (popular) "popular" else "default")
            .apply { query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("search", it) } }
            .build()
        val root = JSONObject(get(url))
        // A changed/error response must not look like a successful empty catalogue.
        val data = root.optJSONArray("data") ?: throw IOException("Gölge Bahçesi katalog yanıtı değişmiş")
        val series = (0 until data.length()).map { parseSeries(data.getJSONObject(it)) }
        val pagination = root.optJSONObject("pagination")
        val hasNext = if (pagination == null) {
            series.size == PAGE_SIZE
        } else {
            val current = pagination.getInt("currentPage")
            val total = pagination.getInt("totalPages")
            if (current < 1 || total < 0) throw IOException("Geçersiz katalog sayfalaması")
            current < total
        }
        return Listing(series, hasNext)
    }

    suspend fun details(mangaUrl: String): Series = parseSeries(JSONObject(get(endpoint("series", seriesSlug(mangaUrl)))))

    suspend fun chapters(mangaUrl: String): List<Chapter> {
        val series = seriesSlug(mangaUrl)
        val array = JSONArray(get(endpoint("series", series, "chapters")))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val number = item.getDouble("number").toFloat()
            if (!number.isFinite() || number < 0) throw IOException("Geçersiz bölüm numarası")
            val ref = ChapterRef(
                key(item.requiredString("id")),
                item.textOrNull("seriesSlug")?.let(::key) ?: series,
                key(item.requiredString("slug")),
            )
            if (ref.seriesSlug != series) throw IOException("Bölüm farklı bir seriye ait")
            Chapter(
                ref = ref,
                title = item.textOrNull("title") ?: "Bölüm $number",
                number = number,
                uploadDate = parseDate(item.textOrNull("releaseDate"))
                    ?: parseDate(item.textOrNull("createdAt")) ?: 0L,
                isLocked = item.optBoolean("isLocked", false),
            )
        }.distinctBy { it.ref.id }.sortedByDescending { it.number }
    }

    suspend fun resolveChapter(chapterUrl: String): ChapterRef {
        val url = siteLink(chapterUrl)
        val path = url.pathSegments.filter(String::isNotEmpty)
        if (path.size == 3 && path[0] != "manga") {
            // Previously installed Keiyoushi versions stored /id/series/slug.
            return ChapterRef(key(path[0]), key(path[1]), key(path[2]))
        }
        if (path.size != 4 || path[0] != "manga" || path[2] != "bolum") {
            throw IOException("Geçersiz Gölge Bahçesi bölüm bağlantısı")
        }
        val series = key(path[1])
        val slug = key(path[3])
        val id = url.fragment?.takeIf { it.startsWith("chapterId=") }?.removePrefix("chapterId=")
        if (id != null) return ChapterRef(key(id), series, slug)
        return chapters(series).firstOrNull { it.ref.slug == slug }?.ref
            ?: throw IOException("Bölüm artık kaynakta bulunamıyor")
    }

    suspend fun content(ref: ChapterRef): ChapterContent {
        val root = JSONObject(get(endpoint("chapters", ref.id)))
        if (root.requiredString("id") != ref.id || root.requiredString("seriesSlug") != ref.seriesSlug ||
            root.requiredString("slug") != ref.slug
        ) throw IOException("Kaynak yanlış bölümü döndürdü")
        if (root.optBoolean("isLocked", false)) {
            throw IOException("Bu bölüm kilitli; sitede erişim izni gerekiyor")
        }
        val pages = root.optJSONArray("pages")
        if (pages != null) validatePageIndices(pages)
        // Live API returns relative .enc URLs. The delivery label alone is not sufficient:
        // imageEnc and encrypted paths must NEVER enter the ordinary image downloader.
        val encrypted = root.optBoolean("imageEnc", false) ||
            root.textOrNull("deliverySystem").equals("secure", ignoreCase = true) ||
            (pages != null && (0 until pages.length()).any { index ->
                pages.getJSONObject(index).textOrNull("url")?.substringBefore('?')
                    ?.endsWith(".enc", ignoreCase = true) == true
            })
        if (encrypted) {
            return ChapterContent(ref, isSecure = true, images = emptyList(),
                expectedPageCount = pages?.length()?.takeIf { it > 0 })
        }
        if (pages == null) throw IOException("Bölüm sayfa listesi bulunamadı")
        if (pages.length() == 0) throw IOException("Bölümde okunabilir sayfa bulunamadı")
        val images = (0 until pages.length()).map { position ->
            val page = pages.getJSONObject(position)
            val index = page.getInt("index")
            if (index < 0) throw IOException("Geçersiz sayfa numarası")
            Image(index, imageUrl(page.requiredString("url")))
        }.sortedBy { it.index }
        if (images.distinctBy { it.index }.size != images.size) throw IOException("Tekrarlanan sayfa numarası")
        return ChapterContent(ref, isSecure = false, images = images)
    }

    fun mangaUrl(slug: String): String = site.newBuilder()
        .encodedPath("/").addPathSegment("manga").addPathSegment(key(slug)).build().toString()

    fun readerUrl(ref: ChapterRef): String = site.newBuilder().encodedPath("/")
        .addPathSegment("manga").addPathSegment(key(ref.seriesSlug))
        .addPathSegment("bolum").addPathSegment(key(ref.slug)).build().toString()

    fun chapterUrl(ref: ChapterRef): String = readerUrl(ref).toHttpUrl().newBuilder()
        .fragment("chapterId=${key(ref.id)}").build().toString()

    fun seriesSlug(value: String): String {
        if (!value.contains('/') && !value.contains(':')) return key(value)
        val path = siteLink(value).pathSegments.filter(String::isNotEmpty)
        if (path.size != 2 || path[0] != "manga") throw IOException("Geçersiz manga bağlantısı")
        return key(path[1])
    }

    private fun parseSeries(item: JSONObject): Series {
        val genres = item.optJSONArray("genres")
        val tags = if (genres == null) emptyList() else (0 until genres.length()).mapNotNull { index ->
            when (val genre = genres.opt(index)) {
                is String -> genre.trim().takeIf { it.isNotEmpty() && it != "null" }
                is JSONObject -> genre.textOrNull("name")
                else -> null
            }
        }
        return Series(
            slug = key(item.requiredString("slug")),
            title = item.requiredString("title"),
            description = item.textOrNull("description"),
            coverUrl = (item.textOrNull("coverImage") ?: item.textOrNull("cover"))?.let(::imageUrl),
            author = item.textOrNull("author"),
            artist = item.textOrNull("artist"),
            status = item.textOrNull("status"),
            genres = (tags + listOfNotNull(item.textOrNull("type"))).distinct(),
        )
    }

    private fun endpoint(vararg segments: String): HttpUrl = api.newBuilder()
        .apply { segments.forEach { addPathSegment(it) } }.build()

    private fun siteLink(value: String): HttpUrl {
        val url = site.resolve(value) ?: throw IOException("Geçersiz kaynak bağlantısı")
        if (!url.isHttps || url.host.removePrefix("www.") != site.host.removePrefix("www.") ||
            url.port != site.port || url.username.isNotEmpty() || url.password.isNotEmpty()
        ) throw IOException("Bağlantı Gölge Bahçesi'ne ait değil")
        return url
    }

    private fun validatePageIndices(pages: JSONArray) {
        if (pages.length() > 2000) throw IOException("Bölüm sayfa sınırını aşıyor")
        val indices = (0 until pages.length()).map { position ->
            val raw = pages.getJSONObject(position).opt("index") as? Number
                ?: throw IOException("Geçersiz sayfa numarası")
            val value = raw.toDouble()
            if (!value.isFinite() || value != value.toInt().toDouble()) throw IOException("Geçersiz sayfa numarası")
            value.toInt()
        }.sorted()
        if (indices.withIndex().any { (position, index) -> position != index }) {
            throw IOException("Eksik veya tekrarlanan sayfa numarası")
        }
    }

    private fun imageUrl(value: String): String {
        val url = site.resolve(value) ?: throw IOException("Geçersiz görsel bağlantısı")
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Güvenli olmayan görsel bağlantısı")
        }
        return url.toString()
    }

    private fun key(value: String): String {
        if (!value.matches(Regex("[\\p{L}\\p{N}_.~-]+")) || value == "." || value == "..") {
            throw IOException("Geçersiz kaynak kimliği")
        }
        return value
    }

    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent())
            .header("Referer", site.toString())
            .header("Origin", site.toString().trimEnd('/'))
            .header("Accept", "application/json").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("Gölge Bahçesi API: HTTP ${it.code}")
                        val body = it.body ?: throw IOException("API yanıtı boş")
                        val source = body.source()
                        source.request(MAX_JSON_BYTES + 1)
                        if (source.buffer.size > MAX_JSON_BYTES) throw IOException("API yanıtı çok büyük")
                        source.buffer.readUtf8().also { result ->
                            if (result.isBlank() || result.trimStart().startsWith('<')) {
                                throw IOException("API yerine HTML/doğrulama sayfası döndü; siteyi tarayıcıda kontrol edin")
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    suspend fun openImage(url: String, chapterUrl: String): InputStream = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(imageUrl(url))
            .header("Referer", siteLink(chapterUrl).toString())
            .header("Origin", site.toString().trimEnd('/'))
            .header("User-Agent", userAgent()).build()
        val call = client.newCall(request)
        val activeResponse = AtomicReference<Response?>()
        continuation.invokeOnCancellation {
            call.cancel()
            activeResponse.getAndSet(null)?.close()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                activeResponse.set(response)
                if (!continuation.isActive) {
                    activeResponse.getAndSet(null)?.close()
                    return
                }
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    activeResponse.getAndSet(null)?.close()
                    if (continuation.isActive) continuation.resumeWithException(IOException("Gölge Bahçesi görseli: HTTP ${response.code}"))
                    return
                }
                val stream = object : FilterInputStream(body.byteStream()) {
                    override fun close() {
                        try { super.close() } finally { activeResponse.getAndSet(null)?.close() }
                    }
                }
                continuation.resume(stream)
            }
        })
    }

    private fun JSONObject.textOrNull(name: String): String? = (opt(name) as? String)
        ?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.requiredString(name: String): String = textOrNull(name)
        ?: throw IOException("API alanı eksik: $name")

    private fun parseDate(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        const val PAGE_SIZE = 24
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024
    }
}
