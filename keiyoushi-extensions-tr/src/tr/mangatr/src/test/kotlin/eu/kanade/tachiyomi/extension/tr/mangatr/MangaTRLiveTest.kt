package eu.kanade.tachiyomi.extension.tr.mangatr

import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Opt-in end-to-end smoke test. It never logs cookies, signatures, or image URLs. */
class MangaTRLiveTest {
    @Before
    fun onlyWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("MANGATR_LIVE_TEST") == "1")
    }

    @Test
    fun `chapter one and two readers decode current configs and real images`() {
        val cookieJar = MemoryCookieJar()
        val gateClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = gateClient.newBuilder()
            .addInterceptor(DDoSGuardInterceptor(gateClient))
            .build()
        val navigationHeaders = navigationHeaders()

        val detailResponse = client.newCall(
            Request.Builder().url(DETAIL_URL).headers(navigationHeaders).get().build(),
        ).execute()
        val detailBody = detailResponse.use { response ->
            assertEquals(200, response.code)
            response.body.string()
        }
        val detail = Jsoup.parse(detailBody, BASE_URL)
        assertEquals("chainsaw-man", detail.selectFirst("#results")?.attr("data-manga"))

        // The chapter AJAX endpoint intermittently rejects CI runner IPs. Reader
        // verification uses the checked-in public chapter listing instead, while
        // every reader, gate, config and image below is still fetched live.
        val fixtureDir = File(requireNotNull(System.getProperty("mangatr.fixtureDir")))
        val chapterListing = File(fixtureDir, "chainsaw_chapters_offset20.html").readText()
        val chapters = MangaTRParser.parseChapterDocument(
            Jsoup.parse(chapterListing, DETAIL_URL),
            chapterFactory = ::createTestChapter,
        ).chapters
        val chapterTwo = chapters.filter { it.chapter_number == 2f }
            .distinctBy { it.url.substringBefore('?') }
        assertEquals(4, chapterTwo.size)
        assertEquals(EXPECTED_READER_IDS, chapterTwo.mapNotNull { READER_ID.find(it.url)?.groupValues?.get(1) }.toSet())
        assertEquals(
            setOf("Bağımsız", "Invictus Fansub, MangaTR", "AnimeWho, Pied Piper", "Şimarik Fansub"),
            chapterTwo.map { it.scanlator }.toSet(),
        )

        val chapterOne = chapters.filter { it.chapter_number == 1f }
            .distinctBy { it.url.substringBefore('?') }
        assertEquals(EXPECTED_CHAPTER_ONE_READER_IDS, chapterOne.mapNotNull { READER_ID.find(it.url)?.groupValues?.get(1) }.toSet())

        val cloudflareImageBlocks = mutableListOf<String>()
        val imageFailures = mutableListOf<String>()
        var decodedImages = 0
        val readersToVerify = chapterOne + chapterTwo
        readersToVerify.forEach { chapter ->
            val readerId = requireNotNull(READER_ID.find(chapter.url)?.groupValues?.get(1))
            val readerUrl = BASE_URL + chapter.url
            val readerResponse = client.newCall(
                Request.Builder().url(readerUrl).headers(navigationHeaders).get().build(),
            ).execute()
            val readerBody = readerResponse.use {
                assertEquals("Reader $readerId failed", 200, it.code)
                it.body.string()
            }
            val readerDocument = Jsoup.parse(readerBody, readerUrl)
            val gatePath = requireNotNull(GATE_PATH.find(readerBody)?.groupValues?.get(1)) {
                "Reader $readerId did not expose its reader gate"
            }
            assertTrue("Reader $readerId exposed an unexpected gate path", gatePath.startsWith("/cek/f/"))
            val gateHeaders = navigationHeaders.newBuilder()
                .mangaTrAjaxHeaders(readerUrl)
                .set("Origin", BASE_URL)
                .build()
            val gatePost = Request.Builder()
                .url(BASE_URL + gatePath)
                .headers(gateHeaders)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            var gateResult = client.newCall(gatePost).execute().use { it.code to it.body.string() }
            if (gateResult.first != 200) {
                val gateGet = Request.Builder().url(BASE_URL + gatePath).headers(gateHeaders).get().build()
                gateResult = client.newCall(gateGet).execute().use { it.code to it.body.string() }
            }
            assertEquals("Reader gate $readerId failed", 200, gateResult.first)
            val gateBody = gateResult.second

            val configScript = requireNotNull(
                readerDocument.selectFirst("script[type=application/json][id^=rdm-]")
                    ?: readerDocument.selectFirst("script[id^=rdm-]"),
            ) { "Reader $readerId did not expose its encrypted config" }
            val pageKey = requireNotNull(
                configScript.attributes().firstNotNullOfOrNull { attr ->
                    attr.value.takeIf { HEX_KEY_REGEX.matches(it) }
                } ?: HEX_KEY_REGEX.find(readerBody)?.value,
            ) { "Reader $readerId did not expose its page key" }
            val gateKey = json.decodeFromString<ReaderGateDto>(gateBody).k
            val config = requireNotNull(
                MangaTRReaderContract.decryptConfig(
                    configScript.data().ifBlank { configScript.html() },
                    gateKey,
                    pageKey,
                ),
            ) { "Reader $readerId config did not match the current or legacy gate key" }
            assertTrue("Reader $readerId did not expose current seeds", config.seeds.isNotEmpty())
            val pages = requireNotNull(MangaTRReaderContract.parseConfiguredPages(readerDocument, config, pageKey)) {
                "Reader $readerId returned no configured pages"
            }
            assertTrue(
                "Reader $readerId still used legacy or decoy data-order",
                pages.all { it.imageUrl?.substringAfter('#', "")?.startsWith(MangaTRReaderContract.CACHE_VERSION_PREFIX) == true },
            )
            assertEquals((pages.indices).toList(), pages.map { it.index })
            assertEquals(pages.size, pages.map { it.imageUrl }.distinct().size)
            if (chapter.chapter_number == 1f) {
                assertEquals("Reader $readerId chapter one page count changed", 52, pages.size)
            }

            val sampleIndexes = if (chapter.chapter_number == 1f) {
                setOf(0, pages.size / 2, pages.lastIndex)
            } else {
                setOf(0)
            }
            pages.filter { it.index in sampleIndexes }.forEach { page ->
                val imageRequest = Request.Builder()
                    .url(requireNotNull(page.imageUrl))
                    .headers(navigationHeaders.newBuilder().mangaTrImageHeaders("$BASE_URL/").build())
                    .get()
                    .build()
                client.newCall(imageRequest).execute().use { image ->
                    val imageBytes = image.body.bytes()
                    val safeErrorBody = if (image.code == 200) {
                        ""
                    } else {
                        imageBytes.toString(Charsets.UTF_8).take(500)
                            .replace(Regex("[A-Za-z0-9_\\-+/=]{24,}"), "<redacted>")
                    }
                    val detail = "HTTP ${image.code}, type=${image.header("Content-Type")}, " +
                        "server=${image.header("Server")}, bytes=${imageBytes.size}"
                    when {
                        image.code == 200 && imageBytes.size > 100 -> {
                            val source = ImageIO.read(ByteArrayInputStream(imageBytes))
                            if (source == null) {
                                imageFailures += "reader $readerId page ${page.index + 1}: unsupported image encoding"
                            } else {
                                val corrected = unscramble(requireNotNull(page.imageUrl), source)
                                decodedImages++
                                if (chapter.chapter_number == 1f) {
                                    val output = File("build/mangatr-live/reader-$readerId/page-${page.index + 1}.png")
                                    output.parentFile.mkdirs()
                                    ImageIO.write(corrected, "png", output)
                                }
                                println(
                                    "MangaTR live reader $readerId: ${pages.size} pages; " +
                                        "page ${page.index + 1} decoded and unscrambled ${corrected.width}x${corrected.height}",
                                )
                            }
                        }

                        image.code == 403 && image.header("Server").equals("cloudflare", ignoreCase = true) &&
                            safeErrorBody.contains("Attention Required", ignoreCase = true) -> {
                            cloudflareImageBlocks += "$readerId/${page.index + 1}"
                            println("MangaTR live reader $readerId: ${pages.size} pages; image blocked by Cloudflare on the runner")
                        }

                        else -> imageFailures += "reader $readerId page ${page.index + 1}: $detail, body=$safeErrorBody"
                    }
                }
            }
        }
        assertTrue("Non-Cloudflare image failures: $imageFailures", imageFailures.isEmpty())
        if (decodedImages == 0 && cloudflareImageBlocks.isNotEmpty()) {
            assumeTrue(
                "All eight reader page lists passed, but Cloudflare blocked every image on this runner: $cloudflareImageBlocks",
                false,
            )
        }
        assertTrue("Cloudflare blocked only some images: $cloudflareImageBlocks", cloudflareImageBlocks.isEmpty())
        assertEquals("Not all selected chapter images were decoded and unscrambled", 16, decodedImages)
    }

    private fun unscramble(imageUrl: String, source: BufferedImage): BufferedImage {
        val fragment = requireNotNull(imageUrl.toHttpUrl().fragment)
        val codes = fragment.removePrefix(MangaTRReaderContract.CACHE_VERSION_PREFIX)
            .split(',')
            .map { it.toInt() }
        assertEquals(codes.indices.toList(), codes.map { it % 100 }.sorted())
        assertTrue(codes.size <= source.height)
        val stripHeight = source.height / codes.size
        assertTrue(stripHeight > 0)
        val output = BufferedImage(source.width, stripHeight * codes.size, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            codes.forEachIndexed { sourceIndex, code ->
                val strip = source.getSubimage(0, sourceIndex * stripHeight, source.width, stripHeight)
                val top = (code % 100) * stripHeight
                val transform = AffineTransform()
                transform.translate(
                    if (MangaTRReaderContract.flipHorizontally(code)) source.width.toDouble() else 0.0,
                    (top + if (MangaTRReaderContract.flipVertically(code)) stripHeight else 0).toDouble(),
                )
                transform.scale(
                    if (MangaTRReaderContract.flipHorizontally(code)) -1.0 else 1.0,
                    if (MangaTRReaderContract.flipVertically(code)) -1.0 else 1.0,
                )
                graphics.drawImage(strip, transform, null)
            }
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun navigationHeaders(): Headers = Headers.Builder()
        .set("User-Agent", USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Accept-Encoding", "identity")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Referer", "$BASE_URL/")
        .set("Origin", BASE_URL)
        .build()

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies += cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        private const val BASE_URL = "https://manga-tr.com"
        private const val DETAIL_URL = "$BASE_URL/manga-chainsaw-man.html"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val EXPECTED_READER_IDS = setOf("123931", "125989", "135286", "136245")
        private val EXPECTED_CHAPTER_ONE_READER_IDS = setOf("122230", "125451", "135285", "136286")
        private val READER_ID = Regex("""(?:^|/)id-(\d+)-read-""")
        private val GATE_PATH = Regex("""_fpx\s*=\s*"([^"]+)"""")
        private val HEX_KEY_REGEX = Regex("""[0-9a-f]{32}""")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
