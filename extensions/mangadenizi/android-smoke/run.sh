#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PKG=com.kitsugi.manga.smoketest
MEX_ID=com_kitsugi_extension_tr_mangadenizi
mkdir -p .audit/android
adb install "$ROOT/build/smoke.apk"
adb shell "run-as $PKG mkdir -p files/manga_extensions"
adb shell -T "run-as $PKG sh -c 'cat > files/host.apk'" < .audit/android/Kitsugi-Beta-v2.4.381-foss.apk
adb shell -T "run-as $PKG sh -c 'cat > files/manga_extensions/$MEX_ID.mex'" < .audit/android/mangadenizi.mex
adb shell -T "run-as $PKG sh -c 'cat > files/reference.json'" < docs/audits/mangadenizi-2026-10-26.json
adb logcat -c
adb shell am instrument -w "$PKG/.SmokeInstrumentation" | tee .audit/android/instrumentation.txt
adb exec-out run-as "$PKG" cat files/report.json > .audit/android/report.json
adb logcat -d -s MangaDeniziSmoke MangaExtensionLoader art dex2oat AndroidRuntime > .audit/android/logcat.txt
python3 - <<'PY'
import base64, gzip, json
from pathlib import Path
report = json.loads(Path('.audit/android/report.json').read_text())
print(json.dumps(report, ensure_ascii=False, indent=2))
data = gzip.compress(json.dumps(report).encode(), compresslevel=9, mtime=0)
assert len(data) <= 20_000
for start in range(0, len(data), 2500):
    print('::notice title=MangaDeniziAndroidEvidence::' + json.dumps({'api': report['androidApi'], 'part': start // 2500, 'totalBytes': len(data), 'base64': base64.b64encode(data[start:start+2500]).decode()}))
assert report['status'] == 'passed', report.get('error', report['status'])
assert len(report['images']) == 53 and all(image['matchesPublicReaderReference'] for image in report['images']), 'Incomplete/incorrect native images'
PY
