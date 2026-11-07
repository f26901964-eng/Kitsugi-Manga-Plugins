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
        # Yönlendirici adı sabit koda gömülü metin değil, sabit + sayfadan keşifle kurulur.
        self.assertIn("var DEFAULT_ROUTER = '_s4d7g2k9m0'", script)
        self.assertIn("var DEFAULT_DETAIL_ROUTER = '_n3p6q1v8x5'", script)
        self.assertIn("'.getSeries?batch=1'", script, 'tRPC yolu batch disinda parametre tasimamali')
        self.assertIn("'.getSeriesData?batch=1'", script)
        self.assertIn("'.getSeriesData?batch=1'", script)

    def test_list_search_does_not_depend_on_the_search_box(self):
        """1.6.11'de arama, kutu bulunamayınca tRPC kademesine hiç ulaşmadan ölüyordu.""" 
        script = FRONTEND.read_text(encoding='utf-8')
        listing = script.split('fun listScript(', 1)[1].split('function normalize(', 1)[0]
        self.assertNotIn('if (!input) return null;', listing, 'kutu yoksa betik ölmemeli')
        self.assertIn('function searchInput()', listing)
        self.assertIn("candidate.getAttribute('type')", listing)

    def test_router_names_are_discovered_from_the_page(self):
        script = FRONTEND.read_text(encoding='utf-8')
        self.assertEqual(script.count('function discoverRouters()'), 2, 'liste ve detay keşif yapmalı')
        self.assertIn("script[src*=\"/_next/static/\"]", script)
        self.assertIn('getSeriesData|getSeries', script)

    def test_browser_challenge_is_reported_separately(self):
        script = FRONTEND.read_text(encoding='utf-8')
        self.assertIn('Just a moment', script)
        self.assertIn('Cloudflare doğrulaması geçilemedi', script)

    def test_time_budget_fits_the_host_app(self):
        """Kitsugi/Mihon kaynak aramasini 25 sn'de kesiyor; toplam butce bunun altinda kalmali."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('callTimeout(5, TimeUnit.SECONDS)', source)
        self.assertIn('connectTimeout(4, TimeUnit.SECONDS)', source)
        self.assertIn('timeout: Duration = 20.seconds', source, 'frontendPage varsayilani 20 sn olmali')
        self.assertIn('timeout = 18.seconds', source, 'liste WebView suresi challenge beklemesini karsilamali')
        self.assertNotIn('callTimeout(45', source)

    def test_sitemap_is_the_last_resort(self):
        """Sitemap kademesi JS/Cloudflare gerektirmez ama kapak döndürmez: en sonda denenir."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('sitemapSearch(', source)
        self.assertIn('SITEMAP_TTL_MS', source)
        self.assertIn('/sitemap.xml', source)
        self.assertIn('"/seriler/${entry.slug}"', source, 'sitemap girdisi detay rotasina baglanmali')
        self.assertIn('Normalizer.normalize', source, 'Turkce karakterler ASCII anahtara cevrilmeli')
        self.assertLess(source.index('SadscansFrontend.listScript'),
                        source.index('attempt { sitemapSearch(query) }'))

    def test_cloudflare_challenge_is_waited_not_failed_fast(self):
        """Doğrulama ekranı görüldüğü an hata verilmemeli; JS challenge 2-5 sn'de geçer."""
        frontend = (SOURCE.parent / 'SadscansFrontend.kt').read_text(encoding='utf-8')
        self.assertIn('CF_PRELUDE', frontend)
        self.assertIn('__sadscansCfStart', frontend)
        self.assertIn('< 12000) return null', frontend, 'doğrulama ekranında beklenmeli')
        self.assertIn('challenge: true', frontend)
        self.assertIn('blocked: true', frontend, 'sert WAF bloğu ayrı raporlanmalı')
        self.assertEqual(frontend.count('${CF_PRELUDE}'), 3, 'üç script de aynı önsözü kullanmalı')
        self.assertNotIn("return out({error: 'SadScans tarayıcı doğrulaması istiyor", frontend,
                         'eski ani-hata metni kalmamalı')

    def test_api_tier_requires_clearance_cookie(self):
        """cf_clearance yokken tRPC denenirse uygulamanın 25 sn bütçesi challenge'ta biter."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('private fun hasClearance()', source)
        self.assertIn('loadForRequest("$siteBase/".toHttpUrl())', source)
        self.assertIn('val direct = if (hasClearance())', source)
        self.assertIn('val direct = if (hasClearance()) attempt { fetchAllViaApi(slug) } else null', source)

    def test_user_agent_comes_from_webview(self):
        """cf_clearance çerezi UA'ya bağlı; API çağrıları WebView UA'sı ile gitmeli."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('private var webViewUserAgent: String?', source)
        self.assertIn('private fun rememberUserAgent(result: JsonObject)', source)
        self.assertIn('add("User-Agent", ua)', source)
        self.assertGreaterEqual(source.count('rememberUserAgent('), 4, 'liste/detay/okuyucu UA yakalamalı')

    def test_trpc_uses_post_body_not_query_json(self):
        """Cloudflare WAF, sorgu dizesindeki JSON'u (input=) 'malformed data' sayıp sert blok uyguluyor."""
        source = SOURCE.read_text(encoding='utf-8')
        frontend = FRONTEND.read_text(encoding='utf-8')
        self.assertNotIn('addQueryParameter("input"', source, 'JSON sorgu dizesine konmamalı')
        self.assertIn('addQueryParameter("batch", "1")', source)
        self.assertIn('.post(envelope.toRequestBody(JSON_MEDIA_TYPE))', source)
        self.assertNotIn("input=", frontend.split("function trpcUrl")[1].split("function trpcBody")[0],
                         'sayfa ici fetch de JSON u URL e koymamali')
        self.assertIn("method: 'POST'", frontend)
        self.assertIn('?batch=1', frontend)

    def test_covers_and_reader_images(self):
        """Kapak ve bölüm görselleri: lazy/srcset/_next/image ve sorgu dizili CDN adresleri."""
        frontend = FRONTEND.read_text(encoding='utf-8')
        source = SOURCE.read_text(encoding='utf-8')
        # Kart kapağı: currentSrc || src || data-src || srcset, _next/image çözümü
        self.assertIn('function imageUrl(node)', frontend)
        self.assertIn("image.getAttribute('data-src')", frontend)
        self.assertIn('srcset', frontend)
        self.assertIn('/_next/image', frontend)
        self.assertIn('anchor.querySelector(\'img, picture source\')', frontend)
        # Okuyucu: uzantı sorgu dizesinden önce de gelebilir, CDN/upload yolları da kabul
        self.assertIn("(\\?|$)/.test(lower)", frontend)
        self.assertIn("lower.indexOf('/uploads/') >= 0", frontend)
        # Kotlin: kapak adresi alanları esnek + next/image sarmalayıcısı çözülür
        self.assertIn('"thumb", "thumbnail", "cover", "image"', source)
        self.assertIn('private fun unwrapNextImage(', source)
        self.assertIn('absolute(it)', source)

    def test_cover_capable_tier_runs_before_sitemap(self):
        """Sitemap kademesi kapak döndürmez: kapaklı arayüz kademesi ondan önce denenmeli."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertLess(source.index('SadscansFrontend.listScript'),
                        source.index('attempt { sitemapSearch(query) }'),
                        'WebView (kapakli) kademesi sitemap tan once olmali')

    def test_new_domain_support(self):
        """Site sadscans.org'a taşınıyor; sert blokta bir kez yedek alan adına geçilmeli."""
        source = SOURCE.read_text(encoding='utf-8')
        frontend = FRONTEND.read_text(encoding='utf-8')
        self.assertIn('MIRROR_BASES', source)
        self.assertIn('sadscans.org', source)
        self.assertIn('private val siteBase: String get() = activeBase ?: baseUrl', source)
        self.assertIn('private var failoverDone', source)
        self.assertIn('activeBase = alternative', source)
        # URL üretiminde siteBase kullanılmalı (baseUrl sabit kalırsa taşınma çalışmaz)
        self.assertIn('"$siteBase/seriler"', source)
        self.assertIn('"$siteBase/api/trpc/$procedure"', source)
        self.assertIn('"$siteBase/sitemap.xml"', source)
        # "Site yapım aşamasında" sayfası ayrı ve anlaşılır hata vermeli
        self.assertIn('YAPIM AŞAMASINDA', frontend)
        self.assertIn('migrating: true', frontend)

    def test_waf_block_cools_down_without_requests(self):
        """Sert blok yiyince siteye istek atmaya devam etmek bloğu uzatır."""
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('private fun isWafBlocked(', source)
        self.assertIn('Sorry, you have been blocked', source)
        self.assertIn('BLOCK_COOLDOWN_MS', source)
        self.assertGreaterEqual(source.count('ensureNotBlocked()'), 3, 'liste/detay/okuyucu blok kontrolü yapmalı')
        self.assertIn('rateLimit(2)', source, 'istek hızı sınırlanmalı')

    def test_router_is_cached_between_searches(self):
        """Her aramada 24 JS parçası indirmek WAF'ı üzen toplu trafik üretiyordu."""
        source = SOURCE.read_text(encoding='utf-8')
        frontend = FRONTEND.read_text(encoding='utf-8')
        self.assertIn('private var seriesRouter: String?', source)
        self.assertIn('seriesRouter = it', source)
        self.assertIn('urls.length < 6', frontend)
        self.assertIn('if (options.router)', frontend)

    def test_sitemap_tier_uses_short_budget(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('callTimeout(3, TimeUnit.SECONDS)', source)
        self.assertIn('SITEMAP_RETRY_MS', source)

    def test_requests_look_like_a_mobile_browser(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.assertIn('override fun Headers.Builder.configureHeaders()', source)
        self.assertIn('Sec-Ch-Ua-Mobile', source)
        self.assertIn('Accept-Language', source)

    def test_reader_tolerates_cdn_hosts(self):
        script = FRONTEND.read_text(encoding='utf-8')
        reader = script.split('fun readerScript(', 1)[1]
        self.assertNotIn("url.host !== location.host", reader, 'bölüm görselleri CDN\'de olabilir')
        self.assertIn("lower.indexOf('/banner')", reader)

    def test_version_is_bumped_for_the_fix(self):
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn('versionCode = 16', gradle)
        self.assertIn('baseUrl = "https://sadscans.net"', gradle)


if __name__ == '__main__':
    unittest.main()
