# Hands engine

Optional Shizuku backend for Hands. This Android library contains no network listener,
agent SDK, Companion host connection, or public arbitrary-shell tool. The containing app owns
local consent, authentication, controller leases, protected apps and command serialization.

The app creates one application-scoped `ShizukuController(context)`, observes `status`, and
offers `requestPermission()` and `connect()` from local UI. Permission is never automatically
granted. Calls that touch the service must run on a worker thread.

```kotlin
val epoch = controller.beginSession() // Only after local approval and controller lease acquisition.
val displayId = controller.createAgentScreen(surface, 720, 1280, 240)
val result = controller.execute("device.open_app", JSONObject().put("package", "org.example.app")
    .put("_hands_session", epoch), displayId)
// When stopping/disarming:
controller.cancel()
controller.destroyAgentScreen()
```

`execute` returns `{ok:true,result:{…}}` or `{ok:false,error:{code,message}}`. Supported
operations: `device.snapshot`, `device.screenshot`, `device.click`, `device.swipe`,
`device.scroll`, `device.type`, `device.press`, `device.open_app` and `device.apps`.

- Every operation takes an explicit display ID. Display `0` means the phone; the only
  accepted nonzero ID is the service's currently owned agent display. Removed or invalid
  targets fail; there is no retry against the phone.
- The separate screen requires Android 14+ and a Shizuku shell/root with trusted-display
  permission. Its flags require independent focus and prevent stealing the phone's top
  focus. Unsupported focus flags are rejected. Own-content mode prevents mirroring the
  phone, and destroy-content mode prevents migrating agent activities onto the phone.
- The `Surface` remains valid for the screen's lifetime. The host should keep its overlay
  renderer in a foreground service. Closing it must destroy the display before releasing
  its own surface.
- Snapshots use `UiAutomation` with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` and inspect
  application windows on the selected display only. The client explicitly enables
  `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (absent from AOSP UiAutomation defaults), then waits at
  most 1.5 seconds for a visible root on that display. They return `snapshot_id` and `eN`
  refs. Reference clicks require the same snapshot ID and revalidate the target. Coordinate
  actions do not require an accessibility tree.
- Legacy swipes accept either four coordinates, `dx`/`dy` from the selected display's center,
  or `from_ref`/`to_ref` with a matching snapshot ID. Both refs are checked against one fresh
  tree. Scroll can target a ref and stays inside the node's visible screen bounds.
- Screenshot capture uses WindowManager's logical-display capture on Android 14+, with
  secure/protected-layer capture disabled. PNGs are bounded below the Binder IPC limit and
  include the actual encoded dimensions and display ID. Android 12–13 screenshot calls
  explicitly report unsupported; the host can select its Accessibility backend instead.
- `device.type` currently uses Android's virtual keymap and explicitly rejects Unicode,
  newlines and literal `%s`; it never claims those characters were typed. The host should
  use its Accessibility/IME backend for richer input.
- Some apps cannot run independent instances. A singleTask/singleInstance app with an
  existing main-display task is rejected before it could move that task to the agent screen.
  App links, OEM launchers, secure apps and OEM task rules can impose further limits.
- Commands use bounded `ProcessBuilder` argv, never a shell command string. The private
  binder accepts only its owning application UID. `cancel()` kills an in-flight Android
  input process; the containing app must invoke it on disarm and stop.
- Android's `input` shell command ignores the injector's delivery boolean. A successful
  input subprocess therefore returns `input_submitted:true,delivery_verified:false`;
  the agent must inspect a new observation to establish the actual outcome.
- Cancellation latches the engine off. A newly approved controller must call `beginSession`
  once, then add its trusted epoch as `_hands_session` after removing any caller-supplied key.
  Old tokens cannot start a process after cancellation, even if an earlier foreground check
  already completed. `cancelAsync()` is a one-way binder call safe for the local Stop button.
- Home/Recents are rejected on the agent display because Android/OEM policy can route those
  keys to the phone's global launcher/recents UI. Back and app launch remain display-targeted.

Dependencies are official Shizuku API/provider `13.1.5`, the current Maven Central release
when added. Relevant primary implementation references:

- [Shizuku UserService API](https://github.com/RikkaApps/Shizuku-API#userservice)
- [Android display flags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/hardware/display/DisplayManager.java)
- [Display focus semantics](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/view/Display.java)
- [WindowManager display capture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/WindowManagerService.java)

The debug-only `EngineProbe` and fixture Activity can validate the same privileged code
through `adb app_process` using an installed debug APK. It creates a temporary display,
launches only the synthetic fixture, verifies a targeted click and screenshot, checks the
primary app did not change and rejects an invalid display. It destroys the display before
exiting. This probe does not replace testing Shizuku's permission and bind lifecycle.
