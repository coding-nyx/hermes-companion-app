#!/usr/bin/env bash
# Shared autostart for the companion relay. Sourced by install.sh / install-services.sh.
# Linux → systemd --user. Darwin → LaunchAgents. No-op with instructions otherwise.

hc_os() {
  case "$(uname -s)" in
    Darwin) echo darwin ;;
    Linux) echo linux ;;
    *) echo other ;;
  esac
}

hc_xml() {
  printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

hc_python() {
  if [ -n "${HERMES_VENV:-}" ] && [ -x "$HERMES_VENV/bin/python" ]; then
    printf '%s\n' "$HERMES_VENV/bin/python"
    return
  fi
  command -v python3 || command -v python
}

hc_detect_venv() {
  if [ -n "${HERMES_VENV:-}" ] && [ -x "$HERMES_VENV/bin/python" ]; then
    return
  fi
  HERMES_VENV=""
  local cand
  for cand in "${HERMES_HOME:-$HOME/.hermes}/hermes-agent/.venv" "${HERMES_HOME:-$HOME/.hermes}/hermes-agent/venv"; do
    if [ -x "$cand/bin/python" ]; then
      HERMES_VENV="$cand"
      return
    fi
  done
}

hc_dashboard_args() {
  # Prints argv lines for the dashboard process (or nothing if we cannot start it).
  hc_detect_venv
  if [ -n "${HERMES_VENV:-}" ] && [ -x "$HERMES_VENV/bin/python" ]; then
    printf '%s\n' "$HERMES_VENV/bin/python" -m hermes_cli.main dashboard --host 127.0.0.1 --port 9119 --no-open --skip-build
    return 0
  fi
  if command -v hermes >/dev/null 2>&1; then
    printf '%s\n' "$(command -v hermes)" dashboard --host 127.0.0.1 --port 9119 --no-open --skip-build
    return 0
  fi
  return 1
}

hc_write_launchd_plist() {
  local plist="$1" label="$2" workdir="$3" logfile="$4"
  shift 4
  local env_xml="" args_xml=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --) shift; break ;;
      *=*)
        env_xml="${env_xml}    <key>$(hc_xml "${1%%=*}")</key>
    <string>$(hc_xml "${1#*=}")</string>
"
        shift
        ;;
      *) break ;;
    esac
  done
  while [ $# -gt 0 ]; do
    args_xml="${args_xml}    <string>$(hc_xml "$1")</string>
"
    shift
  done
  mkdir -p "$(dirname "$plist")" "$(dirname "$logfile")"
  cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$(hc_xml "$label")</string>
  <key>ProgramArguments</key>
  <array>
${args_xml}  </array>
  <key>WorkingDirectory</key>
  <string>$(hc_xml "$workdir")</string>
  <key>EnvironmentVariables</key>
  <dict>
${env_xml}  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>3</integer>
  <key>StandardOutPath</key>
  <string>$(hc_xml "$logfile")</string>
  <key>StandardErrorPath</key>
  <string>$(hc_xml "$logfile")</string>
</dict>
</plist>
EOF
}

hc_launchctl_reload() {
  local label="$1" plist="$2"
  local uid domain
  uid="$(id -u)"
  domain="gui/${uid}"
  launchctl bootout "${domain}/${label}" 2>/dev/null || true
  if ! launchctl bootstrap "$domain" "$plist" 2>/dev/null; then
    launchctl unload "$plist" 2>/dev/null || true
    launchctl load -w "$plist"
  fi
  launchctl enable "${domain}/${label}" 2>/dev/null || true
  launchctl kickstart -k "${domain}/${label}" 2>/dev/null || true
}

hc_darwin_path() {
  local extra=""
  [ -n "${HERMES_VENV:-}" ] && extra="${HERMES_VENV}/bin:"
  printf '%s\n' "${extra}/opt/homebrew/bin:/usr/local/bin:${HOME}/.local/bin:/usr/bin:/bin"
}

hc_install_darwin_relay() {
  local plugin_dir="$1" hermes_home="$2" standalone="${3:-1}"
  local py agents logs label plist
  py="$(hc_python)" || { echo "python3 not found"; return 1; }
  agents="$HOME/Library/LaunchAgents"
  logs="$HOME/Library/Logs/hermes-companion"
  mkdir -p "$agents" "$logs"
  if [ "$standalone" = "0" ]; then
    label="ai.hermes.companion"
    # Proxy owns :9120 — drop the standalone agent if we installed it earlier.
    launchctl bootout "gui/$(id -u)/ai.hermes.companion-relay" 2>/dev/null || true
  else
    label="ai.hermes.companion-relay"
    launchctl bootout "gui/$(id -u)/ai.hermes.companion" 2>/dev/null || true
  fi
  plist="$agents/${label}.plist"
  hc_write_launchd_plist "$plist" "$label" "$plugin_dir" "$logs/relay.log" \
    "PATH=$(hc_darwin_path)" \
    "HERMES_HOME=$hermes_home" \
    "HERMES_WORKSPACE=$HOME" \
    "HERMES_COMPANION_STANDALONE=$standalone" \
    "PYTHONUNBUFFERED=1" \
    -- "$py" -u -m relay
  hc_launchctl_reload "$label" "$plist"
  echo "darwin launchd $label (:9120, standalone=$standalone)"
}

