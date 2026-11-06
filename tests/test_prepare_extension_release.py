import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tools.prepare_extension_release import release

ROOT = Path(__file__).resolve().parents[1]
MODULE = "hayalistic"
PACKAGE = "eu.kanade.tachiyomi.extension.tr.hayalistic"
APK_NAME = "tachiyomi-tr.hayalistic-v1.6.62.apk"


class PrepareExtensionReleaseTests(unittest.TestCase):
    """The release step must describe the APK that is published, never the raw Gradle number."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)
        for path in ("registry", "prebuilt/apk", "apk", "builds", "index.json", "plugins.json",
                     "enabled_plugins.txt"):
            source, target = ROOT / path, self.dir / path
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copyfile(source, target)
        module = self.dir / "keiyoushi-extensions-tr/src/tr" / MODULE
        (module / "build/outputs/apk/release").mkdir(parents=True)
        # A minimal but real zip: the release script checks the package layout.
        apk = module / "build/outputs/apk/release/out.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
            archive.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 12_000)
        shutil.copyfile(ROOT / f"keiyoushi-extensions-tr/src/tr/{MODULE}/build.gradle.kts",
                        module / "build.gradle.kts")
        theme = self.dir / "keiyoushi-extensions-tr/lib-multisrc/madara"
        theme.mkdir(parents=True)
        shutil.copyfile(ROOT / "keiyoushi-extensions-tr/lib-multisrc/madara/build.gradle.kts",
                        theme / "build.gradle.kts")
        # Only this extension is enabled in the fixture, so the catalogs stay small.
        (self.dir / "enabled_plugins.txt").write_text(f"tr/{MODULE}\n", encoding="utf-8")

    def manifest(self, version_name="1.6.62", version_code=106062):
        return mock.patch(
            "tools.prepare_extension_release.read_android_manifest",
            return_value={"versionName": version_name, "versionCode": version_code},
        )

    def entry(self):
        registry = json.loads((self.dir / "registry/extensions.json").read_text())
        return next(item for item in registry if item["pkg"] == PACKAGE)

    def test_catalog_entry_matches_the_built_apk(self):
        with self.manifest():
            version_name, code = release(MODULE, self.dir)
        entry = self.entry()
        self.assertEqual((version_name, code), ("1.6.62", 106062))
        self.assertEqual((entry["version"], entry["code"]), ("1.6.62", 106062))
        self.assertEqual(entry["apk"], APK_NAME)
        self.assertTrue(entry["url"].endswith(APK_NAME))
        self.assertTrue((self.dir / "prebuilt/apk" / APK_NAME).is_file())
        self.assertEqual([p.name for p in (self.dir / "prebuilt/apk").iterdir()
                          if p.name.startswith("tachiyomi-tr.hayalistic-v")], [APK_NAME])

    def test_apk_that_disagrees_with_gradle_is_refused(self):
        with self.manifest(version_name="1.6.7", version_code=106007):
            with self.assertRaisesRegex(ValueError, "uyuşmuyor"):
                release(MODULE, self.dir)

    def test_a_catalog_code_that_goes_backwards_is_refused(self):
        registry_path = self.dir / "registry/extensions.json"
        registry = json.loads(registry_path.read_text())
        for item in registry:
            if item["pkg"] == PACKAGE:
                item["code"] = 999999
                item["version"] = "1.7.0"
        registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2))
        with self.manifest():
            with self.assertRaisesRegex(ValueError, "büyük olmalı"):
                release(MODULE, self.dir)


if __name__ == "__main__":
    unittest.main()
