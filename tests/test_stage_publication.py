import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('stage_publication', ROOT / 'tools/stage_publication.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StagePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / 'source'
        self.dest = Path(self.temp.name) / 'published'
        for root in (self.source, self.dest):
            for file in module.FILES:
                path = root / file
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(root.name, encoding='utf-8')
            for directory in module.DIRECTORIES:
                (root / directory).mkdir(parents=True, exist_ok=True)
        (self.source / 'apk/new.apk').write_bytes(b'new')
        (self.source / 'builds/apk/new.apk').write_bytes(b'new')
        (self.dest / 'apk/old.apk').write_bytes(b'old')
        (self.dest / 'builds/apk/old.apk').write_bytes(b'old')
        (self.dest / 'keep.txt').write_text('untouched')

    def test_copies_selected_files_and_removes_disabled_apk(self):
        module.stage(self.source, self.dest)
        self.assertEqual((self.dest / 'index.json').read_text(), 'source')
        self.assertEqual((self.dest / 'apk/new.apk').read_bytes(), b'new')
        self.assertFalse((self.dest / 'apk/old.apk').exists())
        self.assertFalse((self.dest / 'builds/apk/old.apk').exists())
        self.assertEqual((self.dest / 'keep.txt').read_text(), 'untouched')

    def test_invalid_destination_rejected_before_mutation(self):
        (self.dest / 'apk/keep.txt').write_text('do not delete')
        with self.assertRaises(ValueError):
            module.stage(self.source, self.dest)
        self.assertEqual((self.dest / 'index.json').read_text(), 'published')
        self.assertTrue((self.dest / 'apk/old.apk').exists())

    def test_missing_empty_publication_directories_are_created(self):
        shutil.rmtree(self.dest / 'apk')
        shutil.rmtree(self.dest / 'builds/apk')
        module.stage(self.source, self.dest)
        self.assertTrue((self.dest / 'apk/new.apk').is_file())
        self.assertTrue((self.dest / 'builds/apk/new.apk').is_file())

    def test_same_checkout_rejected(self):
        with self.assertRaises(ValueError):
            module.stage(self.source, self.source)


if __name__ == '__main__':
    unittest.main()
