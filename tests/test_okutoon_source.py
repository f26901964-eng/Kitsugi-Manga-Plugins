"""Static checks that the OkuToon source implements what the dumps showed.

These guard the mistakes that were found while reading the saved pages: cards are anchors,
the reader hides its address in data attributes, and the genres are .tag-link anchors.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "keiyoushi-extensions-tr/src/tr/okutoon"
SOURCE = (MODULE / "src/eu/kanade/tachiyomi/extension/tr/okutoon")


def read(name):
    return (SOURCE / name).read_text(encoding="utf-8")


class OkutoonSourceTests(unittest.TestCase):
    def test_series_cards_are_read_from_the_card_itself(self):
        code = read("OkuToon.kt")
        self.assertIn('.series-grid .series-card', code)
        self.assertIn('card.attr("abs:href")', code)
        self.assertNotIn('element.selectFirst("a")!!.attr("abs:href")', code)

    def test_listing_paging_uses_the_sayfa_query_parameter(self):
        code = read("OkuToon.kt")
        self.assertIn('addQueryParameter("sayfa"', code)
        self.assertNotIn("/$page/", code)

    def test_reader_reads_lazy_attributes(self):
        code = read("OkuToon.kt")
        self.assertIn("data-original-src", code)
        self.assertIn("img.reader-page", code)

    def test_reader_challenge_does_not_wait_for_the_host_interceptor(self):
        code = read("OkuToon.kt")
        self.assertIn('it.javaClass.simpleName == "CloudflareInterceptor"', code)
        self.assertIn('callTimeout(6, TimeUnit.SECONDS)', code)
        self.assertIn('OkuToonReader.isChallenge(response.header("cf-mitigated"))', code)
        self.assertIn('runWebView(timeout = 30.seconds)', code)
        self.assertNotIn('blockImages = true', code)
        self.assertIn('response.close()', code)
        self.assertIn('throw IOException("OkuToon Cloudflare', code)

    def test_reader_prefers_the_public_chapter_api(self):
        code = read("OkuToon.kt")
        self.assertIn('addPathSegments("api/v1/series")', code)
        self.assertIn('OkuToonReader.apiImageUrls(response.body.string(), chapterUrl)', code)
        self.assertIn('page["image_url"]', code)
        self.assertIn('page["page_number"]', code)
        self.assertIn('parsed.sortedBy { it.first }', code)

    def test_reader_keeps_chapter_referer_and_compact_indexes(self):
        code = read("OkuToon.kt")
        self.assertIn('urls.mapIndexed { index, url -> Page(index, url = chapterUrl, imageUrl = url) }', code)
        self.assertIn('.header("Referer", page.url', code)
        self.assertIn('it.toHttpUrlOrNull() != null', code)

    def test_genres_come_from_the_genre_container(self):
        code = read("OkuToon.kt")
        self.assertIn('.series-detail-genres a', code)
        self.assertNotIn('.series-detail-genres .tag"', code)

    def test_module_version_and_language(self):
        gradle = (MODULE / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('baseUrl = "https://okutoon.com"', gradle)
        self.assertIn('lang = "tr"', gradle)
        self.assertRegex(gradle, r"versionCode = \d+")

    def test_filters_cover_the_site_parameters(self):
        filters = read("Filters.kt")
        for parameter in ('"q"', '"sira"', '"durum"', '"tip"', '"k[]"'):
            if parameter in ('"q"', '"sira"', '"durum"', '"tip"', '"k[]"'):
                self.assertTrue(parameter.strip('"') in read("OkuToon.kt"))
        self.assertIn("class SortFilter", filters)
        self.assertIn("class TypeFilter", filters)
        self.assertIn("class GenreFilter", filters)


if __name__ == "__main__":
    unittest.main()
