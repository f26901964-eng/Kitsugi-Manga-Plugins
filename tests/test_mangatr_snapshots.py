"""Optional offline checks for the user's private HTML snapshots.

Set MANGATR_SNAPSHOT_DIR to a directory containing message (50).txt through
message (54).txt. Do not commit the snapshots: they may contain session data.
These tests validate captured HTML, not the Android/Kotlin runtime.
"""
import base64
import json
import os
import re
import unittest
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlsplit


class Snapshot(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.tags = []
        self.scripts = []
        self._script = None
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.tags.append((tag, attrs))
        if tag == 'script':
            self._script = [attrs, '']

    def handle_data(self, data):
        if self._script is not None:
            self._script[1] += data

    def handle_endtag(self, tag):
        if tag == 'script' and self._script is not None:
            self.scripts.append(self._script)
            self._script = None


def snapshot(number):
    return (Path(os.environ['MANGATR_SNAPSHOT_DIR']) / f'message ({number}).txt').read_text(encoding='utf-8')


def decrypt(value, key):
    raw = base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
    key = key.encode()
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(raw))


@unittest.skipUnless(os.environ.get('MANGATR_SNAPSHOT_DIR'), 'private HTML snapshots not provided')
class MangaTrSnapshots(unittest.TestCase):
    def test_home_card_elements_are_articles(self):
        document = Snapshot(snapshot(50))
        cards = [(tag, a) for tag, a in document.tags if 'media-card' in a.get('class', '').split()]
        self.assertEqual(len(cards), 18)
        self.assertTrue(all(tag == 'article' for tag, _ in cards))
        self.assertTrue(any('media-card__title' in a.get('class', '').split() for _, a in document.tags))
        self.assertTrue(any('index-sayfa-2.html' in a.get('href', '') for _, a in document.tags))

    def test_search_cards(self):
        document = Snapshot(snapshot(51))
        cards = [a for _, a in document.tags if 'arama-card' in a.get('class', '').split()]
        self.assertEqual(len(cards), 2)
        self.assertEqual(sum('arama-card__title' in a.get('class', '').split() for _, a in document.tags), 2)
        self.assertEqual(sum('arama-card__cover' in a.get('class', '').split() for _, a in document.tags), 2)

    def test_details_have_expected_structure_but_no_static_chapters(self):
        text = snapshot(52)
        page = Snapshot(text)
        classes = {c for _, a in page.tags for c in a.get('class', '').split()}
        self.assertIn('bento-hero-title', classes)
        self.assertIn('poster-card__image', classes)
        self.assertTrue(any(a.get('id') == 'manga-desc-content' for _, a in page.tags))
        self.assertIsNone(re.search(r"listKey:\s*'([^']+)'", text))
        self.assertTrue(any(
            a.get('id') == 'results' and a.get('data-manga') == 'chainsaw-man'
            for _, a in page.tags
        ))
        self.assertFalse(any('bento-ep-card' in a.get('class', '').split() for _, a in page.tags))

    def test_reader_captures(self):
        for number in (53, 54):
            text = snapshot(number)
            page = Snapshot(text)
            scripts = [(a, s) for a, s in page.scripts if a.get('id', '').startswith('rdm-')]
            self.assertEqual(len(scripts), 1)
            attrs, config = scripts[0]
            self.assertTrue(config.strip())
            key = next(v for v in attrs.values() if v and re.fullmatch(r'[0-9a-f]{32}', v))
            gate = re.search(r'_fpx\s*=\s*"([^"]+)"', text)
            self.assertIsNotNone(gate)
            self.assertTrue(gate.group(1).startswith('/cek/'))
            self.assertEqual(urlsplit(urljoin('https://manga-tr.com', gate.group(1))).path[:5], '/cek/')
            images = Counter()
            indices = {}
            for _, a in page.tags:
                for name, value in a.items():
                    if not name.startswith('data-') or not value or len(value) < 100:
                        continue
                    try:
                        decoded = json.loads(decrypt(value, f'attr|{key}|reader'))
                    except (ValueError, UnicodeError):
                        continue
                    if isinstance(decoded, list) and decoded and isinstance(decoded[0], str) and decoded[0].startswith('https://'):
                        images[name] += 1
                        for index_name, index_value in a.items():
                            if index_name.startswith('data-') and index_value and index_value.isdigit():
                                indices.setdefault(index_name, []).append(int(index_value))
            self.assertEqual(list(images.values()), [25])
            self.assertTrue(any(sorted(v) == list(range(25)) for v in indices.values()))
            groups = [a for tag, a in page.tags if tag == 'a' and 'fansub-group-item' in a.get('class', '').split()]
            self.assertEqual(len(groups), 4)
            self.assertEqual(len({urlsplit(a['href']).path for a in groups}), 4)
            current = [a for a in groups if 'is-current' in a.get('class', '').split()]
            self.assertEqual(len(current), 1)
            self.assertIn('id-123931-read-chainsaw-man-chapter-2.html', current[0]['href'])


if __name__ == '__main__':
    unittest.main()
