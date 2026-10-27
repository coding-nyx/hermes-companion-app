#!/usr/bin/env bash
set -euo pipefail
bridge_src="$(cd "$(dirname "$0")" && pwd)"
bridge_dest="$HOME/.local/share/hermes-desk-bridge"
hermes_runtime="$HOME/.hermes/hermes-agent"
bridge_hermes_home="${DESK_HERMES_HOME:-$HOME/.hermes}"
bridge_ip="${DESK_BRIDGE_IP:-$(tailscale ip -4)}"
test -x "$hermes_runtime/venv/bin/python"
mkdir -p "$bridge_dest" "$HOME/.config/systemd/user"
cp "$bridge_src/server.py" "$bridge_dest/server.py"
cat > "$HOME/.config/systemd/user/hermes-desk-grok.service" <<UNIT
[Unit]
Description=Desk Companion native Grok OAuth broker
After=network-online.target hermes-dashboard.service

[Service]
WorkingDirectory=$hermes_runtime
Environment=PYTHONPATH=$hermes_runtime
Environment=HERMES_HOME=$bridge_hermes_home
Environment=DESK_BRIDGE_BIND=$bridge_ip:9121
ExecStart=$hermes_runtime/venv/bin/python $bridge_dest/server.py
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now hermes-desk-grok.service
systemctl --user restart hermes-desk-grok.service
echo "Desk Grok broker installed on the private Tailscale interface, port 9121."
