"""Optional offline checks for the user's private HTML snapshots.

Set MANGATR_SNAPSHOT_DIR to a directory containing message (15).txt through
message (19).txt. Do not commit the snapshots: they may contain session data.
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


class InfoRows(HTMLParser):
    """Extract detail labels and values without storing private HTML fixtures."""
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.rows = []
        self.row = None
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        classes = dict(attrs).get('class', '').split()
        self.stack.append((tag, classes))
        if 'bento-info-row' in classes:
            self.row = {'label': [], 'value': []}

    def handle_data(self, data):
        if self.row is None:
            return
        classes = {c for _, group in self.stack for c in group}
        if 'bento-info-label' in classes:
            self.row['label'].append(data)
        if 'bento-info-value' in classes:
            self.row['value'].append(data)

    def handle_endtag(self, tag):
        # HTML contains void tags that do not have matching end tags.
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                removed = self.stack[i:]
                del self.stack[i:]
                if self.row is not None and any('bento-info-row' in c for _, c in removed):
                    self.rows.append((''.join(self.row['label']).strip(), ''.join(self.row['value']).strip()))
                    self.row = None
                break


def snapshot(number):
    return (Path(os.environ['MANGATR_SNAPSHOT_DIR']) / f'message ({number}).txt').read_text(encoding='utf-8')


def decrypt(value, key):
    raw = base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
    key = key.encode()
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(raw))


@unittest.skipUnless(os.environ.get('MANGATR_SNAPSHOT_DIR'), 'private HTML snapshots not provided')
class MangaTrSnapshots(unittest.TestCase):
    def test_home_card_elements_are_articles(self):
        document = Snapshot(snapshot(15))
        cards = [(tag, a) for tag, a in document.tags if 'media-card' in a.get('class', '').split()]
        self.assertGreater(len(cards), 0)
        self.assertTrue(all(tag == 'article' for tag, _ in cards))
        self.assertTrue(any(a.get('class') == 'media-card__title' for _, a in document.tags))

    def test_details_have_expected_structure_but_no_chapter_key(self):
        for number in (16, 19):
            text = snapshot(number)
            page = Snapshot(text)
            classes = {c for _, a in page.tags for c in a.get('class', '').split()}
            self.assertIn('bento-hero-title', classes)
            self.assertIn('poster-card__image', classes)
            self.assertTrue(any(a.get('id') == 'manga-desc-content' for _, a in page.tags))
            self.assertIsNone(re.search(r"listKey:\s*'([^']+)'", text))
            self.assertTrue(any(a.get('id') == 'results' and a.get('data-manga') for _, a in page.tags))

    def test_detail_metadata_values_in_both_snapshots(self):
        expected = {
            16: ('Tamamlandı', 'Maripaz Villar'),
            19: ('Devam Ediyor', 'Tatsuki Fujimoto'),
        }
        for number, (status, author) in expected.items():
            with self.subTest(snapshot=number):
                rows = dict(InfoRows(snapshot(number)).rows)
                self.assertEqual(rows.get('Durum'), status)
                self.assertEqual(rows.get('Yazar(lar)'), author)
                self.assertEqual(rows.get('Sanatçı(lar)'), author)

    def test_reader_captures(self):
        for number in (17, 18):
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
            self.assertEqual(list(images.values()), [17])
            self.assertTrue(any(sorted(v) == list(range(17)) for v in indices.values()))
            groups = [a for tag, a in page.tags if tag == 'a' and 'fansub-group-item' in a.get('class', '').split()]
            self.assertEqual(len(groups), 2)
            self.assertEqual(len({urlsplit(a['href']).path for a in groups}), 2)


if __name__ == '__main__':
    unittest.main()
