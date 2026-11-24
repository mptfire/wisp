#!/usr/bin/env bash
# Stage the public download bundle. Canonical APK is CI-built.
# Modes: (default) gh release download v$VERSION (prod path)
#        --from-run <id>  gh run download CI artifact (staging)
#        --from-apk <path> copy local APK (local staging only)
# Every mode: sha256sum -c, apksigner cert pin, aapt2 badging.
# Verified hash is patched into STAGING copies only.
set -euo pipefail
ORIG_PWD="$PWD"
cd "$(dirname "$0")"
MODE=release; RUN_ID=""; APK_SRC=""
case "${1:-}" in
  "") ;;
  --from-run) MODE=run; RUN_ID="${2:-}";;
  --from-apk) MODE=apk; APK_SRC="${2:-}";;
  *) echo "usage: publish.sh [--from-run <id>|--from-apk <path>]" >&2; exit 2;;
esac
case "$MODE" in
  run)  [ -n "$RUN_ID" ] || { echo "need --from-run <id>" >&2; exit 2; };;
  apk)  [ -n "$APK_SRC" ] || { echo "need --from-apk <path>" >&2; exit 2; };;
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
    rm -f staging/certs.txt
    [ -f "staging/wisp-${VERSION}.apk" ] || { echo "artifact missing wisp-${VERSION}.apk" >&2; exit 1; } ;;
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
for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
  [ -n "$sdk_root" ] || continue
  cand="$(ls "$sdk_root"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)" || true
  if [ -n "$cand" ]; then APKSIGNER="$cand"; break; fi
done
[ -n "$APKSIGNER" ] || { echo "apksigner not found (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Android/Sdk, ~/android-sdk)" >&2; exit 1; }
CERT_SHA256=3b99014bdceabeaa0575d8c6aac39150d6fdde78371140384789e0eb3590256a
"$APKSIGNER" verify --print-certs "$APK" \
  | grep -q "certificate SHA-256 digest: $CERT_SHA256" \
  || { echo "unexpected signing certificate on $APK" >&2; exit 1; }
AAPT2="$(dirname "$APKSIGNER")/aapt2"
BADGE="$("$AAPT2" dump badging "$APK" | grep "^package")"
echo "$BADGE" | grep -q "package: name='$APPID'"   || { echo "package mismatch: $BADGE" >&2; exit 1; }
echo "$BADGE" | grep -q "versionCode='$VC'"       || { echo "versionCode mismatch: $BADGE" >&2; exit 1; }
echo "$BADGE" | grep -q "versionName='$VERSION'"  || { echo "versionName mismatch: $BADGE" >&2; exit 1; }
echo "verified: $BADGE"

echo "label: $("$AAPT2" dump badging "$APK" | grep "^application-label:" | head -1)"

HASH="$(sha256sum "$APK" | cut -d' ' -f1)"
export HASH
# page bundle first, then patch the verified hash into STAGING copies
cp index.html release.json release-notes.md firewisp-logo.png staging/
python3 - <<'PY'
import json, os, re, sys
h = os.environ["HASH"]
d = json.load(open("staging/release.json"))
d["sha256"] = h
json.dump(d, open("staging/release.json", "w"), indent=2)
open("staging/release.json", "a").write("\n")
s = open("staging/index.html").read()
s, n = re.subn(r'id="sha256">[^<]*<', f'id="sha256">{h}<', s)
if n != 1:
    sys.exit("expected exactly one id=sha256 site in staging/index.html, found %d" % n)
open("staging/index.html", "w").write(s)
PY

# staging/ is the complete webroot bundle; rsync --delete never
# removes the page because the page is inside staging/.
echo "staged public files:"
ls staging
echo
echo "deploy targets (webroot layout, Caddy/nginx):"
echo "  staging first : rsync -av --delete staging/ user@vps:/srv/staging.firewisp.rtard.de/"
echo "  prod (tagged) : rsync -av --delete staging/ user@vps:/srv/app.firewisp.rtard.de/"
echo "live verify: curl -fsSLI https://staging.firewisp.rtard.de/wisp-${VERSION}.apk | grep -iE 'content-type|content-disposition|strict-transport'"
