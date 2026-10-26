#!/usr/bin/env python3
"""Publish only a MEX proven against real host bytecode; never an unsigned host APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
REPO = 'f26901964-eng/Kitsugi-Manga-Plugins'
BRANCH = 'arena/35425e65-kitsugi-manga-plugins'
TAG = 'native-mangadenizi-v1'
FILE = 'com_kitsugi_extension_tr_mangadenizi.mex'
BASE = f'https://github.com/{REPO}/releases/download/{TAG}'
REQUEST = ROOT / 'extensions/mangadenizi/release-request.json'

def command(*args):
    return subprocess.check_output(args, text=True).strip()

def api(path):
    return json.loads(command('gh', 'api', path))

def validate():
    request = json.loads(REQUEST.read_text())
    run = api(f'repos/{REPO}/actions/runs/{int(request["runId"])}')
    assert run['head_branch'] == BRANCH and run['conclusion'] == 'success' and run['status'] == 'completed', 'The complete Android 10/14 acceptance workflow must succeed'
    assert run['name'] == 'Build native MangaDenizi installable package', 'Wrong test workflow'
    paths = ['extensions/mangadenizi', 'integration/kitsugi-loader-tests', 'integration/kitsugi-mex', 'sources.properties']
    changed = command('git', 'diff', '--name-only', run['head_sha'], 'HEAD', '--', *paths).splitlines()
    assert all(path.endswith('/README.md') or path.endswith('/release-request.json') for path in changed), f'Executable source differs from the tested revision: {changed}'
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'run_id={run["id"]}\n')
    print(f'Validated workflow {run["id"]}, native source {run["head_sha"]}')
    return run

def publish():
    run = validate()
    evidence = {}
    for path in (ROOT / '.audit/publish/evidence').rglob('report.json'):
        report = json.loads(path.read_text())
        assert report['status'] == 'passed' and report['inputMexWritableBeforeLoad'] and report['installerWritableMexLoaded'], report
        assert len(report['images']) == 53 and all(image['matchesPublicReaderReference'] for image in report['images']), 'Incomplete/incorrect native pixels'
        assert report['hostTargetSdk'] == report['testAppTargetSdk'] == 36
        report['evidence'] = {'workflowRunId': run['id'], 'revision': run['head_sha'], 'workflowUrl': run['html_url']}
        evidence[report['androidApi']] = report
    assert set(evidence) == {29, 34}, 'Need actual Android 10 AND Android 14 results'
    assert evidence[29]['hostVariant'] == 'original-release'
    assert evidence[34]['hostVariant'] == 'release-with-readonly-loader-test-fixture'
    assert evidence[34]['singleTurkishSourceProfileVerified'] and evidence[34]['mexReadOnlyAfterLoader']
    binary = ROOT / '.audit/publish/package/mangadenizi.mex'
    with zipfile.ZipFile(binary) as archive:
        assert set(archive.namelist()) == {'classes.dex', 'manifest.json', 'LICENSE', 'NOTICE'}
        manifest = json.loads(archive.read('manifest.json'))
        expected = json.loads((ROOT / 'extensions/mangadenizi/manifest.json').read_text())
        assert manifest == expected and manifest['sourceClassName'] == 'com.kitsugi.extension.tr.mangadenizi.MangaDeniziSource'
        dex_sha = hashlib.sha256(archive.read('classes.dex')).hexdigest()
    sha = hashlib.sha256(binary.read_bytes()).hexdigest()
    compatibility = {
        'android10': '53/53 pixels matched; original Kitsugi v2.4.381 loader',
        'android14': '53/53 pixels matched ONLY with the supplied app-side loader fix in a CI test copy',
        'requiresAppLoaderFixFromApi': 34,
        'signedAndroid14AppUpdateIncluded': False,
        'warning': 'MEX cannot repair its own host loader. An unmodified v2.4.381 on Android 14+ is NOT a verified normal installation. Do not uninstall the app or disable Android code-loading protection.',
    }
    index = {
        'extensionList': {'extensions': [{
            'packageName': manifest['packageName'], 'name': 'MangaDenizi',
            'versionName': manifest['versionName'], 'versionCode': str(manifest['versionCode']),
            'contentWarning': 'CONTENT_WARNING_NONE',
            'resources': {'apkUrl': f'{BASE}/{FILE}', 'iconUrl': f'{BASE}/icon.png', 'sha256': sha},
            'sources': [{'name': 'MangaDenizi', 'language': 'tr', 'type': 'Manga', 'baseUrl': 'https://mangadenizi.net'}],
        }]},
        'compatibility': compatibility,
    }
    installation = {
        'indexUrl': f'{BASE}/index.json', 'packageUrl': f'{BASE}/{FILE}',
        'packageSha256': sha, 'dexSha256': dex_sha, 'packageBytes': binary.stat().st_size,
        'builtAndTestedRevision': run['head_sha'], 'acceptanceWorkflow': run['html_url'],
        'compatibility': compatibility,
    }
    out = ROOT / '.audit/publish/release'; out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, out / FILE)
    shutil.copyfile(ROOT / 'keiyoushi-extensions-tr/src/tr/mangadenizi/res/mipmap-xxxhdpi/ic_launcher.png', out / 'icon.png')
    shutil.copyfile(ROOT / 'integration/kitsugi-mex/loader-readonly.patch', out / 'kitsugi-loader-recovery.patch')
    (ROOT / 'packages').mkdir(exist_ok=True)
    shutil.copyfile(binary, ROOT / 'packages/mangadenizi.mex')
    for name, value in [('index.json', index), ('compatibility.json', installation)]:
        (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'index.json').write_text((out / 'index.json').read_text())
    (ROOT / 'docs/mangadenizi-installation.json').write_text((out / 'compatibility.json').read_text())
    for version, report in evidence.items():
        name = f'mangadenizi-android-{version}.json'
        (ROOT / 'docs/audits' / name).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        shutil.copyfile(ROOT / 'docs/audits' / name, out / name)
    notes = f'''# MangaDenizi native MEX 1.0.0\n\nAynı kaynak paketi: manga / manhwa / webtoon. İki bölümün 53 sayfası gerçek Android bitmap'leriyle doğrulandı.\n\n## Önemli: Android 10 ve Android 14 kurulumu aynı durumda değil\n\n- **Android 10:** orijinal Kitsugi v2.4.381 yükleyicisiyle 53/53 sayfa geçti.\n- **Android 14:** 53/53 sayfa, uygulama yükleyici düzeltmesi bulunan CI test kopyasıyla geçti. **Orijinal v2.4.381 için Android 14 kurulumu tamamlandı denmiyor.**\n- Android 14 için normal, aynı imzalı Kitsugi uygulama güncellemesi gerekir. Bu yayında imzalı uygulama güncellemesi YOKTUR.\n- `kitsugi-loader-recovery.patch` uygulama tarafındaki salt-okunur yükleyici ve tek Türkçe kaynak seçimini içerir. Okuma verilerini / eski eklenti dosyalarını silmez.\n- Uygulamayı kaldırmayın, Android güvenliğini kapatmayın. MEX kendisini yükleyecek uygulamayı içeriden onaramaz.\n\nDepo adresi: `{BASE}/index.json`\nPaket: `{FILE}` (Android paket yükleyicisi değil, Kitsugi içinden yüklenir).\nSHA-256: `{sha}`\n\nKatalog sayısı 26 Ekim 2026 gözleminde 110 seriydi; bütün katalog/bölümler için kusursuzluk garantisi değildir.\n\n[Test ve gerçek kapsam]({run['html_url']})\n'''
    (out / 'release-notes.md').write_text(notes)
    subprocess.run(['gh', 'release', 'create', TAG, '--repo', REPO, '--target', command('git', 'rev-parse', 'HEAD'), '--title', 'MangaDenizi 1.0 — native MEX (Android 14 requires app loader fix)', '--prerelease', '--notes-file', str(out / 'release-notes.md'), *[str(path) for path in sorted(out.iterdir()) if path.name != 'release-notes.md']], check=True)
    assets = api(f'repos/{REPO}/releases/tags/{TAG}')['assets']
    assert {FILE, 'index.json', 'icon.png', 'compatibility.json', 'kitsugi-loader-recovery.patch'}.issubset({asset['name'] for asset in assets})
    print(f'::notice title=Published MangaDenizi::Index {BASE}/index.json ; SHA-256 {sha}; Android 14 still requires the app-side fix')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--validate', action='store_true'); mode.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    if args.validate: validate()
    else: publish()
