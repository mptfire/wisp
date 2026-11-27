#!/usr/bin/env bash
# ngit publisher for Fire Wisp (Android + Web).
#
# Modes:
#   ./dist/publish-ngit.sh --proton [--relogin] [all|android|web]
#       Pairs/uses a persistent Amber NIP-46 bunker profile ("amber-firewisp")
#       backed by Proton Pass ("Billy Proton L2" / "ngit NIP-46 Bunker (Fire Wisp)"),
#       never exposing raw nsec1... keys, then runs `ngit repo edit` + `ngit sync --trust-server`.
#
#   ./dist/publish-ngit.sh --proton-check [--relogin]
#       Verifies Proton Pass retrieval and Amber NIP-46 pairing via `ngit account whoami`.
#
#   ./dist/publish-ngit.sh --use-login [all|android|web]
#       Uses an already configured `ngit account login` session.
#
#   ./dist/publish-ngit.sh [all|android|web]
#       Interactive mode: prompts silently for nsec1... in /dev/shm RAM and wipes it on exit.
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

if ! command -v ngit >/dev/null 2>&1; then
  echo "Error: ngit not found in PATH ($HOME/.local/bin/ngit)" >&2
  exit 1
fi

MODE="interactive"
RELOGIN=false
TARGET=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --proton)
      MODE="proton"
      shift
      ;;
    --proton-check)
      MODE="proton-check"
      shift
      ;;
    --use-login)
      MODE="use-login"
      shift
      ;;
    --relogin)
      RELOGIN=true
      shift
      ;;
    all|android|web)
      if [[ -n "$TARGET" ]]; then
        echo "Usage: $0 [--proton [--relogin] | --proton-check [--relogin] | --use-login] [all|android|web]" >&2
        exit 2
      fi
      TARGET="$1"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--proton [--relogin] | --proton-check [--relogin] | --use-login] [all|android|web]"
      exit 0
      ;;
    *)
      echo "Usage: $0 [--proton [--relogin] | --proton-check [--relogin] | --use-login] [all|android|web]" >&2
      exit 2
      ;;
  esac
done

TARGET="${TARGET:-all}"

ANDROID_REPO="/home/mynameissteve/.zcode/workspace/default/wisp"
WEB_REPO="/home/mynameissteve/.zcode/workspace/default/firewisp-web"
CANONICAL_RELAY="wss://relay.firewisp.de"
LEGACY_RELAY="wss://firewisp.relay.rtard.de"
GRASP_SERVER="git.firewisp.de"

PROTON_VAULT="${NGIT_PROTON_VAULT:-Billy Proton L2}"
PROTON_ITEM="${NGIT_PROTON_ITEM:-ngit NIP-46 Bunker (Fire Wisp)}"
PROTON_AGENT_ENV="${PROTON_PASS_AGENT_ENV:-$HOME/.config/proton-pass/agent-env.sh}"
AGENT_REASON="${PROTON_PASS_AGENT_REASON:-Pairing ngit with Amber NIP-46 bunker for Fire Wisp}"
NGIT_PROFILE="${NGIT_PROTON_PROFILE:-amber-firewisp}"

KEYFILE=""
NSEC=""
BUNKER_URI=""
PROTON_PASS_PERSONAL_ACCESS_TOKEN=""
NGIT_AUTH_ARGS=()
cleanup() {
  unset NSEC BUNKER_URI PROTON_PASS_PERSONAL_ACCESS_TOKEN
  if [[ -n "$KEYFILE" && -f "$KEYFILE" ]]; then
    dd if=/dev/zero of="$KEYFILE" bs=1 count=128 conv=notrunc 2>/dev/null || true
    rm -f "$KEYFILE"
  fi
}
trap cleanup EXIT

has_ngit_profile() {
  local whoami_json
  whoami_json="$(ngit account whoami --offline --json 2>/dev/null || echo '{}')"
  printf '%s' "$whoami_json" | python3 -c '
import json, sys

target = sys.argv[1]
text = sys.stdin.read()
dec = json.JSONDecoder()
idx = 0
d = {}
while idx < len(text):
    while idx < len(text) and text[idx].isspace():
        idx += 1
    if idx >= len(text):
        break
    try:
        obj, end = dec.raw_decode(text, idx)
        idx = end
        if isinstance(obj, dict) and "accounts" in obj:
            d = obj
    except json.JSONDecodeError:
        break

for bad in (d.get("unavailable_profiles") or []):
    if isinstance(bad, str) and bad == target:
        sys.exit(1)
    if isinstance(bad, dict) and target in (bad.get("profile"), bad.get("name")):
        sys.exit(1)

def matches_entry(obj):
    if isinstance(obj, str):
        return obj == target
    if isinstance(obj, dict):
        if target in (obj.get("profile"), obj.get("name"), obj.get("alias")):
            return True
        for key in ("profiles", "aliases", "signers"):
            vals = obj.get(key)
            if isinstance(vals, list) and any(matches_entry(v) for v in vals):
                return True
    return False

for acct in (d.get("accounts") or []):
    if matches_entry(acct):
        sys.exit(0)

sys.exit(1)
' "$NGIT_PROFILE"
}

