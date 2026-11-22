#!/usr/bin/env bash
# Stage the public download bundle for static hosting. The canonical APK
# is ALWAYS the CI-built GitHub Release asset (tag v<version> from the
# fork-release workflow); this script never builds, so the hashes on the
# site match the published release exactly.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')"
VC="$(python3 -c 'import json; print(json.load(open("release.json"))["versionCode"])')"
TAG="v$VERSION"
rm -rf staging && mkdir staging
gh release download "$TAG" --dir staging --clobber \
  --pattern "wisp-${VERSION}.apk" --pattern "SHA256SUMS"
( cd staging && sha256sum -c SHA256SUMS )
APK="staging/wisp-${VERSION}.apk"
APKSIGNER="$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner | sort -V | tail -1)"
CERT_SHA256=3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a
"$APKSIGNER" verify --print-certs "$APK" \
  | grep -q "certificate SHA-256 digest: $CERT_SHA256" \
  || { echo "unexpected signing certificate on $APK" >&2; exit 1; }
AAPT2="$(dirname "$APKSIGNER")/aapt2"
BADGE="$("$AAPT2" dump badging "$APK" | grep '^package')"
echo "$BADGE" | grep -q "versionCode='$VC'"   || { echo "versionCode mismatch: $BADGE" >&2; exit 1; }
echo "$BADGE" | grep -q "versionName='$VERSION'" || { echo "versionName mismatch: $BADGE" >&2; exit 1; }
HASH="$(sha256sum "$APK" | cut -d' ' -f1)"
export HASH
python3 - <<'PY'
import json, os, re
h = os.environ['HASH']
d = json.load(open('release.json'))
d['sha256'] = h
json.dump(d, open('release.json', 'w'), indent=2)
open('release.json', 'a').write('\n')
s = open('index.html').read()
s = re.sub(r'id="sha256">[^<]*<', f'id="sha256">{h}<', s)
open('index.html', 'w').write(s)
PY
echo "staged public files only:"
ls staging
echo "publish: rsync -av --delete staging/ user@host:/srv/wisp.rtard.de/download/"
