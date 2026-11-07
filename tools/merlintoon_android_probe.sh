#!/usr/bin/env bash
# Fresh, disposable emulator: no user data or account is used.
set -Eeuo pipefail
trap 'code=$?; echo "::error title=Android probe::Command failed with exit $code: $BASH_COMMAND"' ERR
mkdir -p /tmp/merlin-android
adb install /tmp/kitsugi/*.apk
PKG=$(adb shell pm list packages | sed -n 's/^package://p' | grep -i kitsugi | head -1 | tr -d '\r')
echo "Installed package: $PKG"
adb root
adb wait-for-device
adb shell id
adb push prebuilt/apk/tachiyomi-tr.merlinscans-v1.6.11.apk /data/local/tmp/merlin.apk
adb push /tmp/merlin-v1.6.9.apk /data/local/tmp/merlin-old.apk
# Seed the published extension and its trusted signing hash inside the fresh emulator only.
# This is equivalent to installing and accepting that signature in the test environment.
cat > /tmp/merlin-trust.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><set name="trusted_signatures"><string>a12ba4b532bd7fa9c6f2e7eb05c841bca6560ffa871252e915ba72c429fe9dba</string></set></map>
XML
adb push /tmp/merlin-trust.xml /data/local/tmp/merlin-trust.xml
cat > /tmp/merlin-profile.xml <<'XML'
<map><boolean name="repo_first_manga_profile_v2" value="true"/><boolean name="disabled_default_source_eu.kanade.tachiyomi.extension.tr.merlinscans" value="true"/></map>
XML
adb push /tmp/merlin-profile.xml /data/local/tmp/merlin-profile.xml
adb shell "D=/data/user/0/$PKG; U=\$(stat -c '%u:%g' \$D); mkdir -p \$D/files/manga_extensions \$D/shared_prefs; cp /data/local/tmp/merlin-trust.xml \$D/shared_prefs/security_trust.xml; cp /data/local/tmp/merlin-profile.xml \$D/shared_prefs/kitsugi_manga_default_profile.xml; chown -R \$U \$D/files/manga_extensions \$D/shared_prefs; restorecon -R \$D/files/manga_extensions \$D/shared_prefs"
if [[ "${TEST_MODE:-duplicate}" != legacy-empty ]]; then
    adb shell "D=/data/user/0/$PKG; U=\$(stat -c '%u:%g' \$D); cp /data/local/tmp/merlin.apk \$D/files/manga_extensions/eu_kanade_tachiyomi_extension_tr_merlinscans.apk; chown \$U \$D/files/manga_extensions/eu_kanade_tachiyomi_extension_tr_merlinscans.apk; restorecon \$D/files/manga_extensions/eu_kanade_tachiyomi_extension_tr_merlinscans.apk"
fi
if [[ "${TEST_MODE:-duplicate}" == duplicate || "${TEST_MODE:-duplicate}" == patched || "${TEST_MODE:-duplicate}" == legacy-empty ]]; then
    adb shell "D=/data/user/0/$PKG; U=\$(stat -c '%u:%g' \$D); cp /data/local/tmp/merlin-old.apk \$D/files/manga_extensions/tachiyomi-tr.merlinscans-v1.6.9.apk; chown \$U \$D/files/manga_extensions/tachiyomi-tr.merlinscans-v1.6.9.apk; restorecon \$D/files/manga_extensions/tachiyomi-tr.merlinscans-v1.6.9.apk"
fi
# Isolated synthetic fixture and CONNECT proxy, used only by the disposable emulator.
openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/fixture.key -out /tmp/fixture.crt -days 1 -subj '/CN=merlintoon.com' >/dev/null 2>&1
FIXTURE_READER="${TEST_MODE:-duplicate}" FIXTURE_KEY=/tmp/fixture.key FIXTURE_CERT=/tmp/fixture.crt python3 tools/merlintoon_android_fixture.py > /tmp/merlin-android/fixture.log 2>&1 &
FIXTURE_PID=$!
python3 tools/merlintoon_android_proxy.py > /tmp/merlin-android/proxy.log 2>&1 &
PROXY_PID=$!
trap 'kill "$FIXTURE_PID" "$PROXY_PID" 2>/dev/null || true' EXIT
adb shell settings put global http_proxy 10.0.2.2:8888
adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE || true
adb logcat -G 20M || true
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 35
# Open the actual app's manga browser through its public deep-link entry point.
adb shell am start -a android.intent.action.VIEW -d 'Kitsugianimelist://v1/manga/merlin/1' "$PKG/.MainActivity"
sleep 30
adb shell uiautomator dump /sdcard/window.xml >/dev/null || true
adb pull /sdcard/window.xml /tmp/merlin-android/window.xml || true
if [[ "${TEST_MODE:-duplicate}" == single* || "${TEST_MODE:-duplicate}" == legacy-empty ]]; then
    cp /tmp/merlin-android/window.xml /tmp/merlin-android/browse.xml
    tap_matching() {
        local needle="$1" coords
        coords=$(python3 - "$needle" /tmp/merlin-android/window.xml <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle = sys.argv[1].casefold()
root = ET.parse(sys.argv[2]).getroot()
for node in root.iter('node'):
    text = ' '.join((node.get('text', ''), node.get('content-desc', ''))).casefold()
    if needle not in text:
        continue
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        if x2 > x1 and y2 > y1:
            print((x1+x2)//2, (y1+y2)//2)
            break
PY
        )
        if [[ -n "$coords" ]]; then
            adb shell input tap $coords
            return 0
        fi
        echo "::warning title=Android navigation::Could not find $needle on the current screen"
        return 1
    }
    if tap_matching 'Buyucu Coban'; then
        sleep 25
        adb shell uiautomator dump /sdcard/window.xml >/dev/null || true
        adb pull /sdcard/window.xml /tmp/merlin-android/window.xml || true
        cp /tmp/merlin-android/window.xml /tmp/merlin-android/detail.xml
        if tap_matching 'Read from start'; then
            sleep 25
            adb shell uiautomator dump /sdcard/window.xml >/dev/null || true
            adb pull /sdcard/window.xml /tmp/merlin-android/window.xml || true
        fi
    fi
fi
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png /tmp/merlin-android/screenshot.png
adb logcat -d -v time > /tmp/merlin-android/logcat.txt
adb shell cat "/data/user/0/$PKG/files/crash_log.txt" > /tmp/merlin-android/crash.txt 2>/dev/null || true
printf 'UI dump: ' && wc -c /tmp/merlin-android/window.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
for name in ('fixture', 'proxy'):
    log = open(f'/tmp/merlin-android/{name}.log', errors='replace').read()
    if log:
        safe = log[-4000:].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print(f"::notice title=Synthetic {name}::{safe}")
for filename in ('browse.xml', 'detail.xml', 'window.xml'):
    path = Path('/tmp/merlin-android') / filename
    if not path.exists():
        continue
    items = []
    for node in ET.parse(path).getroot().iter('node'):
        text = node.get('text') or node.get('content-desc')
        if text or node.get('clickable') == 'true':
            safe = (text or '<clickable>').replace('%', '%25').replace('\n', ' ')
            items.append(f"{safe} {node.get('bounds', '')}")
    print(f"::notice title=Android UI {filename}::{'%0A'.join(items)[:9000]}")
crash = open('/tmp/merlin-android/crash.txt', errors='replace').read()
if crash:
    import re
    match = re.search(r'▶ HATA ZİNCİRİ(.*?)(?:▶ SON BİLİNEN EKRAN|$)', crash, re.S)
    excerpt = (match.group(1) if match else crash)[:8000]
    escaped = excerpt.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f"::warning title=Android crash report::{escaped}")
lines = open('/tmp/merlin-android/logcat.txt', errors='replace').readlines()
selected = [line for line in lines if any(tag in line.lower() for tag in ('merlinscans', 'merlintoon', 'mangaextensionloader'))]
for line in selected[-35:]:
    print(f"::notice title=Android Merlin::{line[-450:].strip().replace('%', '%25')}")
PY