load_bunker_from_proton() {
  if ! command -v pass-cli >/dev/null 2>&1; then
    echo "Error: pass-cli not found in PATH ($HOME/.local/bin/pass-cli)" >&2
    exit 1
  fi
  if [[ -f "$PROTON_AGENT_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$PROTON_AGENT_ENV"
  fi

  if ! pass-cli info >/dev/null 2>&1; then
    if [[ -n "${PROTON_PASS_PERSONAL_ACCESS_TOKEN:-}" ]]; then
      pass-cli logout --force >/dev/null 2>&1 || true
      pass-cli login --pat "$PROTON_PASS_PERSONAL_ACCESS_TOKEN" >/dev/null
      pass-cli info >/dev/null
    else
      echo "Error: No active pass-cli session and $PROTON_AGENT_ENV not found." >&2
      echo "Run 'pass-cli login' or restore $PROTON_AGENT_ENV, then retry." >&2
      exit 1
    fi
  fi

  local item_json
  item_json="$(
    PROTON_PASS_AGENT_REASON="$AGENT_REASON" \
      pass-cli item view \
        --vault-name "$PROTON_VAULT" \
        --item-title "$PROTON_ITEM" \
        --output json
  )"

  BUNKER_URI="$(
    printf '%s' "$item_json" | python3 -c '
import base64, concurrent.futures, json, os, re, socket, ssl, sys, urllib.parse

raw = json.load(sys.stdin)
item = raw.get("item", raw)
content_outer = item.get("content", item)
content = content_outer.get("content", content_outer)

candidates = []
if isinstance(content, dict):
    login = content.get("Login")
    if isinstance(login, dict):
        for k in ("password", "username"):
            v = login.get(k)
            if isinstance(v, str) and v.strip():
                candidates.append(v.strip())
    note = content.get("Note")
    if isinstance(note, dict):
        v = note.get("note")
        if isinstance(v, str) and v.strip():
            candidates.append(v.strip())
    custom = content.get("Custom")
    if isinstance(custom, dict):
        for sec in custom.get("sections", []) or []:
            for f in sec.get("fields", []) or []:
                for v in (f.get("content") or {}).values():
                    if isinstance(v, str) and v.strip():
                        candidates.append(v.strip())
    for extra in content_outer.get("extra_fields", []) or []:
        fc = extra.get("content") or {}
        for v in fc.values():
            if isinstance(v, str) and v.strip():
                candidates.append(v.strip())
    top_note = content_outer.get("note")
    if isinstance(top_note, str) and top_note.strip():
        candidates.append(top_note.strip())

pattern = re.compile(r"bunker://[^\s\"'\''<>]+")
uri = None
for text in candidates:
    m = pattern.search(text)
    if m:
        uri = m.group(0)
        break
if not uri:
    sys.exit(2)

# Pre-filter unreachable or phone-local relays so ngit NIP-46 handshake does not stall.
u = urllib.parse.urlparse(uri)
pairs = urllib.parse.parse_qsl(u.query, keep_blank_values=True)
relays = [v for k, v in pairs if k == "relay"]
other_pairs = [(k, v) for k, v in pairs if k != "relay"]

def is_loopback(url):
    h = (urllib.parse.urlparse(url).hostname or "").lower()
    return h in ("127.0.0.1", "localhost", "::1")

def probe_ws(url):
    ru = urllib.parse.urlparse(url)
    host = ru.hostname
    if not host:
        return False
    port = ru.port or (443 if ru.scheme == "wss" else 80)
    path = ru.path or "/"
    try:
        sock = socket.create_connection((host, port), timeout=2.5)
        try:
            if ru.scheme == "wss":
                sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
            sock.settimeout(2.5)
            key = base64.b64encode(os.urandom(16)).decode()
            req = (
                f"GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
                f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            )
            sock.sendall(req.encode())
            first = sock.recv(256).decode("utf-8", "replace").splitlines()[0]
            return "101" in first
        finally:
            sock.close()
    except Exception:
        return False

non_loopback = [r for r in relays if not is_loopback(r)]
if non_loopback:
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(8, len(non_loopback))) as ex:
        alive = [r for r, ok in zip(non_loopback, ex.map(probe_ws, non_loopback)) if ok]
    # Prefer fast LAN / standard NIP-46 relays ahead of caching relays (e.g. relay.primal.net).
    preferred = [r for r in alive if "relay.primal.net" not in r.lower()]
    chosen = preferred if preferred else (alive if alive else non_loopback)
    # Put private LAN relays first for lowest latency when on local network.
    chosen.sort(key=lambda r: (0 if (urllib.parse.urlparse(r).hostname or "").startswith(("192.168.", "10.", "172.")) else 1))
    rebuilt_q = urllib.parse.urlencode([("relay", r) for r in chosen] + other_pairs)
    uri = urllib.parse.urlunparse((u.scheme, u.netloc, u.path, "", rebuilt_q, ""))

print(uri)
'
  )" || {
    echo "Error: Could not find a bunker:// URI in Proton Pass item '$PROTON_ITEM' (vault: '$PROTON_VAULT')." >&2
    echo "Note: --proton mode strictly requires a NIP-46 bunker:// URI (raw nsec1... keys are rejected)." >&2
    exit 1
  }

  unset item_json
  BUNKER_URI="$(printf '%s' "$BUNKER_URI" | tr -d '[:space:]')"
  if [[ "$BUNKER_URI" != bunker://* ]]; then
    echo "Error: Proton Pass item must contain a bunker:// URI (raw nsec1... keys are not allowed in --proton mode)." >&2
    exit 1
  fi
}

ensure_proton_ngit_login() {
  if [[ "$RELOGIN" == false ]] && has_ngit_profile; then
    echo "Using existing paired ngit NIP-46 profile '$NGIT_PROFILE'."
  else
    load_bunker_from_proton
    echo "Pairing ngit profile '$NGIT_PROFILE' via Proton Pass NIP-46 bunker ($PROTON_VAULT / $PROTON_ITEM)..."
    echo "Watch Amber on your phone and approve the NIP-46 connection."
    ngit account login --bunker-url "$BUNKER_URI" --profile "$NGIT_PROFILE"
    unset BUNKER_URI
    if ! has_ngit_profile; then
      echo "Error: ngit profile '$NGIT_PROFILE' is not available after bunker login." >&2
      exit 1
    fi
  fi
  NGIT_AUTH_ARGS=(--signer "$NGIT_PROFILE")
}

case "$MODE" in
  proton)
    ensure_proton_ngit_login
    ;;
  proton-check)
    ensure_proton_ngit_login
    echo "=== ngit account whoami ==="
    ngit account whoami --json
    exit 0
    ;;
  use-login)
    NGIT_AUTH_ARGS=()
    ;;
  interactive)
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
    ;;
