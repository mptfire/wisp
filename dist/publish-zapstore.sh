#!/usr/bin/env bash
# Zapstore publisher for Fire Wisp (de.rtard.wisp).
#
# Modes:
#   ./dist/publish-zapstore.sh --proton [zsp args...]
#       Fetches the approval-gated NIP-46 bunker:// URI from Proton Pass
#       (vault: "Billy Proton L2", item: "Zapstore NIP-46 Bunker (Fire Wisp)")
#       via the [Agent] Pi PAT session. Rejects raw nsec1... keys, never echoes
#       or writes secrets to disk, and logs an audit reason to Proton Pass.
#
#   ./dist/publish-zapstore.sh --proton-check [zsp check args...]
#       Verifies Proton Pass retrieval + NIP-46 bunker pairing/approval via
#       `zsp check` without publishing a new release.
#
#   ./dist/publish-zapstore.sh [zsp args...]
#       Interactive mode: prompts silently for nsec1... or bunker://...
#       without echoing to terminal or bash history.
#
#   ./dist/publish-zapstore.sh --wizard [zsp args...]
#       Launches the interactive Zapstore C1 proof & setup wizard.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

export PATH="$HOME/.local/bin:$PATH"

if ! command -v zsp >/dev/null 2>&1; then
  echo "Error: zsp not found in PATH ($HOME/.local/bin/zsp)" >&2
  exit 1
fi

PROTON_VAULT="${ZSP_PROTON_VAULT:-Billy Proton L2}"
PROTON_ITEM="${ZSP_PROTON_ITEM:-Zapstore NIP-46 Bunker (Fire Wisp)}"
PROTON_AGENT_ENV="${PROTON_PASS_AGENT_ENV:-$HOME/.config/proton-pass/agent-env.sh}"
AGENT_REASON="${PROTON_PASS_AGENT_REASON:-Zapstore NIP-46 bunker signing for Fire Wisp}"

SIGN_WITH=""
PROTON_PASS_PERSONAL_ACCESS_TOKEN=""
cleanup() {
  unset SIGN_WITH PROTON_PASS_PERSONAL_ACCESS_TOKEN
}
trap cleanup EXIT

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

  SIGN_WITH="$(
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

# Pre-filter unreachable or phone-local relays so zsp NIP-46 handshake does not stall.
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
  SIGN_WITH="$(printf '%s' "$SIGN_WITH" | tr -d '[:space:]')"
  if [[ "$SIGN_WITH" != bunker://* ]]; then
    echo "Error: Proton Pass item must contain a bunker:// URI (raw nsec1... keys are not allowed in --proton mode)." >&2
    exit 1
  fi
}

if [[ -z "${GITHUB_TOKEN:-}" ]] && command -v gh >/dev/null 2>&1; then
  GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
  export GITHUB_TOKEN
fi

MODE="interactive"
if [[ "${1:-}" == "--proton" ]]; then
  MODE="proton"
  shift
elif [[ "${1:-}" == "--proton-check" ]]; then
  MODE="proton-check"
  shift
elif [[ "${1:-}" == "--wizard" ]]; then
  MODE="wizard"
  shift
fi

case "$MODE" in
  proton)
    load_bunker_from_proton
    echo "Publishing Fire Wisp to Zapstore via Proton Pass NIP-46 bunker ($PROTON_VAULT / $PROTON_ITEM)..."
    echo "Watch Amber on your phone to approve the NIP-46 signing request(s)."
    SIGN_WITH="$SIGN_WITH" zsp publish "$@"
    ;;
  proton-check)
    load_bunker_from_proton
    echo "Checking Fire Wisp Zapstore setup via Proton Pass NIP-46 bunker ($PROTON_VAULT / $PROTON_ITEM)..."
    echo "Watch Amber on your phone if it prompts to approve the NIP-46 connection."
    SIGN_WITH="$SIGN_WITH" zsp check "$@"
    ;;
  wizard)
    read -r -s -p "Enter Nostr signer (nsec1... or bunker://...): " SIGN_WITH
    echo ""
    SIGN_WITH="$(printf '%s' "$SIGN_WITH" | tr -d '[:space:]')"
    if [[ "$SIGN_WITH" != nsec1* && "$SIGN_WITH" != bunker://* ]]; then
      echo "Error: signer must start with nsec1... or bunker://..." >&2
      exit 1
    fi
    echo "Launching Zapstore C1 proof & publish wizard in $REPO_ROOT ..."
    SIGN_WITH="$SIGN_WITH" zsp "$@"
    ;;
  interactive)
    read -r -s -p "Enter Nostr signer (nsec1... or bunker://...): " SIGN_WITH
    echo ""
    SIGN_WITH="$(printf '%s' "$SIGN_WITH" | tr -d '[:space:]')"
    if [[ "$SIGN_WITH" != nsec1* && "$SIGN_WITH" != bunker://* ]]; then
      echo "Error: signer must start with nsec1... or bunker://..." >&2
      exit 1
    fi
    echo "Publishing Fire Wisp to Zapstore from $REPO_ROOT/zapstore.yaml ..."
    SIGN_WITH="$SIGN_WITH" zsp publish "$@"
    ;;
esac
