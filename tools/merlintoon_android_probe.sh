#!/usr/bin/env bash
# Fresh, disposable emulator: no user data or account is used.
set -euo pipefail
mkdir -p /tmp/merlin-android
adb install /tmp/kitsugi/*.apk
PKG=$(adb shell pm list packages | sed -n 's/^package://p' | grep -i kitsugi | head -1)
echo "Installed package: $PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 30
adb shell uiautomator dump /sdcard/window.xml >/dev/null || true
adb pull /sdcard/window.xml /tmp/merlin-android/window.xml || true
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png /tmp/merlin-android/screenshot.png
adb logcat -d -v time > /tmp/merlin-android/logcat.txt
printf 'UI dump: ' && wc -c /tmp/merlin-android/window.xml
cat /tmp/merlin-android/window.xml | head -c 12000
