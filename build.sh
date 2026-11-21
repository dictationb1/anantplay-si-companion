#!/bin/sh
# Builds AnantPlay-SI-Companion.apk with plain SDK tools — NO Gradle.
# (The same recipe official AnantPlay itself uses: AnantPlay/android build.sh.)
#
# Needs: an Android SDK (platform 34 + build-tools 34.x), a JDK 17+.
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
# Output: dist/AnantPlay-SI-Companion.apk (signed — installable immediately).
# Signing: optional release keystore via env (CI GitHub Secrets):
#   KS_BASE64 (base64 .keystore), KS_PASS, KS_KEYPASS (default: KS_PASS), KS_ALIAS
# — when absent, falls back to a generated debug key so the build NEVER fails.
set -e
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)   # newest build-tools installed
JAR=$SDK/platforms/android-34/android.jar
VERSION_NAME=1.0.0
VERSION_CODE=10000
VERSION="--version-code $VERSION_CODE --version-name $VERSION_NAME"
cd "$(dirname "$0")"

if [ ! -f "$JAR" ]; then
  echo "ERROR: $JAR missing. Install with: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\"" >&2
  exit 1
fi

rm -rf build dist
mkdir -p build/classes dist

echo "── aapt2: compile + link resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/app.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
  -A assets --java build --min-sdk-version 26 --target-sdk-version 34 $VERSION

echo "── javac (Java 8 target, like AnantPlay itself)"
# core-lambda-stubs.jar ships with build-tools; fall back to tools/ so the
# build never breaks if a future build-tools drops it
LAMBDA=$BT/core-lambda-stubs.jar
[ -f "$LAMBDA" ] || LAMBDA=tools/core-lambda-stubs.jar
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 \
  -bootclasspath "$JAR:$LAMBDA" \
  -d build/classes $(find src build/lk -name '*.java')

echo "── d8 → dex"
"$BT/d8" --lib "$JAR" --min-api 26 --output build $(find build/classes -name '*.class')

echo "── package"
cd build
zip -q app.apk classes.dex
cd ..

echo "── zipalign + sign"
"$BT/zipalign" -f 4 build/app.apk build/aligned.apk

# Optional release keystore from env (CI GitHub Secrets); clean fallback to a
# generated debug key so the build NEVER fails when secrets aren't configured.
if [ -n "$KS_BASE64" ]; then
  echo "   signing with the release keystore (KS_ALIAS=${KS_ALIAS:-sicomp})"
  printf '%s' "$KS_BASE64" | base64 -d > build/release.keystore
  KS_FILE=build/release.keystore
  KS_STOREPASS=$KS_PASS
  KS_KEYPASS=${KS_KEYPASS:-$KS_PASS}
  KS_ALIAS=${KS_ALIAS:-sicomp}
else
  echo "   no release keystore configured — debug key fallback"
  if [ ! -f build/debug.keystore ]; then
    keytool -genkeypair -keystore build/debug.keystore -storetype PKCS12 -alias sicomp \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=AnantPlay SI Companion" -storepass android -keypass android >/dev/null 2>&1
  fi
  KS_FILE=build/debug.keystore
  KS_STOREPASS=android
  KS_KEYPASS=android
  KS_ALIAS=sicomp
fi
"$BT/apksigner" sign --ks "$KS_FILE" --ks-pass "pass:$KS_STOREPASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" --out "dist/AnantPlay-SI-Companion.apk" build/aligned.apk
"$BT/apksigner" verify --print-certs "dist/AnantPlay-SI-Companion.apk" | head -3

echo "── done: dist/AnantPlay-SI-Companion.apk ($(wc -c < "dist/AnantPlay-SI-Companion.apk") bytes)"
