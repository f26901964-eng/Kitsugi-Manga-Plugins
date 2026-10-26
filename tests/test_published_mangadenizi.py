"""Check the actual delivered binary/index, not a simulated extension build."""
import hashlib
import json
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class PublishedMangaDeniziTests(unittest.TestCase):
    def test_one_native_turkish_entry_has_the_real_delivered_binary(self):
        index = json.loads((ROOT / 'index.json').read_text())
        entries = index['extensionList']['extensions']
        self.assertEqual(len(entries), 1)
        entry = entries[0]
        self.assertEqual(entry['packageName'], 'com.kitsugi.extension.tr.mangadenizi')
        self.assertEqual([s['language'] for s in entry['sources']], ['tr'])
        self.assertTrue(entry['resources']['apkUrl'].endswith('/com_kitsugi_extension_tr_mangadenizi.mex'))
        binary = ROOT / 'packages/mangadenizi.mex'
        self.assertEqual(hashlib.sha256(binary.read_bytes()).hexdigest(), entry['resources']['sha256'])
        with zipfile.ZipFile(binary) as archive:
            self.assertEqual(set(archive.namelist()), {'classes.dex', 'manifest.json', 'LICENSE', 'NOTICE'})
            self.assertEqual(json.loads(archive.read('manifest.json')), json.loads((ROOT / 'extensions/mangadenizi/manifest.json').read_text()))

    def test_android14_is_not_misrepresented_as_an_unmodified_phone_update(self):
        meta = json.loads((ROOT / 'docs/mangadenizi-installation.json').read_text())
        self.assertFalse(meta['compatibility']['signedAndroid14AppUpdateIncluded'])
        self.assertEqual(meta['compatibility']['requiresAppLoaderFixFromApi'], 34)
        report = json.loads((ROOT / 'docs/audits/mangadenizi-android-34.json').read_text())
        self.assertEqual(report['hostVariant'], 'release-with-readonly-loader-test-fixture')
        self.assertTrue(report['inputMexWritableBeforeLoad'])
        self.assertTrue(report['mexReadOnlyAfterLoader'])
        self.assertTrue(report['singleTurkishSourceProfileVerified'])

    def test_both_native_pixel_reports_equal_the_real_public_reader_reference(self):
        browser = json.loads((ROOT / 'docs/audits/mangadenizi-2026-10-26.json').read_text())
        reference = {(image['slug'], image['index']): image for image in browser['images']}
        self.assertEqual(len(reference), 53)
        for api in [29, 34]:
            report = json.loads((ROOT / f'docs/audits/mangadenizi-android-{api}.json').read_text())
            self.assertEqual(report['status'], 'passed')
            self.assertEqual(len(report['images']), 53)
            for image in report['images']:
                expected = reference[image['slug'], image['index']]
                self.assertEqual((image['width'], image['height'], image['pixelSha256']), (expected['width'], expected['height'], expected['pixelSha256']))
                self.assertTrue(image['matchesPublicReaderReference'])


if __name__ == '__main__': unittest.main()
