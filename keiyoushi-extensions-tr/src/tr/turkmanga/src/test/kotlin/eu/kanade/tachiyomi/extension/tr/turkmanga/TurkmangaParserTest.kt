package eu.kanade.tachiyomi.extension.tr.turkmanga

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkmangaParserTest {
    private val baseUrl = "https://turkmanga.org"
    private val mangaUrl = "$baseUrl/manga/chainsaw-man"
    private val chapterUrl = "$mangaUrl/bolum-1"

    @Test
    fun nextDataSearchCardsKeepTitlesCoversAndPagination() {
        val document = Jsoup.parse(
            """
            <script id="__NEXT_DATA__" type="application/json">
              {"props":{"pageProps":{"manga_data":{"mangas":[
                {"_id":"a1","slug":"chainsaw-man","name":"Chainsaw Man","description":"Testere Adam","type":"Manga","finished":false,"nsfw":false,"episode":{"slug":"bolum-1","date":"2025-06-02T10:12:57.299Z"}},
                {"_id":"a2","slug":"berserk","name":"Berserk","type":"Manga","finished":true,"nsfw":false}
              ],"hasNextPage":true}}},"page":"/search"}
            </script>
            """.trimIndent(),
            baseUrl,
        )

        val page = TurkmangaParser.parseMangaList(document, baseUrl, page = 1)

        assertEquals(listOf("Chainsaw Man", "Berserk"), page.mangas.map { it.title })
        assertEquals("/manga/chainsaw-man", page.mangas.first().url)
        assertEquals("https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&height=900", page.mangas.first().thumbnail_url)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun detailJsonLdProvidesMetadataChaptersAndTranslators() {
        val document = Jsoup.parse(
            """
            <html><head><meta property="og:image" content="https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&amp;height=900"></head><body>
              <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"ComicSeries","name":"Chainsaw Man","url":"$mangaUrl",
                   "description":"Denji ve Pochita'nın hikâyesi.","genre":["Aksiyon","Dram"],
                   "author":{"@type":"Person","name":"Tatsuki Fujimoto,"},
                   "translator":{"@type":"Person","name":"Manga Şehri"},
                   "creativeWorkStatus":"Ongoing","image":{"@type":"ImageObject","url":"https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&amp;height=900"},
                   "hasPart":[
                     {"@type":"ComicIssue","url":"$chapterUrl","name":"Chainsaw Man – Bölüm 1","issueNumber":1,"datePublished":"2025-06-02T10:12:57.299Z","translator":{"@type":"Person","name":"Manga Şehri"}},
                     {"@type":"ComicIssue","url":"$mangaUrl/bolum-2","name":"Bölüm 2","issueNumber":2,"translator":{"@type":"Person","name":"Başka Çeviri"}}
                   ]}
                ]}
              </script>
            </body></html>
            """.trimIndent(),
            mangaUrl,
        )

        val update = TurkmangaParser.parseMangaDetails(document, baseUrl, "/manga/chainsaw-man")

        assertEquals("Chainsaw Man", update.manga.title)
        assertEquals("Denji ve Pochita'nın hikâyesi.", update.manga.description)
        assertEquals("Tatsuki Fujimoto", update.manga.author)
        assertEquals("Aksiyon, Dram", update.manga.genre)
        assertEquals("https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&height=900", update.manga.thumbnail_url)
        assertEquals(SManga.ONGOING, update.manga.status)
        assertEquals(2, update.chapters.size)
        assertEquals("/manga/chainsaw-man/bolum-1", update.chapters.first().url)
        assertEquals(1f, update.chapters.first().chapter_number)
        assertEquals("Manga Şehri", update.chapters.first().scanlator)
        assertTrue(update.chapters.first().date_upload > 0L)
        assertEquals("Başka Çeviri", update.chapters.last().scanlator)
    }

    @Test
    fun readerUsesOnlyTheMatchingComicIssueImageListInOrder() {
        val document = Jsoup.parse(
            """
            <script type="application/ld+json">
              {"@graph":[
                {"@type":"ComicSeries","url":"$mangaUrl","image":"https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&amp;height=900"},
                {"@type":"ComicIssue","url":"$chapterUrl","image":[
                  "https://cdnapi.turkmanga.org/image/683d77ea901ca67b36ff3118",
                  "https://cdnapi.turkmanga.org/image/683d77ec901ca67b36ff311a"
                ]}
              ]}
            </script>
            """.trimIndent(),
            chapterUrl,
        )

        assertEquals(
            listOf(
                "https://cdnapi.turkmanga.org/image/683d77ea901ca67b36ff3118",
                "https://cdnapi.turkmanga.org/image/683d77ec901ca67b36ff311a",
            ),
            TurkmangaParser.parseImageUrls(document, baseUrl, chapterUrl),
        )
    }

    @Test
    fun htmlCardFallbackAndReaderLazyImageFallbackAreSupported() {
        val cards = Jsoup.parse(
            """
            <a href="/manga/chainsaw-man" class="manga-card">
              <img alt="Chainsaw Man" data-src="https://api.turkmanga.org/api/media/manga/chainsaw-man?width=600&amp;height=900">
            </a>
            """.trimIndent(),
            baseUrl,
        )
        val result = TurkmangaParser.parseMangaList(cards, baseUrl, page = 1)
        assertEquals("Chainsaw Man", result.mangas.single().title)
        assertEquals("/manga/chainsaw-man", result.mangas.single().url)
        assertFalse(result.hasNextPage)

        val reader = Jsoup.parse(
            """<div id="reader"><img data-src="https://cdnapi.turkmanga.org/image/page-1"><img src="https://cdnapi.turkmanga.org/image/page-2"></div>""",
            chapterUrl,
        )
        assertEquals(
            listOf("https://cdnapi.turkmanga.org/image/page-1", "https://cdnapi.turkmanga.org/image/page-2"),
            TurkmangaParser.parseImageUrls(reader, baseUrl, chapterUrl),
        )
    }

    @Test
    fun urlsAreNormalizedAndOffSiteMangaLinksAreRejected() {
        assertEquals("/manga/chainsaw-man", TurkmangaParser.mangaPath(mangaUrl, baseUrl))
        assertEquals("/manga/chainsaw-man/bolum-1", TurkmangaParser.chapterPath("/manga/chainsaw-man/bolum-1", baseUrl))
        assertNull(TurkmangaParser.mangaPath("https://example.com/manga/chainsaw-man", baseUrl))
        assertNull(TurkmangaParser.chapterPath("https://turkmanga.org/manga/chainsaw-man", baseUrl))
        val searchUrl = TurkmangaParser.searchUrl(baseUrl, 2, "chainsaw man", "aksiyon")
        assertEquals("/search", searchUrl.encodedPath)
        assertEquals("chainsaw man", searchUrl.queryParameter("input"))
        assertEquals("2", searchUrl.queryParameter("page"))
        assertEquals("aksiyon", searchUrl.queryParameter("genre"))
    }
}
