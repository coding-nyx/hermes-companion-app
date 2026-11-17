# Background connection and restart recovery

`StayConnectedService` is the operator-enabled persistent bridge for paired-agent device/operator
WebSockets, wake subscriptions and selected-app notification streaming. It runs only while **Stay
connected** or notification streaming is enabled, with an ongoing notification. It is declared as
`specialUse`, with its actual continuous bridge purpose documented in the manifest. This is distinct
from a finite upload, download or synchronization operation.

Android 15+ restricts `dataSync` services to six background hours per day and prohibits starting them
from `BOOT_COMPLETED`. That was an unsuitable declaration for an explicitly enabled continuous bridge.
`specialUse` requires its permission and a manifest subtype explaining an otherwise uncovered valid
use case. The OS can still stop or reject the service; stop callbacks must finish promptly and must
not create a restart loop. See the official [service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) and
[Android 15 boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed).

## Boot and package updates

`CompanionBootReceiver` handles `BOOT_COMPLETED` after the user unlocks and `MY_PACKAGE_REPLACED`.
It checks the saved explicit background opt-in before requesting the connection service. It does not
listen for locked boot, open the activity, acquire a screen hold, arm Hands, restore a previous arm
generation, or start a microphone/voice service. Pairing credentials remain in credential-protected
storage.

If Android or an OEM rejects the background start, Companion posts a **Resume Companion connection**
notification when notification permission is available. Its tap opens Companion; a visible activity
can restore the user's enabled background connection. There is no automatic retry around that
rejection. Android force-stop and manufacturer sleep restrictions can require the user to open the
app again; a boot receiver cannot guarantee recovery from those states.

## Battery optimization

An ongoing service notification and the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest permission do
not grant a Doze exemption. `mobile_power_state` reports the actual
`ignoring_battery_optimizations` value; `mobile_settings_open(page="battery_optimization")` opens the
system list. Any app-specific exemption request must be an explicit operator action, and its result
must be refreshed from `PowerManager.isIgnoringBatteryOptimizations` after returning from Settings.
Exemption permits network and partial wake-lock use during Doze, while other OS restrictions still
apply. See [Android's Doze guidance](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases).
