#!/usr/bin/env bash
# Stage the public download bundle. The canonical APK is CI-built; three
# input modes:
#   (default)          prod path      — gh release download "v$VERSION"
#   --from-run <id>    staging path   — gh run download <id> -n fork-release-files
#                       (CI workflow_dispatch artifact, before a public tag)
#   --from-apk <path>  local staging  — copy a verified local APK
# All modes run the same verification: sha256sum -c, apksigner cert pin,
# and aapt2 package/versionCode/versionName against release.json.
set -euo pipefail
ORIG_PWD="$PWD"
cd "$(dirname "$0")"

MODE=release; RUN_ID=""; APK_SRC=""
case "${1:-}" in
  "") ;;
  --from-run) MODE=run; RUN_ID="${2:?usage: publish.sh --from-run <run-id>}";;
  --from-apk) MODE=apk; APK_SRC="${2:?usage: publish.sh --from-apk <path>}";;
  *) echo "usage: publish.sh [--from-run <run-id>|--from-apk <path>]" >&2; exit 2;;
esac

VERSION="$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')"
VC="$(python3 -c 'import json; print(json.load(open("release.json"))["versionCode"])')"
APPID="$(python3 -c 'import json; print(json.load(open("release.json"))["applicationId"])')"
TAG="v$VERSION"

rm -rf staging && mkdir staging
case "$MODE" in
  release)
    gh release download "$TAG" --dir staging --clobber \
      --pattern "wisp-${VERSION}.apk" --pattern "SHA256SUMS" ;;
  run)
    gh run download "$RUN_ID" -n fork-release-files -D staging
    [ -f "staging/wisp-${VERSION}.apk" ] || { echo "artifact missing wisp-${VERSION}.apk" >&2; exit 1; }
    ;;
  apk)
    src="$APK_SRC"
    [ -f "$src" ] || src="$ORIG_PWD/$APK_SRC"
    [ -f "$src" ] || { echo "APK not found: $APK_SRC" >&2; exit 1; }
    cp "$src" "staging/wisp-${VERSION}.apk"
    ( cd staging && sha256sum "wisp-${VERSION}.apk" > SHA256SUMS ) ;;
esac

( cd staging && sha256sum -c SHA256SUMS )
APK="staging/wisp-${VERSION}.apk"
APKSIGNER=""
for sdk_root in "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
  [ -n "$sdk_root" ] || continue
  cand="$(ls "$sdk_root"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)" || true
  if [ -n "$cand" ]; then APKSIGNER="$cand"; break; fi
done
[ -n "$APKSIGNER" ] || { echo "apksigner not found under ANDROID_HOME, ~/Android/Sdk or ~/android-sdk" >&2; exit 1; }
CERT_SHA256=3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a
"$APKSIGNER" verify --print-certs "$APK" \
  | grep -q "certificate SHA-256 digest: $CERT_SHA256" \
  || { echo "unexpected signing certificate on $APK" >&2; exit 1; }
AAPT2="$(dirname "$APKSIGNER")/aapt2"
BADGE="$("$AAPT2" dump badging "$APK" | grep '^package')"
echo "$BADGE" | grep -q "package: name='$APPID'"     || { echo "package mismatch: $BADGE" >&2; exit 1; }
echo "$BADGE" | grep -q "versionCode='$VC'"        || { echo "versionCode mismatch: $BADGE" >&2; exit 1; }
echo "$BADGE" | grep -q "versionName='$VERSION'"   || { echo "versionName mismatch: $BADGE" >&2; exit 1; }
LABEL="$("$AAPT2" dump badging "$APK" | grep '^application-label:' | head -1)"
echo "label: $LABEL"

HASH="$(sha256sum "$APK" | cut -d' ' -f1)"
export HASH
python3 - <<'PY'
import json, os, re, sys
h = os.environ['HASH']
d = json.load(open('release.json'))
d['sha256'] = h
json.dump(d, open('release.json', 'w'), indent=2)
open('release.json', 'a').write('\n')
s = open('index.html').read()
s, n = re.subn(r'id="sha256">[^<]*<', f'id="sha256">{h}<', s)
if n != 1:
    sys.exit(f'expected exactly one id="sha256" occurrence in index.html, found {n}')
open('index.html', 'w').write(s)
PY

# staging/ is the complete public bundle for the webroot: page,
# metadata, notes, APK, checksums. rsync --delete of staging/ never
# removes the page.
cp index.html release.json release-notes.md staging/
echo "staged public files:"
ls staging
echo
echo "deploy targets (webroot layout, Caddy/nginx):"
echo "  staging first : rsync -av --delete staging/ user@vps:/srv/staging.wisp.rtard.de/"
echo "  prod (tagged) : rsync -av --delete staging/ user@vps:/srv/wisp.rtard.de/"
echo "live verify (staging): curl -fsSLI https://staging.wisp.rtard.de/wisp-${VERSION}.apk | grep -iE 'content-type|content-disposition|strict-transport'"
