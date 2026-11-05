package eu.kanade.tachiyomi.extension.tr.mangadiyari

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.get
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fixtures are the exact JSON shapes the site returned while the extension was fixed
 * (2026-11-06); keep them verbatim so a contract change shows up as a test failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MangaDiyariTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val listBody = """
        {"series":[
          {"id":16,"title":"Ben Ölümsüzüm","slug":"ben-olumsuzum","description":"A\u00e7\u0131klama",
           "cover_url":"/uploads/covers/i302868-1.jpg","author":"June Snow Studio","artist":"June Snow Studio",
           "status":"ongoing","genres":["Aksiyon","Fantezi"],"rating":10,"views":22616,"published":1,
           "type":"manhua","created_at":"2026-04-25T17:57:48.000Z","is_adult":0,"alt_names":"The Almighty Cultivator",
           "discord_role_id":"1412791700459622470","support_coins":0,"chapterCount":793},
          {"id":40,"title":"K\u00fc\u00e7\u00fck Prenses","slug":"kucuk-prenses","cover_url":null,
           "status":"completed","genres":[],"type":"manga","alt_names":"","chapterCount":0}
        ],"total":2,"page":1,"hasMore":true}
    """.trimIndent()

    @Test
    fun `series list keeps optional fields optional`() {
        val response = json.decodeFromString<SeriesListResponse>(listBody)
        assertEquals(2, response.series.size)
        assertTrue(response.hasMore)

        val first = response.series[0].toSManga(BASE)
        assertEquals("ben-olumsuzum", first.url)
        assertEquals("Ben Ölümsüzüm", first.title)
        assertEquals("$BASE/uploads/covers/i302868-1.jpg", first.thumbnail_url)
        assertEquals(SManga.ONGOING, first.status)
        assertTrue(first.genre!!.startsWith("Manhua"))
        assertTrue(first.description!!.contains("Alternatif isimler: The Almighty Cultivator"))

        // A series without a cover must not produce a thumbnail pointing at the base URL.
        val second = response.series[1].toSManga(BASE)
        assertNull(second.thumbnail_url)
        assertEquals(SManga.COMPLETED, second.status)
        assertNull(second.genre)
    }

    @Test
    fun `details response tolerates missing alt names and chapters`() {
        val body = """
            {"series":{"id":24,"title":"İlahi Savaşçı","slug":"ilahi-savasci-goksel-hukumdar",
             "description":null,"cover_url":"/uploads/covers/i393572.png","status":"ongoing",
             "genres":["Aksiyon"],"type":"manga"},"chapters":[]}
        """.trimIndent()
        val response = json.decodeFromString<SeriesDetailsResponse>(body)
        assertEquals("ilahi-savasci-goksel-hukumdar", response.series.slug)
        assertTrue(response.chapters.isEmpty())
        assertNull(response.series.toSManga(BASE).description)
    }

    @Test
    fun `chapters are ordered without assuming a contiguous numbering`() {
        val body = """
            {"series":{"slug":"ben-olumsuzum","title":"Ben Ölümsüzüm"},
             "chapters":[
               {"id":308,"series_id":16,"chapter_number":202,"title":"Bölüm 203"},
               {"id":793,"series_id":16,"chapter_number":687,"title":"Bölüm 689"},
               {"id":326,"series_id":16,"chapter_number":220,"title":"Bölüm 221"},
               {"id":326,"series_id":16,"chapter_number":220,"title":"Bölüm 221"}
             ]}
        """.trimIndent()
        val details = json.decodeFromString<SeriesDetailsResponse>(body)
        val chapters = details.chapters.toSChapters(details.series.slug)

        assertEquals(listOf(687f, 220f, 202f), chapters.map { it.chapter_number })
        assertEquals(listOf("793", "326", "308"), chapters.map { it.url })
        assertEquals("Bölüm 689", chapters.first().name)
        // The reader route is number based, so a real chapter has to map to its number.
        assertEquals("$BASE/seri/ben-olumsuzum/bolum/687", readerUrl(BASE, details.series.slug, "687"))
    }

    @Test
    fun `chapter without a number falls back instead of failing to parse`() {
        val body = """
            {"series":{"slug":"bos-seri","title":"Boş Seri"},
             "chapters":[{"id":41,"series_id":9,"chapter_number":null,"title":null},
                         {"id":42,"series_id":9,"chapter_number":12.5}]}
        """.trimIndent()
        val details = json.decodeFromString<SeriesDetailsResponse>(body)
        val chapters = details.chapters.toSChapters(details.series.slug)

        assertEquals(2, chapters.size)
        assertEquals("Bölüm 41", chapters.last().name)
        assertEquals(-1f, chapters.last().chapter_number)
        assertNull(chapters.last().memo["number"])
        // Half chapters keep their decimal point, otherwise the reader URL would point elsewhere.
        assertEquals("Bölüm 12.5", chapters.first().name)
        assertEquals("12.5", chapters.first().memo["number"]!!.toString().trim('"'))
    }

    @Test
    fun `page list prefers display image and falls back to the image path`() {
        val body = """
            {"chapter":{"id":3851,"chapter_number":435},"series":{"slug":"dovus-sanatlarinin-zirvesi"},
             "pages":[
               {"page_number":2,"image_path":"https://cdn.example/b/002.jpg","display_image":"https://cdn.example/b/002.jpg"},
               {"page_number":1,"image_path":"https://cdn.example/a/001.webp","display_image":"https://cdn.example/a/001.webp"},
               {"page_number":3,"image_path":"https://cdn.example/c/003.jpg","display_image":null},
               {"page_number":4,"image_path":"/uploads/pages/9/page_004.webp","display_image":"/uploads/pages/9/page_004.webp"},
               {"page_number":5,"image_path":null,"display_image":null},
               {"page_number":6,"image_path":"//cdn.example/d/006.jpg","display_image":null}
             ]}
        """.trimIndent()
        val response = json.decodeFromString<ChapterPagesResponse>(body)
        val pages = response.pages.toPages(BASE, "$BASE/seri/slug/bolum/435")

        // Page 5 carries no image and is skipped; the rest stay in page order.
        assertEquals(5, pages.size)
        assertEquals(
            listOf(
                "https://cdn.example/a/001.webp",
                "https://cdn.example/b/002.jpg",
                "https://cdn.example/c/003.jpg",
                "$BASE/uploads/pages/9/page_004.webp",
                "https://cdn.example/d/006.jpg",
            ),
            pages.map { it.imageUrl },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.index })
        assertEquals("$BASE/seri/slug/bolum/435", pages.first().url)
    }

    @Test
    fun `list url sends the typed query and the site genre names`() {
        val url = seriesListUrl(BASE, page = 2, query = "Şeytan Kral", sort = "popular", type = "manhwa", status = "ongoing", genres = "Aksiyon,Fantezi")

        assertEquals("$BASE/api/series", url.toString().substringBefore('?'))
        assertEquals("Şeytan Kral", url.queryParameter("search"))
        assertEquals("Aksiyon,Fantezi", url.queryParameter("genre"))
        assertEquals("popular", url.queryParameter("sort"))
        assertEquals("manhwa", url.queryParameter("type"))
        assertEquals("ongoing", url.queryParameter("status"))
        assertEquals("24", url.queryParameter("limit"))
        assertEquals("2", url.queryParameter("page"))
    }

    @Test
    fun `list url omits empty filters`() {
        val url = seriesListUrl(BASE, page = 1)

        assertNull(url.queryParameter("search"))
        assertNull(url.queryParameter("genre"))
        assertNull(url.queryParameter("status"))
        assertNull(url.queryParameter("type"))
        assertEquals("latest", url.queryParameter("sort"))
    }

    @Test
    fun `latest updates url mirrors the popular paging`() {
        val url = latestUpdatesUrl(BASE, page = 3)
        assertEquals("$BASE/api/series/latest-updates", url.toString().substringBefore('?'))
        assertEquals("24", url.queryParameter("limit"))
        assertEquals("3", url.queryParameter("page"))
    }

    @Test
    fun `genre filter collapses duplicate names`() {
        val filter = GenreFilter()
        val action = filter.state.first { it.value == "Aksiyon" }
        val romance = filter.state.first { it.value == "Romantizm" }

        action.state = true
        romance.state = true
        assertEquals("Aksiyon,Romantizm", filter.toUriPart())

        // Selecting everything must not repeat a value: the server matches the whole list.
        filter.state.forEach { it.state = true }
        val joined = filter.toUriPart().split(",")
        assertEquals(joined.size, joined.distinct().size)
        assertFalse(joined.any { it.isBlank() })
    }

    private companion object {
        const val BASE = "https://mangadiyari.com"
    }
}
