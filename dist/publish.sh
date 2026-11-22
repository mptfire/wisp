#!/usr/bin/env bash
# Assemble the local dist bundle: copy the signed APK under its release
# name, regenerate SHA256SUMS, and remind of the two publish paths.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')"
APK=../app/build/outputs/apk/release/app-release.apk
test -f "$APK" || { echo "build the APK first: ./gradlew assembleRelease"; exit 1; }
cp "$APK" "wisp-$VERSION.apk"
sha256sum "wisp-$VERSION.apk" > SHA256SUMS
cat SHA256SUMS
echo
echo "publish paths:"
echo "  static host : rsync -av --delete ./ user@host:/srv/wisp.rtard.de/download/"
echo "  gh release  : gh release create \"v$VERSION\" \"wisp-$VERSION.apk\" SHA256SUMS --notes-file release-notes.md"
