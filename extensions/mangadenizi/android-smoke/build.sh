#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
mkdir -p "$OUT/classes" "$OUT/dex"
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-35/android.jar}"
javac -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" "$ROOT/SmokeInstrumentation.java"
"$TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class' -print)
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$ROOT/AndroidManifest.xml" -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
keytool -genkeypair -keystore "$OUT/test.keystore" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Disposable integration test' -keyalg RSA -keysize 2048 -validity 2
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$OUT/test.keystore" --ks-pass pass:android --out "$OUT/smoke.apk" "$OUT/aligned.apk"