hc_install_darwin_dashboard() {
  local hermes_home="$1"
  local agents logs plist label line out
  local -a args=()
  out="$(hc_dashboard_args || true)"
  if [ -z "$out" ]; then
    echo "no hermes dashboard binary — relay stays standalone"
    return 1
  fi
  while IFS= read -r line; do
    [ -n "$line" ] && args+=("$line")
  done <<EOF
$out
EOF
  agents="$HOME/Library/LaunchAgents"
  logs="$HOME/Library/Logs/hermes-companion"
  label="ai.hermes.dashboard"
  plist="$agents/${label}.plist"
  hc_write_launchd_plist "$plist" "$label" "$hermes_home" "$logs/dashboard.log" \
    "PATH=$(hc_darwin_path)" \
    "HERMES_HOME=$hermes_home" \
    "VIRTUAL_ENV=${HERMES_VENV:-}" \
    "PYTHONUNBUFFERED=1" \
    -- "${args[@]}"
  hc_launchctl_reload "$label" "$plist"
  echo "darwin launchd $label (:9119)"
}

hc_linux_user_systemd() {
  command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1
}

hc_install_linux_unit() {
  local src="$1" dest_name="$2"
  local unit_dir="$HOME/.config/systemd/user"
  mkdir -p "$unit_dir"
  sed -e "s|@PYTHON@|$(hc_python)|g" \
      -e "s|@PLUGIN_DIR@|$3|g" \
      -e "s|@HERMES_HOME@|$4|g" \
      -e "s|@HOME@|$HOME|g" \
      -e "s|@VENV@|${HERMES_VENV:-}|g" \
      "$src" > "$unit_dir/$dest_name"
  systemctl --user daemon-reload
  systemctl --user enable --now "$dest_name"
  systemctl --user restart "$dest_name"
  echo "started $dest_name"
}

# After plugin files are copied: make sure :9120 is running the new code.
hc_ensure_relay() {
  local src="$1" dest="$2" hermes_home="$3"
  hc_detect_venv
  case "$(hc_os)" in
    linux)
      if hc_linux_user_systemd; then
        if systemctl --user is-enabled --quiet hermes-agent-companion.service 2>/dev/null \
           || systemctl --user is-active --quiet hermes-agent-companion.service 2>/dev/null; then
          systemctl --user restart hermes-agent-companion.service
          echo "restarted hermes-agent-companion (:9120)"
        else
          hc_install_linux_unit "$src/systemd/hermes-companion-relay.service" \
            hermes-companion-relay.service "$dest" "$hermes_home"
        fi
        return
      fi
      echo "no user systemd — start relay: cd $dest && python3 -u -m relay"
      ;;
    darwin)
      if launchctl print "gui/$(id -u)/ai.hermes.companion" >/dev/null 2>&1; then
        hc_install_darwin_relay "$dest" "$hermes_home" 0
      else
        hc_install_darwin_relay "$dest" "$hermes_home" 1
      fi
      ;;
    *)
      echo "unsupported host OS $(uname -s) — start relay: cd $dest && python3 -u -m relay"
      ;;
  esac
}

# Boot-persistent dashboard (:9119) + relay (:9120) when we can; else standalone relay.
hc_install_boot_services() {
  local src="$1" dest="$2" hermes_home="$3"
  hc_detect_venv
  case "$(hc_os)" in
    linux)
      if ! hc_linux_user_systemd; then
        echo "systemd not available; start 'hermes dashboard --no-open' and 'python -m relay' another way"
        return 1
      fi
      if [ -z "${HERMES_VENV:-}" ]; then
        echo "no Hermes venv found under $hermes_home/hermes-agent (set HERMES_VENV)"
        return 1
      fi
      hc_install_linux_unit "$src/systemd/hermes-dashboard.service" \
        hermes-dashboard.service "$dest" "$hermes_home"
      sleep 2
      hc_install_linux_unit "$src/systemd/hermes-agent-companion.service" \
        hermes-agent-companion.service "$dest" "$hermes_home"
      loginctl enable-linger "$USER" 2>/dev/null || echo "run: sudo loginctl enable-linger $USER"
      sleep 2
      systemctl --user --no-pager is-enabled hermes-dashboard.service hermes-agent-companion.service || true
      systemctl --user --no-pager is-active hermes-dashboard.service hermes-agent-companion.service || true
      ;;
    darwin)
      if hc_install_darwin_dashboard "$hermes_home"; then
        sleep 2
        hc_install_darwin_relay "$dest" "$hermes_home" 0
      else
        hc_install_darwin_relay "$dest" "$hermes_home" 1
      fi
      echo "logs: $HOME/Library/Logs/hermes-companion/"
      echo "launchctl print gui/$(id -u)/ai.hermes.companion-relay"
      echo "launchctl print gui/$(id -u)/ai.hermes.companion"
      echo "launchctl print gui/$(id -u)/ai.hermes.dashboard"
      ;;
    *)
      echo "unsupported host OS $(uname -s)"
      return 1
      ;;
  esac
  curl -s -m 5 -o /dev/null -w "relay health: HTTP %{http_code}\n" http://127.0.0.1:9120/companion/health || true
  echo "phone origin: http://<this-host-tailscale-or-lan>:9120"
}
