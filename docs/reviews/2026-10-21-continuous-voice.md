# Continuous voice — implementation and repaired rollout, 2026-10-21

Voice host support now ships in **Companion plugin 1.0.0**, through Hermes's supported
native dashboard plugin API loader. Lab's tracked Hermes source files are restored
to their original versions. Clean installs and upgrades do not receive source patches.
The earlier native overlay installer has been removed from this repository.

The screenshot's failure occurred after successful transcription and an agent reply.
The configured Edge client still emitted the obsolete v1 service protocol. The plugin
now uses pinned, unmodified upstream v2 protocol code for affected installed clients,
with source attribution and LGPL license included. The provider, endpoint, configured
voice and credentials are unchanged; no Python environment was modified. Newer v2
clients are left alone.

## Current deployment and update behavior

- Lab: plugin, relay and managed dashboard updated and running. The installer also
  enables Companion in the machine default and active Knight profile, preserving
  speech settings, credentials and other plugins. Native API enablement/auth gates
  remain active. The dashboard service explicitly launches the default profile.
- Canonical API: `/api/plugins/hermes-companion/audio/*`. The relay retains
  `/api/audio/*` aliases for existing phone clients; new clients also discover the
  plugin namespace directly on clean native hosts.
- Hermes updates/reinstalls that preserve `$HERMES_HOME/plugins` and configuration
  retain Companion support. Removing the entire Hermes data directory requires
  reinstalling/enabling Companion and pairing again as usual.
- Plugin reinstall/update carries all settings, snapshots, transport adapters and
  Edge compatibility code. Its installer reloads the relay and an existing managed
  dashboard. Disabling/uninstalling and restarting removes the runtime adapters.
- A changed native handler contract produces a compatibility error rather than
  rewriting Hermes. This does not promise compatibility with arbitrary future
  Hermes APIs; the native contract tests must pass when updating that integration.
- Recognized old overlay artifacts are archived under
  `$HERMES_HOME/state/companion-voice-legacy`. Migration refuses unrecognized source
  edits before restoring either tracked core file.
- Raj is online on Tailscale, but SSH to `100.86.138.6:22` resets from both the local
  Mac and lab. Its deployment has not been completed; an accessible SSH route is needed.

## Verification that now exercises the failure

- **Real lab Edge synthesis:** 25,920 encoded audio bytes returned in about 1.05 s.
- **Real relay stream:** complete 24 kHz mono PCM stream, 231,552 bytes, with normal
  start/end frames; no fallback or provider switch.
- **Real S22 provider test:** passed in 10.302 s. The phone synthesized a test phrase
  on Edge, transcribed it with the configured local STT, then streamed another reply
  through the actual `StreamingHostSpeechOutput` and Android AudioTrack using a
  profile-bound call snapshot. Android playback started and drained successfully.
  The test used temporary loopback forwarding and did not change the saved host.
- 456 Android unit/render/persistence/contract regressions pass (app 279, remote
  122, local 35, device feature 20); Desk compiles; lint has no errors/fatals.
- All 191 Companion plugin unit tests pass, including installer behavior. Seven
  native tests pass through Hermes's runner: plugin discovery, authentication and
  disablement, profile isolation/secret exclusion, allowlisted writes, frozen
  settings through HTTP/WebSocket/Live adapters, v1→v2 protocol wiring, compatibility
  rejection, and the existing native profile-scope regression tests.
- Earlier S22 lifecycle checks passed actual microphone ownership, mute retained
  through recreation, resume, 61 seconds of background silence and teardown. Three
  local WebRTC SDK/data-channel/lifecycle checks passed. No claim of live provider
  realtime acceptance is based on those local transport tests.

## Remaining acceptance

Realtime is implemented but unavailable on Knight because no host OpenAI credential
is configured. No key was provisioned or paid Live session started.

The successful provider/playback test repairs the observed speech-output failure.
It does not prove zero acoustic echo, human barge-in p95 below 300 ms, every wired/
Bluetooth route, long-running tools/approvals, network recovery, incoming telephone
calls, or the complete locked-screen experience. Those spoken acceptance checks
remain outstanding. An earlier test tried recreation after the phone entered doze
and timed out waiting for RESUMED; recreation before the background interval passed.

## Installed APK

`app/build/outputs/apk/debug/app-debug.apk`, ARM64 debug, installed with replacement
on Samsung S22 (`SM_S901E`, owner user 0), preserving data.

SHA-256: `701b6fd79eba365921a6162da235b1ff35e8a8ad0e4cff427ccede3ab6240e91`.

The installed `base.apk` checksum matches the value above and
`WRITE_SECURE_SETTINGS` remains granted after the final device run. Temporary test
forwarding is removed. The separate work-profile user 150 is unchanged.
