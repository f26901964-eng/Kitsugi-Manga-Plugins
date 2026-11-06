import unittest

from tools.prepare_mangadiyari_release import release_version


class MangaDiyariReleaseTests(unittest.TestCase):
    def test_gradle_version_is_mapped_to_the_catalog(self):
        gradle = '''
        keiyoushi {
            // 2: chapter and reader fixes
            versionCode = 2
            libVersion = "1.6"
        }
        '''
        self.assertEqual(release_version(gradle), ("1.6.2", 2, 106002))

    def test_only_the_current_apk_stays_in_the_archive(self):
        """Birden fazla sürüm arşivde kalırsa katalog testi ve imza karışıklığı çıkar."""
        text = open("tools/prepare_mangadiyari_release.py", encoding="utf-8").read()
        self.assertIn("prebuilt/apk", text)
        self.assertIn("stale.unlink()", text)

    def test_missing_version_is_rejected(self):
        with self.assertRaises(ValueError):
            release_version('libVersion = "1.6"')


if __name__ == "__main__":
    unittest.main()
