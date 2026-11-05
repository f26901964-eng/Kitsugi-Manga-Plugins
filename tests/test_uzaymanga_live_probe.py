import unittest
from datetime import datetime, timezone

try:
    from tools.uzaymanga_live_probe import Candidate, SvelteData, chapters, get_svelte, image_url
except ModuleNotFoundError:
    Candidate = SvelteData = chapters = get_svelte = image_url = None


@unittest.skipIf(SvelteData is None, 'live probe dependencies are not installed')
class UzayMangaLiveProbeTests(unittest.TestCase):
    def test_devalue_series_and_chapter_order(self):
        array = [
            {'SeriesEpisode': 1},
            [2, 7],
            {'slug': 3, 'order': 4, 'createdDate': 6},
            'old-chapter', '1.0', 6,
            ['Date', '2026-01-01T00:00:00+00:00'],
            {'slug': 8, 'order': 9, 'createdDate': 11},
            'new-chapter', '2.0', 11,
            ['Date', '2026-11-01T00:00:00+00:00'],
        ]
        data = SvelteData(array)
        parsed = chapters(data, array[0])
        self.assertEqual(['new-chapter', 'old-chapter'], [item['slug'] for item in parsed])
        self.assertEqual(['2', '1'], [item['order'] for item in parsed])
        self.assertEqual(datetime(2026, 11, 1, tzinfo=timezone.utc), parsed[0]['date'])

    def test_series_summary_uses_production_keys(self):
        data = SvelteData([{'name': 1, 'slug': 2}, 'Public title', 'public-slug'])
        self.assertEqual(
            {'name': 'Public title', 'slug': 'public-slug'},
            data.series_summary(data.array[0]),
        )

    def test_svelte_parser_skips_non_data_nodes(self):
        class FakeResponse:
            status_code = 200

            @staticmethod
            def json():
                return {'nodes': [None, 'metadata', {'type': 'data', 'data': [{'root': 1}]}]}

        class FakeSession:
            @staticmethod
            def get(*args, **kwargs):
                return FakeResponse()

        data, root = get_svelte(FakeSession(), 'https://source.example/__data.json')
        self.assertEqual({'root': 1}, root)
        self.assertEqual([{'root': 1}], data.array)

    def test_cdn_resolution_preserves_absolute_urls_and_normalizes_slashes(self):
        candidate = Candidate('test', 'Test', 'https://source.example', 'https://cdn.example/', '1.0')
        self.assertEqual('https://cdn.example/image/page.jpg', image_url(candidate, '/image/page.jpg'))
        self.assertEqual('https://other.example/page.jpg', image_url(candidate, 'https://other.example/page.jpg'))


if __name__ == '__main__':
    unittest.main()
