#!/usr/bin/env bash
# Interactive Zapstore publisher for Fire Wisp.
# Prompts silently for nsec1... (or bunker://...) without echoing to terminal or bash history.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

export PATH="$HOME/.local/bin:$PATH"

if ! command -v zsp >/dev/null 2>&1; then
  echo "Error: zsp not found in PATH ($HOME/.local/bin/zsp)" >&2
  exit 1
fi

SIGN_WITH=""
cleanup() {
  unset SIGN_WITH
}
trap cleanup EXIT

read -r -s -p "Enter Nostr signer (nsec1... or bunker://...): " SIGN_WITH
echo ""

SIGN_WITH="$(printf '%s' "$SIGN_WITH" | tr -d '[:space:]')"
if [[ "$SIGN_WITH" != nsec1* && "$SIGN_WITH" != bunker://* ]]; then
  echo "Error: signer must start with nsec1... or bunker://..." >&2
  exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]] && command -v gh >/dev/null 2>&1; then
  GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
  export GITHUB_TOKEN
fi

if [[ "${1:-}" == "--wizard" ]]; then
  shift
  echo "Launching Zapstore C1 proof & publish wizard in $REPO_ROOT ..."
  SIGN_WITH="$SIGN_WITH" zsp "$@"
else
  echo "Publishing Fire Wisp to Zapstore from $REPO_ROOT/zapstore.yaml ..."
  SIGN_WITH="$SIGN_WITH" zsp publish "$@"
fi
