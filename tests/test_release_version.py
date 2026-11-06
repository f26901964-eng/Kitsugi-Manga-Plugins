import unittest
from pathlib import Path

from tools.release_version import module_version, release_version, theme_base_version_code

ROOT = Path(__file__).resolve().parents[1]


class ReleaseVersionTests(unittest.TestCase):
    def test_theme_base_is_added_to_the_source_version(self):
        # Hayalistic: madara baseVersionCode 55 + source 7 -> the APK reads 1.6.62/106062.
        gradle = '''
        keiyoushi {
            versionCode = 7
            libVersion = "1.6"
            theme = "madara"
        }
        '''
        self.assertEqual(release_version(gradle, 55), ("1.6.62", 106062))

    def test_module_without_a_theme_keeps_the_source_version(self):
        gradle = '''
        keiyoushi {
            versionCode = 8
            libVersion = "1.6"
        }
        '''
        self.assertEqual(release_version(gradle), ("1.6.8", 106008))

    def test_missing_version_is_rejected(self):
        with self.assertRaises(ValueError):
            release_version('libVersion = "1.6"')

    def test_unknown_theme_is_rejected(self):
        with self.assertRaises(ValueError):
            theme_base_version_code("bu-tema-yok", ROOT)

    def test_enabled_plugin_catalog_entries_match_their_apk_version(self):
        import json

        registry = {
            entry["pkg"].split(".")[-1]: (entry["version"], entry["code"])
            for entry in json.loads((ROOT / "registry/extensions.json").read_text(encoding="utf-8"))
        }
        enabled = [
            line.strip() for line in (ROOT / "enabled_plugins.txt").read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        for module in enabled:
            expected = module_version(module, ROOT)
            self.assertEqual(registry[module.split("/")[1]], expected, module)


if __name__ == "__main__":
    unittest.main()
