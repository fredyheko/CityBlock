#!/usr/bin/env bash
# Сборка APK без Gradle — только инструментами Android SDK.
#   ANDROID_HOME=/путь/к/sdk ./build.sh
# Нужны: JDK 17+, Android SDK с platforms;android-35 и build-tools;35.0.0.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
VERSION_CODE="${VERSION_CODE:-7}"
VERSION_NAME="${VERSION_NAME:-2.0}"
OUT=build
APK="$OUT/zayavki-pm.apk"

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "› ресурсы"
"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
    --manifest AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$OUT/res/res.zip"

echo "› java"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
    -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

echo "› dex"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex)

echo "› подпись"
KS="${KEYSTORE:-release.keystore}"
PASS_FILE="$KS.pass"   # пароль ключа: рядом с ключом, в git не попадает
if [ ! -f "$KS" ]; then
    head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > "$PASS_FILE"; echo >> "$PASS_FILE"
    keytool -genkeypair -keystore "$KS" -storepass:file "$PASS_FILE" -keypass:file "$PASS_FILE" \
        -alias zayavki -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Zayavki PM" >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASS_FILE" \
    --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "✓ $APK ($(du -h "$APK" | cut -f1))"
