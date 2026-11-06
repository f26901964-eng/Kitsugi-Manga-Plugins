import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'keiyoushi-extensions-tr/src/tr/mangadiyari/src/eu/kanade/tachiyomi/extension/tr/mangadiyari'
FILTERS = MODULE / 'Filters.kt'
URLS = MODULE / 'Urls.kt'
PROBE = ROOT / 'tools/mangadiyari_probe.py'


def probe_genre_values():
    """Sondanın tür listesi; `requests` kurulu olmayan ortamda da okunabilsin diye kaynaktan ayıklanır."""
    text = PROBE.read_text(encoding='utf-8')
    block = text.split('GENRE_FILTER_VALUES = [', 1)[1].split(']', 1)[0]
    return re.findall(r'"([^"]+)"', block)


def kotlin_genre_values():
    text = FILTERS.read_text(encoding='utf-8')
    body = text.split('class GenreFilter', 1)[1]
    return [value for _, value in re.findall(r'"([^"]+)" to "([^"]+)"', body)]


class ProbeContractTest(unittest.TestCase):
    """Sonda eklentinin uçlarını ve listelerini kopyalar; ikisi sessizce ayrışmamalı."""

    def test_probe_genres_match_the_filter(self):
        self.assertEqual(probe_genre_values(), kotlin_genre_values())

    def test_probe_hits_the_same_endpoints_as_the_source(self):
        probe = PROBE.read_text(encoding='utf-8')
        urls = URLS.read_text(encoding='utf-8')
        for endpoint in ('/api/series', '/api/series/latest-updates', '/api/series/', '/api/chapters/'):
            self.assertIn(endpoint, probe)
            self.assertIn(endpoint, urls)

    def test_probe_page_size_matches_the_source(self):
        probe = PROBE.read_text(encoding='utf-8')
        size = re.search(r'PAGE_SIZE = (\d+)', probe).group(1)
        source = re.search(r'MANGADIYARI_PAGE_SIZE = (\d+)', URLS.read_text(encoding='utf-8')).group(1)
        self.assertEqual(size, source)


if __name__ == '__main__':
    unittest.main()
