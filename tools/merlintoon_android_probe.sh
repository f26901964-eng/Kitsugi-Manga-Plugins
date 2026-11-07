#!/usr/bin/env bash
# Fresh, disposable emulator: no user data or account is used.
set -euo pipefail
mkdir -p /tmp/merlin-android
adb install /tmp/kitsugi/*.apk
PKG=$(adb shell pm list packages | sed -n 's/^package://p' | grep -i kitsugi | head -1 | tr -d '\r')
echo "Installed package: $PKG"
adb root
adb wait-for-device
# This is a disposable rooted emulator: map the test hostname to adb reverse
# without using the app's domain override (which has a separate recursion bug).
adb disable-verity
adb reboot
adb wait-for-device
adb root
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
adb remount
adb shell "echo '127.0.0.1 merlintoon.com' >> /system/etc/hosts"
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
adb shell "D=/data/user/0/$PKG; U=\$(stat -c '%u:%g' \$D); mkdir -p \$D/files/manga_extensions \$D/shared_prefs; cp /data/local/tmp/merlin.apk \$D/files/manga_extensions/eu_kanade_tachiyomi_extension_tr_merlinscans.apk; cp /data/local/tmp/merlin-old.apk \$D/files/manga_extensions/tachiyomi-tr.merlinscans-v1.6.9.apk; cp /data/local/tmp/merlin-trust.xml \$D/shared_prefs/security_trust.xml; cp /data/local/tmp/merlin-profile.xml \$D/shared_prefs/kitsugi_manga_default_profile.xml; chown -R \$U \$D/files/manga_extensions \$D/shared_prefs; restorecon -R \$D/files/manga_extensions \$D/shared_prefs"
# Isolated synthetic fixture: only the disposable emulator uses this hostname override.
openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/fixture.key -out /tmp/fixture.crt -days 1 -subj '/CN=127.0.0.1' >/dev/null 2>&1
FIXTURE_KEY=/tmp/fixture.key FIXTURE_CERT=/tmp/fixture.crt python3 tools/merlintoon_android_fixture.py > /tmp/merlin-android/fixture.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true' EXIT
adb reverse tcp:443 tcp:8443
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
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png /tmp/merlin-android/screenshot.png
adb logcat -d -v time > /tmp/merlin-android/logcat.txt
adb shell cat "/data/user/0/$PKG/files/crash_log.txt" > /tmp/merlin-android/crash.txt 2>/dev/null || true
printf 'UI dump: ' && wc -c /tmp/merlin-android/window.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('/tmp/merlin-android/window.xml').getroot()
for node in root.iter('node'):
    text = node.get('text') or node.get('content-desc')
    if text or node.get('clickable') == 'true':
        safe = (text or '<clickable>').replace('%', '%25').replace('\n', ' ')
        print(f"::notice title=Android UI::{safe} {node.get('bounds', '')}")
for line in open('/tmp/merlin-android/fixture.log', errors='replace'):
    print(f"::notice title=Synthetic fixture::{line.strip().replace('%', '%25')}")
crash = open('/tmp/merlin-android/crash.txt', errors='replace').read()
if crash:
    import re
    match = re.search(r'▶ HATA ZİNCİRİ(.*?)(?:▶ SON BİLİNEN EKRAN|$)', crash, re.S)
    excerpt = (match.group(1) if match else crash)[-14000:]
    escaped = excerpt.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f"::warning title=Android crash report::{escaped}")
lines = open('/tmp/merlin-android/logcat.txt', errors='replace').readlines()
selected = [line for line in lines if any(tag in line.lower() for tag in ('merlinscans', 'merlintoon', 'mangaextensionloader'))]
for line in selected[-35:]:
    print(f"::notice title=Android Merlin::{line[-450:].strip().replace('%', '%25')}")
PY
