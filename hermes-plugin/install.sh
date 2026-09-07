#!/usr/bin/env bash
# Install hermes-companion into this machine's Hermes plugins dir.
# Starts :9120 via systemd (Linux) or launchd (macOS).
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=host_services.sh
. "$SRC/host_services.sh"
HOME_DIR="${HERMES_HOME:-$HOME/.hermes}"
DEST="$HOME_DIR/plugins/hermes-companion"
mkdir -p "$DEST"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude '__pycache__' --exclude 'tests' --exclude '.venv' "$SRC/" "$DEST/"
else
  rm -rf "$DEST"
  mkdir -p "$DEST"
  cp -R "$SRC/." "$DEST/"
fi
echo "copied plugin -> $DEST"
if command -v hermes >/dev/null 2>&1; then
  hermes plugins enable hermes-companion
  echo "enabled hermes-companion"
else
  echo "hermes CLI not on PATH — later run: hermes plugins enable hermes-companion"
fi

hc_ensure_relay "$SRC" "$DEST" "$HOME_DIR"

echo
echo "Next:"
echo "  1. Phone origin: http://<this-host-tailscale-or-lan>:9120"
echo "  2. Device tab PAIR, then: hermes companion approve CODE"
echo "  3. Optional dashboard+proxy (boot persistent): bash $DEST/install-services.sh"
