#!/usr/bin/env bash
# Runs the tests and builds the signed release APK into dist/: app-release.apk, SHA256SUMS.txt
# Needs: JDK 21, Android SDK (see local.properties), and keystore.properties.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f keystore.properties ] || { echo "keystore.properties is missing: the release APK would be unsigned (see README, 'Signing')." >&2; exit 1; }
[ -f local.properties ] || [ -n "${ANDROID_HOME:-}" ] || { echo "Android SDK not found: create local.properties with sdk.dir=... or set ANDROID_HOME." >&2; exit 1; }

echo "==> Unit tests, lint and signed release APK"
# GRADLE=gradle uses an installed Gradle instead of downloading the wrapper distribution.
"${GRADLE:-./gradlew}" --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

echo "==> Collecting release files"
rm -rf dist && mkdir dist
cp app/build/outputs/apk/release/app-release.apk dist/
(cd dist && sha256sum app-release.apk > SHA256SUMS.txt)

APKSIGNER=$(ls -d "$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || echo "${ANDROID_HOME:-}")"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)
[ -n "$APKSIGNER" ] && "$APKSIGNER" verify dist/app-release.apk && echo "APK signature verified."
echo; cat dist/SHA256SUMS.txt