esac

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

  local repo_json state_flags is_nostr_repo has_legacy has_canonical has_grasp
  repo_json="$(ngit repo --json --offline 2>/dev/null || echo '{}')"
  state_flags="$(
    printf '%s' "$repo_json" | python3 -c '
import json, sys
text = sys.stdin.read()
dec = json.JSONDecoder()
idx = 0
d = {}
while idx < len(text):
    while idx < len(text) and text[idx].isspace():
        idx += 1
    if idx >= len(text):
        break
    try:
        obj, end = dec.raw_decode(text, idx)
        idx = end
        if isinstance(obj, dict) and "is_nostr_repo" in obj:
            d = obj
    except json.JSONDecodeError:
        break
relays = [r.rstrip("/") for r in (d.get("relays") or [])]
grasps = [g.rstrip("/").lower() for g in (d.get("grasp_servers") or [])]
is_nostr = "true" if d.get("is_nostr_repo") is True else "false"
has_legacy = "true" if "wss://firewisp.relay.rtard.de" in relays else "false"
has_canonical = "true" if "wss://relay.firewisp.de" in relays else "false"
has_grasp = "true" if "git.firewisp.de" in grasps else "false"
print(is_nostr, has_legacy, has_canonical, has_grasp)
'
  )"
  read -r is_nostr_repo has_legacy has_canonical has_grasp <<<"$state_flags"

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
    if [[ "$has_grasp" != "true" ]]; then
      edit_args+=(--add-grasp-server "$GRASP_SERVER")
    fi
    ngit --repo-relay-only "${NGIT_AUTH_ARGS[@]}" "${edit_args[@]}"
    ngit --repo-relay-only "${NGIT_AUTH_ARGS[@]}" sync --trust-server
  else
    local init_args=(
      init
      --name "$name"
      --identifier "$identifier"
      --description "$description"
      --web "$web_url"
      --grasp-server "$GRASP_SERVER"
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
