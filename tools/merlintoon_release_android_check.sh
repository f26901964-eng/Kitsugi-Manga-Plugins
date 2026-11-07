#!/usr/bin/env bash
# Exercise the newly built APK against clearly synthetic reader fixtures only.
set -Eeuo pipefail
trap 'code=$?; echo "::error title=MerlinToon release Android::Command failed with exit $code: $BASH_COMMAND"' ERR

TEST_MODE=single bash tools/merlintoon_android_probe.sh
test ! -s /tmp/merlin-android/crash.txt
grep -q 'Manga page 1' /tmp/merlin-android/window.xml
for page in 0001 0002 0003; do
    grep -q "GET /pages/$page.png" /tmp/merlin-android/fixture.log
done
echo '::notice title=MerlinToon release Android::Three synthetic reader images loaded in the published Kitsugi app.'

# Clear only this disposable emulator before checking the empty-reader diagnostic.
adb uninstall com.kitsugi.animelist
rm -f /tmp/merlin-android/window.xml /tmp/merlin-android/browse.xml /tmp/merlin-android/detail.xml /tmp/merlin-android/crash.txt
TEST_MODE=single-empty bash tools/merlintoon_android_probe.sh
test ! -s /tmp/merlin-android/crash.txt
grep -q 'MerlinToon 1.6.12: Bölüm görselleri alınamadı' /tmp/merlin-android/window.xml
grep -q 'KTS-SRC-9008' /tmp/merlin-android/window.xml
grep -q 'GET /seri/buyucu-coban/bolum-1/' /tmp/merlin-android/fixture.log
echo '::notice title=MerlinToon release Android::Empty synthetic reader shows the 1.6.12 source diagnostic.'
