#!/usr/bin/env bash
# Interactive ngit publisher for Fire Wisp (Android + Web).
# Prompts silently for nsec1... and stores it only in a chmod-600 RAM temp file
# for the duration of the command, wiping & removing it automatically on exit.
#
# Usage:
#   ./publish-ngit.sh            # publish both Android (wisp) and Web (firewisp-web)
#   ./publish-ngit.sh android    # publish Fire Wisp Android only
#   ./publish-ngit.sh web        # publish Fire Wisp Web only
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

if ! command -v ngit >/dev/null 2>&1; then
  echo "Error: ngit not found in PATH ($HOME/.local/bin/ngit)" >&2
  exit 1
fi

TARGET="${1:-all}"
if [[ "$TARGET" != "all" && "$TARGET" != "android" && "$TARGET" != "web" ]]; then
  echo "Usage: $0 [all|android|web]" >&2
  exit 2
fi

ANDROID_REPO="/home/mynameissteve/.zcode/workspace/default/wisp"
WEB_REPO="/home/mynameissteve/.zcode/workspace/default/firewisp-web"

TMP_DIR="/dev/shm"
[[ -d "$TMP_DIR" && -w "$TMP_DIR" ]] || TMP_DIR="${TMPDIR:-/tmp}"
KEYFILE="$(mktemp "$TMP_DIR/.ngit-key.XXXXXX")"
chmod 600 "$KEYFILE"

NSEC=""
cleanup() {
  unset NSEC
  if [[ -f "$KEYFILE" ]]; then
    dd if=/dev/zero of="$KEYFILE" bs=1 count=128 conv=notrunc 2>/dev/null || true
    rm -f "$KEYFILE"
  fi
}
trap cleanup EXIT

read -r -s -p "Enter Nostr nsec (nsec1...): " NSEC
echo ""

NSEC="$(printf '%s' "$NSEC" | tr -d '[:space:]')"
if [[ "$NSEC" != nsec1* ]]; then
  echo "Error: key must start with nsec1..." >&2
  exit 1
fi

printf '%s\n' "$NSEC" > "$KEYFILE"
unset NSEC

publish_android() {
  echo ""
  echo "=== Publishing Fire Wisp (Android) to Nostr via ngit ==="
  cd "$ANDROID_REPO"
  ngit --nsec-file "$KEYFILE" init \
    --name "Fire Wisp" \
    --identifier "firewisp" \
    --description "Privacy-hardened Android Nostr client with built-in Tor, sovereign auth, Blossom uploads, and Lightning zaps." \
    --web "https://app.firewisp.rtard.de" \
    --additional-clone "https://github.com/mptfire/wisp.git" \
    --additional-relay "wss://firewisp.relay.rtard.de" \
    --hashtag nostr \
    --hashtag android \
    --hashtag privacy \
    --hashtag tor \
    --hashtag lightning \
    --defaults
  echo ""
  echo "Fire Wisp (Android) remotes:"
  git remote -v | grep 'nostr://' || true
}

publish_web() {
  echo ""
  echo "=== Publishing Fire Wisp Web to Nostr via ngit ==="
  cd "$WEB_REPO"
  ngit --nsec-file "$KEYFILE" init \
    --name "Fire Wisp Web" \
    --identifier "firewisp-web" \
    --description "Privacy-hardened Nostr web & PWA client with sovereign auth, NIP-53 live streams, NIP-13 PoW, Blossom media, and Lightning zaps." \
    --web "https://firewisp.rtard.de" \
    --additional-clone "https://github.com/mptfire/firewisp-web.git" \
    --additional-relay "wss://firewisp.relay.rtard.de" \
    --hashtag nostr \
    --hashtag web \
    --hashtag pwa \
    --hashtag privacy \
    --defaults
  echo ""
  echo "Fire Wisp Web remotes:"
  git remote -v | grep 'nostr://' || true
}

case "$TARGET" in
  all)
    publish_android
    publish_web
    ;;
  android)
    publish_android
    ;;
  web)
    publish_web
    ;;
esac

echo ""
echo "Done! Your repositories are published on Nostr (browse on https://gitworkshop.dev)."
