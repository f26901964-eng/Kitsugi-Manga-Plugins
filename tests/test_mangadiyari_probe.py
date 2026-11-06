import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'keiyoushi-extensions-tr/src/tr/mangadiyari/src/eu/kanade/tachiyomi/extension/tr/mangadiyari'
FILTERS = MODULE / 'Filters.kt'
URLS = MODULE / 'Urls.kt'
PROBE = ROOT / 'tools/mangadiyari_probe.py'


def probe_genre_groups():
    """Sondanın tür tablosu; `requests` kurulu olmayan ortamda da okunabilsin diye kaynaktan ayıklanır."""
    text = PROBE.read_text(encoding='utf-8')
    block = text.split('GENRE_GROUPS:', 1)[1].split('\n]', 1)[0]
    return {name: value for name, value in re.findall(r'^\s*\("([^"]+)", "([^"]+)"', block, re.M)}


def kotlin_genre_values():
    """`GenreCheckBox("Aksiyon", "Aksiyon")` -> gönderilen etiket."""
    text = FILTERS.read_text(encoding='utf-8')
    body = text.split('class GenreFilter', 1)[1]
    return re.findall(r'GenreCheckBox\("([^"]+)", "([^"]+)"\)', body)


@unittest.skipUnless(
    PROBE.is_file() and FILTERS.is_file(),
    "Manga Diyarı devre dışı: sonda/kaynak dosyaları yok",
)
class ProbeContractTest(unittest.TestCase):
    """Sonda eklentinin uçlarını ve listelerini kopyalar; ikisi sessizce ayrışmamalı."""

    def test_probe_genres_match_the_filter(self):
        """Sondanın doğruladığı etiket, eklentinin gönderdiği etiketle birebir aynı olmalı."""
        probe = probe_genre_groups()
        self.assertEqual(35, len(probe))
        self.assertEqual(probe, {name: value for name, value in kotlin_genre_values()})

    def test_genre_values_are_single_labels(self):
        """Virgül = AND olduğu için gruplar tek etiket göndermeli; aksi hâlde sonuç boş döner."""
        values = [value for _, value in kotlin_genre_values()]
        self.assertEqual(len(values), len(set(values)))
        for value in values:
            self.assertNotIn(',', value)

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
