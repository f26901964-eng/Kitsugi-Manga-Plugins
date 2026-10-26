"""An image pass with an explicit chmod is NOT a publishable Android 14 loader pass."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('mangadenizi_release', ROOT / 'scripts/release-mangadenizi.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class MangaDeniziPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.patch = patch.object(release, 'ROOT', self.root)
        self.patch.start(); self.addCleanup(self.patch.stop)
        self.run = {'id': 123, 'html_url': 'https://github.com/test/run/123', 'head_sha': 'a' * 40}

    def evidence(self, api):
        report = {
            'androidApi': api, 'status': 'passed', 'inputMexWritableBeforeLoad': True,
            'installerWritableMexLoaded': True, 'hostTargetSdk': 36, 'testAppTargetSdk': 36,
            'hostVariant': 'original-release' if api == 29 else 'release-with-readonly-loader-test-fixture',
            'singleTurkishSourceProfileVerified': True, 'mexReadOnlyAfterLoader': True,
            'images': [{'slug': slug, 'index': n, 'matchesPublicReaderReference': True}
                       for slug, count in [('black-clover', 20), ('shadow-slave', 33)] for n in range(count)],
        }
        return report

    def save(self, report):
        path = self.root / '.audit/publish/evidence' / str(report['androidApi']) / 'report.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report))

    def test_needs_both_specific_android_versions(self):
        self.save(self.evidence(29))
        with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_accepts_honestly_labeled_native_results(self):
        for api in [29, 34]: self.save(self.evidence(api))
        reports = release.read_evidence(self.run)
        self.assertEqual(set(reports), {29, 34})
        self.assertEqual(reports[34]['evidence']['revision'], self.run['head_sha'])

    def test_diagnostic_chmod_does_not_count_as_normal_loader(self):
        for api in [29, 34]: self.save(self.evidence(api))
        bad = self.evidence(34)
        bad['status'] = 'images_passed_requires_host_readonly_fix'
        bad['installerWritableMexLoaded'] = False
        self.save(bad)
        with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_cannot_relabel_original_android14_as_a_fixed_loader(self):
        for api in [29, 34]: self.save(self.evidence(api))
        bad = self.evidence(34); bad['hostVariant'] = 'original-release'; self.save(bad)
        with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_a_missing_or_duplicate_page_prevents_publication(self):
        for api in [29, 34]: self.save(self.evidence(api))
        bad = self.evidence(34); bad['images'][-1]['index'] = 0; self.save(bad)
        with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_any_pixel_mismatch_prevents_publication(self):
        for api in [29, 34]: self.save(self.evidence(api))
        bad = self.evidence(29); bad['images'][0]['matchesPublicReaderReference'] = False; self.save(bad)
        with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_cannot_skip_host_permission_or_tr_policy_check(self):
        for field in ['inputMexWritableBeforeLoad', 'mexReadOnlyAfterLoader', 'singleTurkishSourceProfileVerified']:
            with self.subTest(field=field):
                for api in [29, 34]: self.save(self.evidence(api))
                bad = self.evidence(34); bad[field] = False; self.save(bad)
                with self.assertRaises(AssertionError): release.read_evidence(self.run)

    def test_source_changed_after_native_test_prevents_publication(self):
        request = self.root / 'request.json'; request.write_text('{"runId": 123}')
        run = dict(self.run, head_branch=release.BRANCH, conclusion='success', status='completed', name='Build native MangaDenizi installable package')
        with patch.object(release, 'REQUEST', request), patch.object(release, 'api', return_value=run), patch.object(release, 'command', return_value='extensions/mangadenizi/extension/src/main/FutureChange.kt'):
            with self.assertRaises(AssertionError): release.validate()


if __name__ == '__main__': unittest.main()
