import hashlib
import json
import unittest
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'eu.kanade.tachiyomi.extension.tr.mangatr'


class CatalogTest(unittest.TestCase):
    def test_only_manga_tr_module_is_enabled(self):
        settings = (ROOT / 'keiyoushi-extensions-tr/settings.gradle.kts').read_text(encoding='utf-8')
        module_section = settings.split('/**\n * ===================================== COMMON CONFIGURATION', 1)[0]
        self.assertIn('loadIndividualExtension("tr", "mangatr")', module_section)
        self.assertNotIn('\nloadAllIndividualExtensions()', module_section)

    def test_catalogs_expose_only_manga_tr(self):
        catalogs = [json.loads((ROOT / name).read_text(encoding='utf-8')) for name in (
            'index.json', 'plugins.json', 'builds/index.json', 'builds/plugins.json',
        )]
        for catalog in catalogs:
            self.assertEqual(len(catalog), 1)
            self.assertEqual(catalog[0]['pkg'], PACKAGE)
            self.assertEqual(catalog, catalogs[0])
        entry = catalogs[0][0]
        v2 = json.loads((ROOT / 'builds/index.v2.json').read_text(encoding='utf-8'))['extensionList']['extensions']
        self.assertEqual(len(v2), 1)
        self.assertEqual(v2[0]['packageName'], PACKAGE)
        self.assertEqual(v2[0]['versionName'], entry['version'])
        self.assertEqual(int(v2[0]['versionCode']), entry['code'])
        self.assertEqual(v2[0]['resources']['apkUrl'], entry['url'])
        self.assertEqual(v2[0]['resources']['iconUrl'], entry['iconUrl'])
        self.assertEqual(Path(urlparse(entry['url']).path).name, entry['apk'])
        for directory in ('apk', 'builds/apk'):
            artifacts = {p.name for p in (ROOT / directory).iterdir() if p.is_file()}
            self.assertEqual(artifacts, {entry['apk'], PACKAGE + '.png'})
        self.assertEqual(
            hashlib.sha256((ROOT / 'apk' / entry['apk']).read_bytes()).digest(),
            hashlib.sha256((ROOT / 'builds/apk' / entry['apk']).read_bytes()).digest(),
        )


if __name__ == '__main__':
    unittest.main()
