#!/usr/bin/env bash
# 独立测试包避免超过默认设备套件的 instrumentation 数量上限。
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../../.." && pwd)"
cd "$root"
sdk="${ANDROID_SDK_ROOT:?}"
tools="$sdk/build-tools/35.0.0"
build="$root/target/android/assistance-test"
mkdir -p "$build/classes" "$build/dex"
javac --release 17 -Xlint:all -Werror -cp "$sdk/platforms/android-35/android.jar" -d "$build/classes" platforms/android/tests/device/assistance/InputAssistancePreparation.java
jar --create --file "$build/classes.jar" -C "$build/classes" .
"$tools/d8" --release --min-api 28 --lib "$sdk/platforms/android-35/android.jar" --output "$build/dex" "$build/classes.jar"
"$tools/aapt2" link -I "$sdk/platforms/android-35/android.jar" --manifest platforms/android/tests/device/assistance/AndroidManifest.xml -o "$build/unsigned.apk"
(cd "$build/dex" && zip -q -0 "$build/unsigned.apk" classes.dex)
repo_root="$root"
source platforms/android/scripts/signing.sh
"$tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
"$tools/apksigner" sign "${android_signing[@]}" --out "$build/test.apk" "$build/aligned.apk"
"$tools/apksigner" verify "$build/test.apk"
echo "$build/test.apk"
