from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("integration_installer", ROOT / "scripts/apply_kitsugi_integration.py")
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


def patch_preimage(target: Path) -> None:
    """Offline app fixture: exact old hunk contents, unrelated lines left blank.

    This tests patch application, NOT the Android app's compilation. The complete
    pinned upstream checkout is separately checked when preparing an integration.
    """
    current: Path | None = None
    output: dict[Path, list[str]] = {}
    lines = installer.PATCH.read_text(encoding="utf-8").splitlines(keepends=True)
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("--- a/"):
            current = Path(line[len("--- a/"):].strip())
            output[current] = []
        elif line.startswith("@@ "):
            match = re.match(r"@@ -(\d+)(?:,(\d+))? \+", line)
            if match is None or current is None:
                raise AssertionError("Malformed patch")
            start = int(match.group(1)) - 1
            old_count = int(match.group(2) or 1)
            buffer = output[current]
            if len(buffer) > start:
                raise AssertionError("Overlapping hunks")
            buffer.extend(["\n"] * (start - len(buffer)))
            previous = len(buffer)
            index += 1
            while index < len(lines) and not lines[index].startswith(("@@ ", "diff --git ")):
                if lines[index].startswith((" ", "-")):
                    buffer.append(lines[index][1:])
                index += 1
            if len(buffer) - previous != old_count:
                raise AssertionError("Incorrect preimage hunk length")
            continue
        index += 1
    for relative, contents in output.items():
        path = target / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(contents), encoding="utf-8")


def fingerprint(directory: Path) -> dict[str, str]:
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.rglob("*") if path.is_file()}


class SingleSourceProfileTests(unittest.TestCase):
    def test_exactly_one_logical_source_and_no_legacy_parser_enabled(self):
        profile = installer.read_profile()
        self.assertEqual("golgebahcesi", profile["activeSource"])
        self.assertEqual(["tr:golgebahcesi"], profile["keiyoushi.enabled"].split(","))
        self.assertEqual("", profile["kotatsu.enabled"])
        self.assertEqual("native", profile["kitsugi.adapter"])

    def test_legacy_source_files_have_not_been_deleted(self):
        modules = list((ROOT / "keiyoushi-extensions-tr/src/tr").glob("*/build.gradle.kts"))
        self.assertEqual(76, len(modules))
        self.assertTrue((ROOT / "kotatsu-parsers-tr/src/main/kotlin/org/koitharu/kotatsu/parsers/site/mangareader/tr/Golgebahcesi.kt").is_file())

    def test_build_loading_is_allowlisted_instead_of_scanning_all_modules(self):
        settings = (ROOT / "keiyoushi-extensions-tr/settings.gradle.kts").read_text()
        self.assertIn('getProperty("keiyoushi.enabled", "")', settings)
        self.assertNotIn("loadAllIndividualExtensions()", settings)
        self.assertNotIn('eachDir { include(', settings)

    def test_disabled_kotatsu_sources_cannot_be_instantiated_or_link_resolved(self):
        processor = (ROOT / "kotatsu-parsers-tr/kotatsu-parsers-ksp/src/main/kotlin/org/koitharu/kotatsu/parsers/ksp/ParserProcessor.kt").read_text()
        self.assertIn('options["enabledSources"].orEmpty()', processor)
        self.assertLess(processor.index("require(isEnabled)"), processor.index("return when (this)"))
        resolver = (ROOT / "kotatsu-parsers-tr/src/main/kotlin/org/koitharu/kotatsu/parsers/util/LinkResolver.kt").read_text()
        self.assertIn("if (!s.isEnabled) continue", resolver)

    def test_installer_rejects_different_or_duplicate_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sources.properties"
            path.write_text("activeSource=all\n")
            with self.assertRaises(ValueError):
                installer.read_profile(path)
            path.write_text("activeSource=golgebahcesi\nactiveSource=golgebahcesi\n")
            with self.assertRaises(ValueError):
                installer.read_profile(path)

    def test_native_source_does_not_inherit_incompatible_keisource(self):
        source = (ROOT / "integration/kitsugi/src/main/kotlin/com/kitsugi/animelist/data/manga/golgebahcesi/GolgeBahcesiSource.kt").read_text()
        self.assertIn(") : MangaSource", source)
        self.assertNotIn("import keiyoushi.", source)
        self.assertNotIn("import com.squareup.zstd", source)

    def test_reader_bridge_is_origin_scoped_and_cleaned_up(self):
        reader = (ROOT / "integration/kitsugi/src/main/kotlin/com/kitsugi/animelist/data/manga/golgebahcesi/GolgeBahcesiReader.kt").read_text()
        self.assertIn("setOf(ORIGIN)", reader)
        self.assertNotIn('setOf("*")', reader)
        self.assertIn("if (!mainFrame", reader)
        self.assertIn("writerJob.cancelAndJoin()", reader)
        self.assertIn("NonCancellable + Dispatchers.Main.immediate", reader)
        self.assertIn("destroy()", reader)
        self.assertNotIn("override fun onReceivedSslError", reader)


class IntegrationInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.app = Path(self.temporary.name)
        patch_preimage(self.app)

    def tearDown(self):
        self.temporary.cleanup()

    def test_default_preflight_is_read_only(self):
        before = fingerprint(self.app)
        already, count = installer.install(self.app)
        self.assertFalse(already)
        self.assertGreaterEqual(count, 11)
        self.assertEqual(before, fingerprint(self.app))

    def test_apply_copies_the_actual_tested_assets_and_native_adapter(self):
        installer.install(self.app, apply=True)
        for source, relative in installer.copies():
            self.assertEqual(source.read_bytes(), (self.app / relative).read_bytes())
        loader = (self.app / "app/src/main/java/com/kitsugi/animelist/data/manga/MangaExtensionLoader.kt").read_text()
        self.assertIn("return KitsugiMangaSources.getLoadedSources()", loader)
        self.assertIn("external manga APK downloads are paused", loader)
        self.assertIn('implementation("androidx.webkit:webkit:1.12.1")', (self.app / "app/build.gradle.kts").read_text())

    def test_apply_is_idempotent(self):
        installer.install(self.app, apply=True)
        before = fingerprint(self.app)
        already, _ = installer.install(self.app, apply=True)
        self.assertTrue(already)
        self.assertEqual(before, fingerprint(self.app))

    def test_conflicting_destination_is_not_overwritten_or_partially_patched(self):
        _, relative = installer.copies()[0]
        destination = self.app / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text("user changes")
        before = fingerprint(self.app)
        with self.assertRaisesRegex(ValueError, "overwrite"):
            installer.install(self.app, apply=True)
        self.assertEqual(before, fingerprint(self.app))

    def test_incompatible_app_patch_fails_before_any_file_is_copied(self):
        (self.app / "app/build.gradle.kts").write_text("unrelated app build")
        before = fingerprint(self.app)
        with self.assertRaisesRegex(ValueError, "does not apply"):
            installer.install(self.app, apply=True)
        self.assertEqual(before, fingerprint(self.app))

    def test_installed_extension_files_and_reading_progress_are_not_removed(self):
        installed = self.app / "app/files/manga_extensions/old-source.apk"
        installed.parent.mkdir(parents=True)
        installed.write_bytes(b"unchanged-archive")
        progress = self.app / "app/files/progress.db"
        progress.write_bytes(b"reading-progress")
        installer.install(self.app, apply=True)
        self.assertEqual(b"unchanged-archive", installed.read_bytes())
        self.assertEqual(b"reading-progress", progress.read_bytes())

    def test_no_target_path_can_escape_checkout_through_symlink(self):
        with tempfile.TemporaryDirectory() as external:
            source, relative = installer.copies()[0]
            parent = self.app / relative.parent
            parent.mkdir(parents=True, exist_ok=True)
            (parent / relative.name).symlink_to(Path(external) / source.name)
            with self.assertRaisesRegex(ValueError, "escapes"):
                installer.install(self.app, apply=True)


if __name__ == "__main__":
    unittest.main()
