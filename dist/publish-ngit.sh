#!/usr/bin/env bash
# Interactive ngit publisher for Fire Wisp (Android + Web).
# Prompts silently for nsec1... and stores it only in a chmod-600 RAM temp file
# for the duration of the command, wiping & removing it automatically on exit.
#
# Usage:
#   ./publish-ngit.sh [--use-login] [all|android|web]
#   ./publish-ngit.sh            # publish both Android (wisp) and Web (firewisp-web)
#   ./publish-ngit.sh android    # publish Fire Wisp Android only
#   ./publish-ngit.sh web        # publish Fire Wisp Web only
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

if ! command -v ngit >/dev/null 2>&1; then
  echo "Error: ngit not found in PATH ($HOME/.local/bin/ngit)" >&2
  exit 1
fi

USE_LOGIN=false
if [[ "${1:-}" == "--use-login" ]]; then
  USE_LOGIN=true
  shift
fi

TARGET="${1:-all}"
if [[ "$TARGET" != "all" && "$TARGET" != "android" && "$TARGET" != "web" ]] || [[ $# -gt 1 ]]; then
  echo "Usage: $0 [--use-login] [all|android|web]" >&2
  exit 2
fi

ANDROID_REPO="/home/mynameissteve/.zcode/workspace/default/wisp"
WEB_REPO="/home/mynameissteve/.zcode/workspace/default/firewisp-web"
CANONICAL_RELAY="wss://relay.firewisp.de"
LEGACY_RELAY="wss://firewisp.relay.rtard.de"

KEYFILE=""
NSEC=""
NGIT_AUTH_ARGS=()
cleanup() {
  unset NSEC
  if [[ -n "$KEYFILE" && -f "$KEYFILE" ]]; then
    dd if=/dev/zero of="$KEYFILE" bs=1 count=128 conv=notrunc 2>/dev/null || true
    rm -f "$KEYFILE"
  fi
}
trap cleanup EXIT

if [[ "$USE_LOGIN" == false ]]; then
  TMP_DIR="/dev/shm"
  [[ -d "$TMP_DIR" && -w "$TMP_DIR" ]] || TMP_DIR="${TMPDIR:-/tmp}"
  KEYFILE="$(mktemp "$TMP_DIR/.ngit-key.XXXXXX")"
  chmod 600 "$KEYFILE"

  read -r -s -p "Enter Nostr nsec (nsec1...): " NSEC
  echo ""

  NSEC="$(printf '%s' "$NSEC" | tr -d '[:space:]')"
  if [[ "$NSEC" != nsec1* ]]; then
    echo "Error: key must start with nsec1..." >&2
    exit 1
  fi

  printf '%s\n' "$NSEC" > "$KEYFILE"
  unset NSEC
  NGIT_AUTH_ARGS=(--nsec-file "$KEYFILE")
fi

publish_repo() {
  local repo_dir="$1"
  local name="$2"
  local identifier="$3"
  local description="$4"
  local web_url="$5"
  local clone_url="$6"
  shift 6
  local hashtags=("$@")

  echo ""
  echo "=== Publishing $name to Nostr via ngit ==="
  cd "$repo_dir"

  local repo_json state_flags is_nostr_repo has_legacy has_canonical
  repo_json="$(ngit repo --json --offline 2>/dev/null || echo '{}')"
  state_flags="$(
    printf '%s' "$repo_json" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    d = {}
relays = [r.rstrip("/") for r in (d.get("relays") or [])]
is_nostr = "true" if d.get("is_nostr_repo") is True else "false"
has_legacy = "true" if "wss://firewisp.relay.rtard.de" in relays else "false"
has_canonical = "true" if "wss://relay.firewisp.de" in relays else "false"
print(is_nostr, has_legacy, has_canonical)
'
  )"
  read -r is_nostr_repo has_legacy has_canonical <<<"$state_flags"

  if [[ "$is_nostr_repo" == "true" ]]; then
    local edit_args=(
      repo edit
      --name "$name"
      --description "$description"
      --web "$web_url"
    )
    if [[ "$has_legacy" == "true" ]]; then
      edit_args+=(--remove-additional-relay "$LEGACY_RELAY")
    fi
    if [[ "$has_canonical" != "true" ]]; then
      edit_args+=(--add-additional-relay "$CANONICAL_RELAY")
    fi
    ngit "${NGIT_AUTH_ARGS[@]}" "${edit_args[@]}"
    ngit "${NGIT_AUTH_ARGS[@]}" sync --trust-server
  else
    local init_args=(
      init
      --name "$name"
      --identifier "$identifier"
      --description "$description"
      --web "$web_url"
      --additional-clone "$clone_url"
      --additional-relay "$CANONICAL_RELAY"
    )
    local tag
    for tag in "${hashtags[@]}"; do
      init_args+=(--hashtag "$tag")
    done
    init_args+=(--defaults)
    ngit "${NGIT_AUTH_ARGS[@]}" "${init_args[@]}"
  fi

  echo ""
  echo "$name remotes:"
  git remote -v | grep 'nostr://' || true
}

publish_android() {
  publish_repo \
    "$ANDROID_REPO" \
    "Fire Wisp" \
    "firewisp" \
    "Privacy-hardened, zero-Google Android Nostr client with built-in Tor, NIP-46 bunker & multi-signer auth, Citrine local relay, Blossom uploads, and Lightning zaps." \
    "https://app.firewisp.de" \
    "https://github.com/mptfire/wisp.git" \
    nostr android privacy tor lightning
}

publish_web() {
  publish_repo \
    "$WEB_REPO" \
    "Fire Wisp Web" \
    "firewisp-web" \
    "Privacy-hardened Nostr web & PWA client with sovereign auth, NIP-53 live streams, NIP-13 PoW, Blossom media, and Lightning zaps." \
    "https://firewisp.de" \
    "https://github.com/mptfire/firewisp-web.git" \
    nostr web pwa privacy
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
