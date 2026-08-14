#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release.
#
#   scripts/release.sh              # uses versionName from app/build.gradle.kts
#   scripts/release.sh --notes "…"  # custom release notes
#
# Requires keystore.properties at the project root (never committed) and gh being logged in.
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f keystore.properties ]]; then
  echo "keystore.properties is missing — see the README, 'Signed release'." >&2
  exit 1
fi

VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
TAG="v${VERSION}"
NOTES="${2:-Signed release build of SpectroFlac ${VERSION}.}"

echo "==> Building ${TAG}"
./gradlew --quiet :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "no APK produced" >&2; exit 1; }

# Refuse to ship an unsigned build: the release signing config is skipped silently
# when keystore.properties has no storeFile.
if ! "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner verify "$APK" >/dev/null 2>&1; then
  echo "APK is not signed — check keystore.properties" >&2
  exit 1
fi

STAGED="build/SpectroFlac-${VERSION}.apk"
mkdir -p build
cp "$APK" "$STAGED"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "==> ${TAG} already exists, uploading asset to it"
  gh release upload "$TAG" "$STAGED" --clobber
else
  echo "==> Creating release ${TAG}"
  gh release create "$TAG" "$STAGED" --title "SpectroFlac ${VERSION}" --notes "$NOTES"
fi

echo "==> Done: $(gh release view "$TAG" --json url -q .url)"
