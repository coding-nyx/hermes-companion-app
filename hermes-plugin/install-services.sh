#!/usr/bin/env bash
# Boot-persistent dashboard (:9119) + companion relay (:9120).
# Linux: systemd --user units. macOS: LaunchAgents. Idempotent.
#
#   bash ~/.hermes/plugins/hermes-companion/install-services.sh
#
# Env: HERMES_HOME (default ~/.hermes), HERMES_VENV (auto-detects .venv or venv under hermes-agent).
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=host_services.sh
. "$SRC/host_services.sh"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
DEST="$HERMES_HOME/plugins/hermes-companion"
hc_install_boot_services "$SRC" "$DEST" "$HERMES_HOME"
