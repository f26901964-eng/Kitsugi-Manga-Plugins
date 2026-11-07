#!/usr/bin/env bash
# Fresh, disposable emulator: no user data or account is used.
set -euo pipefail
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
adb shell "D=/data/user/0/$PKG; U=\$(stat -c '%u:%g' \$D); mkdir -p \$D/files/manga_extensions \$D/shared_prefs; cp /data/local/tmp/merlin.apk \$D/files/manga_extensions/eu_kanade_tachiyomi_extension_tr_merlinscans.apk; cp /data/local/tmp/merlin-old.apk \$D/files/manga_extensions/tachiyomi-tr.merlinscans-v1.6.9.apk; cp /data/local/tmp/merlin-trust.xml \$D/shared_prefs/security_trust.xml; chown -R \$U \$D/files/manga_extensions \$D/shared_prefs/security_trust.xml; restorecon -R \$D/files/manga_extensions \$D/shared_prefs/security_trust.xml"
adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 35
# Return from any first-run card opened while the home screen was loading.
adb shell input keyevent KEYCODE_BACK
sleep 15
adb shell uiautomator dump /sdcard/window.xml >/dev/null || true
adb pull /sdcard/window.xml /tmp/merlin-android/window.xml || true
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png /tmp/merlin-android/screenshot.png
adb logcat -d -v time > /tmp/merlin-android/logcat.txt
printf 'UI dump: ' && wc -c /tmp/merlin-android/window.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('/tmp/merlin-android/window.xml').getroot()
for node in root.iter('node'):
    text = node.get('text') or node.get('content-desc')
    if text:
        safe = text.replace('%', '%25').replace('\n', ' ')
        print(f"::notice title=Android UI::{safe} {node.get('bounds', '')}")
lines = open('/tmp/merlin-android/logcat.txt', errors='replace').readlines()
selected = [line for line in lines if any(tag in line.lower() for tag in ('merlinscans', 'merlintoon', 'mangaextensionloader'))]
for line in selected[-35:]:
    print(f"::notice title=Android Merlin::{line[-450:].strip().replace('%', '%25')}")
PY
