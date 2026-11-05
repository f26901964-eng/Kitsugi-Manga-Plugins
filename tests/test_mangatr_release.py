import unittest

from tools.prepare_mangatr_release import release_version


class MangaTrReleaseTests(unittest.TestCase):
    def test_gradle_version_is_mapped_to_the_catalog(self):
        gradle = '''
        keiyoushi {
            versionCode = 29
            libVersion = "1.6"
        }
        '''
        self.assertEqual(release_version(gradle), ("1.6.29", 29, 106029))

    def test_missing_version_is_rejected(self):
        with self.assertRaises(ValueError):
            release_version("versionCode = 29")


if __name__ == "__main__":
    unittest.main()
