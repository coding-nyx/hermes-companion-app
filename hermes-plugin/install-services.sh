#!/usr/bin/env bash
# Install systemd *user* units so the Hermes dashboard (:9119) and the companion relay (:9120)
# come back after a reboot. Run as the user that runs Hermes. Idempotent.
#
#   bash ~/.hermes/plugins/hermes-companion/install-services.sh
#
# Env: HERMES_HOME (default ~/.hermes), HERMES_VENV (auto-detects .venv or venv under hermes-agent).
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
if [ -z "${HERMES_VENV:-}" ]; then
  for cand in "$HERMES_HOME/hermes-agent/.venv" "$HERMES_HOME/hermes-agent/venv"; do
    [ -x "$cand/bin/python" ] && HERMES_VENV="$cand" && break
  done
fi
[ -n "${HERMES_VENV:-}" ] || { echo "no Hermes venv found under $HERMES_HOME/hermes-agent (set HERMES_VENV)"; exit 1; }
command -v systemctl >/dev/null || { echo "systemd not available; start 'hermes dashboard --no-open' and 'python -m relay' another way"; exit 1; }

UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
for unit in hermes-dashboard.service hermes-agent-companion.service; do
  sed -e "s|@VENV@|$HERMES_VENV|g" -e "s|@HERMES_HOME@|$HERMES_HOME|g" -e "s|@HOME@|$HOME|g" \
    "$SRC/systemd/$unit" > "$UNIT_DIR/$unit"
  echo "wrote $UNIT_DIR/$unit"
done

# Retire hand-started copies so the units own the ports.
pkill -f "^$HERMES_VENV/bin/python[3]* .*hermes_cli.main dashboard" 2>/dev/null || true
pkill -f "^$HERMES_VENV/bin/python[3]* $HERMES_VENV/bin/hermes dashboard" 2>/dev/null || true
pkill -f "^python[3]* -u relay.py" 2>/dev/null || true

systemctl --user daemon-reload
systemctl --user enable --now hermes-dashboard.service
sleep 5
systemctl --user enable --now hermes-agent-companion.service
systemctl --user restart hermes-agent-companion.service
# Keep the user manager alive without a login session (needed for boot start).
loginctl enable-linger "$USER" 2>/dev/null || echo "run: sudo loginctl enable-linger $USER"

sleep 3
echo
systemctl --user --no-pager is-enabled hermes-dashboard.service hermes-agent-companion.service
systemctl --user --no-pager is-active  hermes-dashboard.service hermes-agent-companion.service
curl -s -m 5 -o /dev/null -w "relay -> dashboard: HTTP %{http_code}\n" http://127.0.0.1:9120/api/status || true
echo "phone origin: http://<this-host-tailscale-or-lan>:9120"
