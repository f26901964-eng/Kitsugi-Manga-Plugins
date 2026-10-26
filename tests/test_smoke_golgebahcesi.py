import copy
import importlib.util
import json
from pathlib import Path
import unittest
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("smoke", ROOT / "scripts/smoke_golgebahcesi.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
FIXTURES = json.loads((ROOT / "integration/kitsugi/src/test/resources/golgebahcesi/fixtures.json").read_text())


class SmokeTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = copy.deepcopy(FIXTURES)
        self.content = self.fixtures["plain"]
        self.images = []
        self.calls = []

    def get_json(self, url):
        self.calls.append(url)
        path = urlparse(url).path
        if path == "/api/series":
            return self.fixtures["list"]
        if path == "/api/series/test-serisi":
            return self.fixtures["details"]
        if path == "/api/series/test-serisi/chapters":
            return self.fixtures["chapters"]
        if path == "/api/chapters/chapter-1":
            return self.content
        raise AssertionError(url)

    def get_image(self, url, referer):
        self.images.append((url, referer))
        return b"\x89PNG\r\n\x1a\nsynthetic-image-signature"

    def check(self, **kwargs):
        return smoke.check(chapter_id="chapter-1", get_json=self.get_json, get_image=self.get_image, **kwargs)

    def test_plain_chain_samples_first_middle_last_without_claiming_android_validation(self):
        result = self.check()
        self.assertEqual("network_contract_ok", result["status"])
        self.assertFalse(result["android_verified"])
        self.assertEqual([0, 1, 2], [item["position"] for item in result["samples"]])
        self.assertEqual("https://golgebahcesi.com/pages/0.webp", self.images[0][0])
        self.assertTrue(all(referer.endswith("/manga/test-serisi/bolum/bolum-1") for _, referer in self.images))

    def test_secure_chapter_is_pending_not_a_false_success(self):
        self.content = self.fixtures["secure"]
        with self.assertRaises(smoke.NeedsDevice):
            self.check()
        self.assertEqual([], self.images)

    def test_image_enc_flag_without_secure_label_is_not_a_false_plain_success(self):
        self.content["imageEnc"] = True
        with self.assertRaises(smoke.NeedsDevice):
            self.check()
        self.assertEqual([], self.images)

    def test_enc_path_without_either_flag_is_not_a_false_plain_success(self):
        self.content["pages"][0]["url"] = "/series/test/2.jpg.enc?signature=public"
        with self.assertRaises(smoke.NeedsDevice):
            self.check()
        self.assertEqual([], self.images)

    def test_locked_chapter_never_fetches_or_decrypts_images(self):
        self.content = self.fixtures["locked"]
        with self.assertRaises(smoke.NeedsDevice):
            self.check()
        self.assertEqual([], self.images)

    def test_missing_search_manga_is_a_failure(self):
        def no_search(url):
            if "search=" in url:
                return {"data": []}
            return self.get_json(url)
        with self.assertRaisesRegex(ValueError, "Search"):
            smoke.check(chapter_id="chapter-1", get_json=no_search, get_image=self.get_image)

    def test_html_or_encrypted_bytes_are_not_accepted_as_images(self):
        for data in (b"<html>challenge</html>", b"encrypted-not-an-image", b""):
            with self.assertRaises(ValueError):
                smoke.check(chapter_id="chapter-1", get_json=self.get_json, get_image=lambda *_: data)

    def test_wrong_chapter_identity_fails_before_image_download(self):
        self.content["id"] = "wrong-id"
        with self.assertRaisesRegex(ValueError, "identity"):
            self.check()
        self.assertEqual([], self.images)

    def test_duplicate_page_indices_fail_before_image_download(self):
        self.content["pages"][0]["index"] = 0
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.check()
        self.assertEqual([], self.images)

    def test_unsafe_image_scheme_is_rejected(self):
        self.content["pages"][1]["url"] = "file:///etc/passwd"
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            self.check()
        self.assertEqual([], self.images)


if __name__ == "__main__":
    unittest.main()
