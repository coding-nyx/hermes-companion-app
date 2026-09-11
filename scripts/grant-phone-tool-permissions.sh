#!/usr/bin/env bash
# Grant the runtime permissions behind the phone tools to a sideloaded Companion build.
#
# Declaring a permission in the manifest is not enough: runtime permissions still need a
# grant, and `adb shell pm grant` is the practical route for a sideloaded debug APK (the
# in-app request flow is only wired up for the ones the UI already asks for).
#
# Usage:  bash scripts/grant-phone-tool-permissions.sh [package]
#
# Two classes of permission are NOT handled here, because adb cannot grant them:
#   * SPECIAL (user must flip a toggle in a Settings page) — notification policy / DND,
#     usage access, modify system settings, notification listener, accessibility.
#     The script prints the deep links for those at the end.
#   * HARD-RESTRICTED (SMS, call log) — the installer must allowlist them. The script
#     attempts them and reports clearly if the platform refuses.
set -uo pipefail
PKG="${1:-app.hermes.companion}"

if ! adb get-state >/dev/null 2>&1; then
  echo "no adb device. On the phone: Developer options -> Wireless debugging, then"
  echo "  adb connect <ip>:<port>   (the port rotates every time it is toggled)"
  exit 1
fi

# Runtime permissions the app actually uses today. Keep this list equal to what the manifest
# declares: granting a permission no code path uses is attack surface for zero capability.
# When a tool lands, add its permission to the manifest and to this list in the same change.
RUNTIME=(
  android.permission.RECORD_AUDIO
  android.permission.CAMERA
  android.permission.POST_NOTIFICATIONS
  # mobile_contacts_search
  android.permission.READ_CONTACTS
  # mobile_calls_place (dial needs nothing)
  android.permission.CALL_PHONE
)

# Hard-restricted (SMS, call log): the installer must allowlist these or the grant is refused
# however the user answers. `adb install` does allowlist them (verified 2026-09-09: granted=true
# with RESTRICTION_INSTALLER_EXEMPT), so a sideloaded build needs no default-SMS role.
# NOTE revoking one of these restarts the app, which drops the device lane for ~15s.
RESTRICTED=(
  # mobile_sms_list / mobile_sms_send / mobile_calls_log
  android.permission.READ_SMS
  android.permission.SEND_SMS
  android.permission.READ_CALL_LOG
)

ok=0; failed=0
grant() {
  if out="$(adb shell pm grant "$PKG" "$1" 2>&1)" && [ -z "$out" ]; then
    printf '  ok       %s\n' "$1"; ok=$((ok + 1))
  else
    printf '  FAILED   %s  -- %s\n' "$1" "$(printf '%s' "$out" | tr -d '\r' | head -1)"
    failed=$((failed + 1))
  fi
}

echo "granting runtime permissions to $PKG"
for p in "${RUNTIME[@]}"; do grant "$p"; done

if [ ${#RESTRICTED[@]} -gt 0 ]; then
  echo
  echo "attempting hard-restricted permissions (installer must have allowlisted these)"
  for p in "${RESTRICTED[@]}"; do grant "$p"; done
fi

echo
echo "granted=$ok failed=$failed"
echo
echo "Still needs a human on the phone (adb cannot grant these):"
echo "  accessibility (L1 lane)   adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS"
echo "  notification listener     adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
echo "  DND / notification policy adb shell am start -a android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
echo "  usage access              adb shell am start -a android.settings.USAGE_ACCESS_SETTINGS"
echo "  modify system settings    adb shell am start -a android.settings.action.MANAGE_WRITE_SETTINGS"
echo "  exact alarms (14+)        adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
echo
echo "verify with:  adb shell dumpsys package $PKG | grep -A1 'runtime permissions'"
