import hashlib
import json
import unittest
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'eu.kanade.tachiyomi.extension.'


def enabled_packages():
    return [PREFIX + line.strip().replace('/', '.') for line in
            (ROOT / 'enabled_plugins.txt').read_text(encoding='utf-8').splitlines()
            if line.strip() and not line.strip().startswith('#')]


class CatalogTest(unittest.TestCase):
    def test_gradle_uses_allowlist(self):
        settings = (ROOT / 'keiyoushi-extensions-tr/settings.gradle.kts').read_text(encoding='utf-8')
        module_section = settings.split('/**\n * ===================================== COMMON CONFIGURATION', 1)[0]
        self.assertIn('enabled_plugins.txt', module_section)
        self.assertIn('loadIndividualExtension(lang, name)', module_section)
        self.assertNotIn('\nloadAllIndividualExtensions()', module_section)

    def test_catalogs_match_allowlist_and_artifacts(self):
        catalogs = [json.loads((ROOT / name).read_text(encoding='utf-8')) for name in (
            'index.json', 'plugins.json', 'builds/index.json', 'builds/plugins.json',
        )]
        for catalog in catalogs:
            self.assertEqual([e['pkg'] for e in catalog], enabled_packages())
            self.assertEqual(catalog, catalogs[0])
        v2 = json.loads((ROOT / 'builds/index.v2.json').read_text(encoding='utf-8'))['extensionList']['extensions']
        self.assertEqual([e['packageName'] for e in v2], enabled_packages())
        artifacts = set()
        for entry, v2_entry in zip(catalogs[0], v2):
            self.assertEqual(v2_entry['versionName'], entry['version'])
            self.assertEqual(int(v2_entry['versionCode']), entry['code'])
            self.assertEqual(v2_entry['resources']['apkUrl'], entry['url'])
            self.assertEqual(v2_entry['resources']['iconUrl'], entry['iconUrl'])
            self.assertEqual(Path(urlparse(entry['url']).path).name, entry['apk'])
            artifacts.update((entry['apk'], entry['pkg'] + '.png'))
            self.assertEqual(
                hashlib.sha256((ROOT / 'apk' / entry['apk']).read_bytes()).digest(),
                hashlib.sha256((ROOT / 'builds/apk' / entry['apk']).read_bytes()).digest(),
            )
        for directory in ('apk', 'builds/apk'):
            self.assertEqual({p.name for p in (ROOT / directory).iterdir() if p.is_file()}, artifacts)


if __name__ == '__main__':
    unittest.main()
