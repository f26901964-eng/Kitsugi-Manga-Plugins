"""SadScans kaynak sözleşmesi için regresyon nöbetçileri.

Bu testler canlı API/Android çalışma testi DEĞİLDİR: kaynak kodun, canlı JS parçalarından
(`1vy0fm3wxax8_.js`, `22yc01h34_ye1.js`) çıkarılan sözleşmeye uyduğunu doğrular.
"""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / 'keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans'
SOURCE = BASE / 'Sadscans.kt'
FRONTEND = BASE / 'SadscansFrontend.kt'
FILTERS = BASE / 'SortFilter.kt'
GRADLE = ROOT / 'keiyoushi-extensions-tr/src/tr/sadscans/build.gradle.kts'


class SadscansSourceTests(unittest.TestCase):
    def test_site_procedures_and_sort_values(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('_s4d7g2k9m0', source)
        self.assertIn('_n3p6q1v8x5', source)
        filters = FILTERS.read_text(encoding='utf-8')
        for value in ('"name"', '"-name"', '"views"', '"-views"', '"rating"', '"-rating"'):
            self.assertIn(value, filters)
        # Sitede bulunmayan sıralamalar uydurulmamalı.
        for value in ('updatedAt', 'createdAt', 'totalViews', 'postTitle'):
            self.assertNotIn(value, filters)
        statuses = FILTERS.read_text(encoding='utf-8')
        for status in ('devam-ediyor', 'tamamlandi', 'birakildi'):
            self.assertIn(status, statuses)

    def test_cloudflare_interceptor_is_kept(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertNotIn('"CloudflareInterceptor"', source)
        self.assertNotIn('removeAll', source)

    def test_direct_api_failure_always_falls_back_to_the_site_ui(self):
        source = SOURCE.read_text(encoding='utf-8')
        listing = source.split('private suspend fun fetchSeries(', 1)[1].split('private suspend fun directSeries(', 1)[0]
        self.assertIn('attempt {', listing)
        self.assertIn('frontendPage(', listing)
        self.assertIn('SadscansFrontend.listScript', listing)
        detail = source.split('private suspend fun fetchSeriesData(', 1)[1].split('private suspend fun fetchAllViaApi(', 1)[0]
        self.assertIn('SadscansFrontend.detailsScript', detail)

    def test_search_is_normalised_like_the_site(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('private fun normalizeSearch(', source)
        for char in ("'İ', 'I', 'ı'", "'Ş', 'ş'", "'Ğ', 'ğ'", "'Ü', 'ü'", "'Ö', 'ö'", "'Ç', 'ç'"):
            self.assertIn(char, source)
        self.assertIn('directSeries(page, sort, query, type, status)', source)

    def test_observed_chapter_limit(self):
        self.assertIn('CHAPTER_PAGE_LIMIT = 10', SOURCE.read_text(encoding='utf-8'))

    def test_no_speculative_reader_endpoints(self):
        source = SOURCE.read_text(encoding='utf-8')
        reader = source.split('override suspend fun getPageList(', 1)[1].split('override fun imageRequest(', 1)[0]
        self.assertNotIn('chapterImageProcedures', source)
        self.assertNotIn('getChapterData', reader)
        self.assertIn('SadscansFrontend.readerScript', reader)
        self.assertIn('imageUrl = url', reader)

    def test_canonical_chapter_href_is_preserved(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('val readerPath = href?.let', source)
        self.assertIn('url = readerPath ?: ', source)

    def test_list_script_uses_site_controls_and_is_not_fatal(self):
        script = FRONTEND.read_text(encoding='utf-8')
        self.assertIn('input[placeholder="Aramak istediğiniz seriyi yazın"]', script)
        self.assertIn("if (!container) return {done: true};", script)
        self.assertIn('div[aria-hidden="true"].h-px.w-full', script)
        self.assertIn('seriler/', script)
        self.assertIn('sonuç bulunamadı', script)

    def test_frontend_scripts_are_three_and_guarded(self):
        script = FRONTEND.read_text(encoding='utf-8')
        self.assertEqual(script.count('return """'), 3)
        self.assertIn("if (typeof fetch !== 'function')", script)
        self.assertIn('credentials: \'same-origin\'', script)
        self.assertIn('/api/trpc/_s4d7g2k9m0.getSeries', script)
        self.assertIn('/api/trpc/_n3p6q1v8x5.getSeriesData', script)

    def test_reader_tolerates_cdn_hosts(self):
        script = FRONTEND.read_text(encoding='utf-8')
        reader = script.split('fun readerScript(', 1)[1]
        self.assertNotIn("url.host !== location.host", reader, 'bölüm görselleri CDN\'de olabilir')
        self.assertIn("lower.indexOf('/banner')", reader)

    def test_version_is_bumped_for_the_fix(self):
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn('versionCode = 10', gradle)
        self.assertIn('baseUrl = "https://sadscans.net"', gradle)


if __name__ == '__main__':
    unittest.main()
