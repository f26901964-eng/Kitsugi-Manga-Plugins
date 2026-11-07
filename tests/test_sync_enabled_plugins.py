import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('sync_plugins', ROOT / 'tools/sync_enabled_plugins.py')
sync_plugins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync_plugins)


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        shutil.copytree(ROOT / 'registry', self.root / 'registry')
        (self.root / 'prebuilt/apk').mkdir(parents=True)
        self.entries = json.loads((ROOT / 'registry/extensions.json').read_text(encoding='utf-8'))
        self.chosen = [next(x for x in self.entries if x['pkg'].endswith('.' + name))
                       for name in ('mangatr', 'afroditscans')]
        for entry in self.chosen:
            lang, name = entry['pkg'].split('.')[-2:]
            module = self.root / 'keiyoushi-extensions-tr/src' / lang / name
            module.mkdir(parents=True)
            (module / 'build.gradle.kts').touch()
            for file in (entry['apk'], entry['pkg'] + '.png'):
                shutil.copyfile(ROOT / 'prebuilt/apk' / file, self.root / 'prebuilt/apk' / file)

    def activate(self, text):
        (self.root / 'enabled_plugins.txt').write_text(text, encoding='utf-8')
        return sync_plugins.sync(self.root)

    def test_enable_disable_and_reenable_preserves_archive(self):
        self.activate('tr/mangatr\ntr/afroditscans\n')
        for catalog in ('index.json', 'plugins.json', 'builds/index.json', 'builds/plugins.json'):
            self.assertEqual(len(json.loads((self.root / catalog).read_text())), 2)
        v2 = json.loads((self.root / 'builds/index.v2.json').read_text())
        self.assertEqual(len(v2['extensionList']['extensions']), 2)
        self.activate('tr/mangatr\n')
        removed = self.chosen[1]
        self.assertFalse((self.root / 'apk' / removed['apk']).exists())
        self.assertTrue((self.root / 'prebuilt/apk' / removed['apk']).exists())
        self.activate('tr/mangatr\ntr/afroditscans\n')
        self.assertTrue((self.root / 'builds/apk' / removed['apk']).is_file())
        self.assertEqual(len(json.loads((self.root / 'index.json').read_text())), 2)

    def test_v1_catalog_survives_kitsugi_decoy_check_after_trim(self):
        # Kitsugi checks trimmed String.length (UTF-16 units) before parsing V1.
        # Test several list sizes, including empty, to guard against regressions.
        for modules, count in (('', 0), ('tr/mangatr\n', 1), ('tr/mangatr\ntr/afroditscans\n', 2)):
            with self.subTest(modules=modules):
                self.activate(modules)
                for path in ('index.json', 'plugins.json', 'builds/index.json', 'builds/plugins.json'):
                    payload = (self.root / path).read_text(encoding='utf-8').strip()
                    self.assertGreaterEqual(len(payload.encode('utf-16-le')) // 2, 5000)
                    self.assertEqual(len(json.loads(payload)), count)
                    self.assertFalse(any(marker.lower() in payload.lower() for marker in (
                        'Outdated App', 'Update to Mihon', 'Update to Tachiyomi',
                    )))

    def test_invalid_list_does_not_change_published_catalog(self):
        self.activate('tr/mangatr\n')
        before = (self.root / 'index.json').read_bytes()
        for text in ('tr/missing\n', '../bad\n', 'tr/mangatr\ntr/mangatr\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.activate(text)
            self.assertEqual((self.root / 'index.json').read_bytes(), before)

    def test_empty_allowlist_disables_all(self):
        self.activate('tr/mangatr\n')
        self.activate('# etkin eklenti yok\n')
        self.assertEqual(json.loads((self.root / 'index.json').read_text()), [])
        self.assertEqual(list((self.root / 'apk').iterdir()), [])
        self.assertTrue((self.root / 'prebuilt/apk' / self.chosen[0]['apk']).exists())


if __name__ == '__main__':
    unittest.main()
