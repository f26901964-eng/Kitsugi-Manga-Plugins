"""Source regression guards from SadScans' current /seriler client.

These are not live API or Android runtime tests.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
KEI = ROOT / 'keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/Sadscans.kt'
IKEN = ROOT / 'kotatsu-parsers-tr/src/main/kotlin/org/koitharu/kotatsu/parsers/site/iken/IkenParser.kt'


class SadscansSourceTests(unittest.TestCase):
    def test_only_observed_sorts(self):
        for path in (KEI, IKEN):
            source = path.read_text()
            self.assertNotIn('"-updatedAt"', source)
            self.assertNotIn('"-id"', source)

    def test_list_does_not_decode_unused_numeric_metadata(self):
        source = KEI.read_text()
        listing = source.split('private suspend fun fetchSeries(', 1)[1].split('// ============================== Details', 1)[0]
        self.assertNotIn('decodeFromJsonElement', listing)
        self.assertNotIn('runCatching', listing)
        self.assertIn('item["name"]', listing)
        self.assertIn('item["href"]', listing)

    def test_detail_errors_not_silenced(self):
        source = KEI.read_text()
        method = source.split('private suspend fun fetchSeriesDataPage(', 1)[1].split('private suspend fun fetchChapters(', 1)[0]
        self.assertNotIn('runCatching', method)

    def test_observed_chapter_limit(self):
        self.assertIn('CHAPTER_PAGE_LIMIT = 10', KEI.read_text())
        self.assertIn('chapterPageLimit: Int = 10', IKEN.read_text())

    def test_no_speculative_reader_endpoints_or_schema_images(self):
        source = KEI.read_text()
        reader = source.split('override suspend fun getPageList(', 1)[1].split('// ============================== Helpers', 1)[0]
        self.assertNotIn('chapterImageProcedures', source)
        self.assertNotIn('getChapterData', reader)
        self.assertNotIn('select("script")', reader)
        self.assertIn('SadscansFrontend.readerScript', reader)

    def test_canonical_chapter_href_is_preserved(self):
        self.assertIn('url = readerPath ?: ', KEI.read_text())
