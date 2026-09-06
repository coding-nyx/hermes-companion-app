# Companion stability, skill shipping, arm-first — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (preferred) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop S22 crashes on new-thread + voice, fix thread-list loading (lab partial / raj empty), ship hermes-companion skill with the plugin (+ AGENTS.md convention), and enforce arm-first mobile control (skill + prompt + optional broker/tools guard).

**Architecture:** Android operator UI talks to each Hermes host via host-scoped `DashboardClient` (RPC preferred, REST fallback). Device hands go through the hermes-plugin broker (`mobile_*` tools → relay → phone). Crashes are client Compose/Activity-Result bugs; thread-list bugs are client list merge + RPC-empty/auth asymmetries; arm-first is defense-in-depth on skill/prompt/guards without weakening fail-closed.

**Tech stack:** Kotlin / Compose / Coroutines (app), OkHttp DashboardClient, hermes-plugin Python (broker/tools/skill), adb sideload, plugin install to lab+raj.

**Repo:** `/home/pacman/Projects/hermes-companion-app` @ `main` ~`2fb2a30`  
**Machine:** arch (`b3b6fb3e-09ae-4aa4-b7a5-abe7201911bb`)  
**Phone:** `100.105.213.54:40371` (adb reachable 2026-09-06)  
**Gateways:** lab `http://100.85.151.99:9120` (proxy), raj `http://100.86.138.6:9120` (standalone)  
**Constraint:** PLAN FIRST — do not implement beyond log capture + this plan until Nyx nods. Do **not** restart gateways (separate task). Cloud Agents unavailable.

---

## Investigation snapshot (2026-09-06 IST)

### A) Crashes — captured on device (not hypotheses)

| Symptom | Exception | Entry points |
|---|---|---|
| New thread | `IllegalArgumentException: Key "sess-default-…" was already used` (LazyColumn) | `ThreadsScreen.kt` `items(sessions, key = { it.id })` ← `ChatSessionManager.newThread()` does `listOf(created) + it.sessions` **without dedupe** → `MainActivity` `onNewThread = vm::newThread` / `CompanionShell` → `ThreadsScreen.onNew` |
| Voice button | `IllegalArgumentException: Can only use lower 16 bits for requestCode` at `MainActivity.kt:389` | Composer mic / stream toggle → `audioLauncher.launch(RECORD_AUDIO)` via `rememberLauncherForActivityResult` inside `setContent` (many launchers: notify/audio/photo/video/file/camera/takePicture) |

Logcat path (phone reachable):

```bash
adb connect 100.105.213.54:40371
adb -s 100.105.213.54:40371 logcat -c
# repro crash
adb -s 100.105.213.54:40371 logcat -d -b crash -t 200
adb -s 100.105.213.54:40371 logcat -d '*:E' | grep -E 'AndroidRuntime|app.hermes'
```

### B) Threads loading — quick live probes

| Host | `/companion/health` | Unauthed `GET /api/sessions?profile=default` | Notes |
|---|---|---|---|
| lab `:9120` | proxy, upstream reachable | **401 Unauthorized** (uvicorn) | REST needs host session token/cookie; RPC may still return a short list |
| raj `:9120` | standalone | **200** with **3** sessions (`default`) | `coder` profile reports `session_count: 0` |

**Primary code asymmetry:** `DashboardClient.listSessions` returns any non-null RPC result (including `[]`) and never REST-falls-back on empty — unlike `pageMessages` (A18.8 empty-RPC → REST). There is `emptyRpcHistoryFallsBackToRest` but **no** `emptyRpcSessionsFallsBackToRest`.

**Hypotheses (verify in Phase 2):**

1. **Empty RPC accepted as truth** → rail shows few/none even when REST has rows (especially lab proxy + raj WS edge cases).
2. **Lab partial** → RPC `session.list` limit/page returns a short page; REST never merged because RPC was non-empty.
3. **Raj empty** → sticky profile is `coder` (0 sessions) while `default` has 3; and/or gated auth / wrong origin key / empty cache treating failure as idle.
4. **Host-scoped auth** → lab 401 without bearer; token/cookie must stay in `HostClientPool` for that origin only (A8.5). Cross-host reuse → empty/401.
5. **Duplicate ids in list** (same as crash A) → create + bus refetch race can both insert the same `sess-default-*`.

### C) Skill shipping (current)

- Skill already exists: `hermes-plugin/skills/hermes-companion/SKILL.md` (registered in `hermes-plugin/__init__.py` `register()` via `ctx.register_skill`).
- No project `AGENTS.md` yet — need shipping convention doc.
- Skill already describes arm → snapshot → gesture → disarm; needs sharpening for **arm-first before long work**, multi-device, protected packages, fail-closed.

### D) Arm-first (current)

| Layer | Today |
|---|---|
| Skill | Order includes `mobile_arm` before gestures; can be stronger (“arm before any control, before long reasoning”) |
| System prompt | `_prompt()` in `__init__.py` — `armed_hint` only |
| Broker | Non-META actions already require `device.armed` (`META = {arm, disarm}`); `mobile_status` / `mobile_devices` bypass dispatch armed check |
| Tools | No auto-arm; gestures fail with `disarmed` |

---

## Architecture / files map

```
app/.../MainActivity.kt              # voice requestCode / permission launchers
app/.../ChatSessionManager.kt        # newThread prepend without distinctBy
app/.../CompanionViewModel.kt        # newThread / voice / selectProfile / connect load
app/.../CompanionShell.kt            # wires onNewThread, onVoiceClick
feature-threads/.../ThreadsScreen.kt # LazyColumn key = session.id
feature-chat/.../ChatScreen.kt       # mic clickable → onVoiceClick
data-remote/.../DashboardClient.kt   # listSessions RPC vs REST; createSession
data-remote/.../ProfileJson.kt       # parseSessions / stampProfile
data-remote/.../HostClientPool.kt    # host-scoped clients/tokens
data-remote/.../DashboardClientTest.kt
hermes-plugin/skills/hermes-companion/SKILL.md
hermes-plugin/__init__.py            # register_skill + system prompt
hermes-plugin/tools.py               # mobile_* handlers + armed_hint
hermes-plugin/broker.py              # armed gate / META / protected
AGENTS.md (new) or docs/AGENTS.md    # skill ships with plugin
docs/protocol/mobile-control.md      # optional cross-link
```

---

## Phase 0 — Restarts (out of scope here)

- [ ] **Already in flight separately.** Do not restart lab/raj gateways in this plan’s implementation turns.
- [ ] After those restarts land, re-check `/companion/health` on lab+raj before Phase 2 live verifies.

**Test:** `curl -sS http://100.85.151.99:9120/companion/health` and raj twin → `relay: ok`.

---

## Phase 1 — Capture crash logcats + root-cause crashes

### Task 1.1 — Reproduce & archive crash buffers

- [ ] Clear and capture crash logcat while tapping **NEW** and **mic / stream**:

```bash
adb -s 100.105.213.54:40371 logcat -c
# repro on phone
adb -s 100.105.213.54:40371 logcat -d -b crash > /tmp/companion-crash-$(date +%Y%m%d).txt
```

- [ ] Confirm still matching:
  - duplicate LazyColumn key on new thread
  - `requestCode` overflow on voice permission launch

**Test:** File contains both signatures; note IST timestamps.

### Task 1.2 — Fix new-thread duplicate LazyColumn keys

**Files:** `ChatSessionManager.kt`, optionally `ThreadsScreen.kt`, unit/UI test if present.

- [ ] In `newThread()`, when merging created session into state, **dedupe by id** (created wins / prepend once), e.g. `listOf(created) + it.sessions.filterNot { it.id == created.id }`.
- [ ] Audit other session merges (`SyncManager` bus patch, connect load, profile switch) for the same prepend-without-dedupe pattern.
- [ ] Optional belt: `ThreadsScreen` `items(sessions.distinctBy { it.id }, key = { it.id })` so a bad list cannot kill the process.
- [ ] Ensure `createSession` parse cannot yield blank/duplicate ids that collide with placeholders.

**Test:**
- Unit: merge helper keeps single row when id already present.
- Device: tap NEW 5× rapidly on raj + lab — no crash; rail shows unique rows; open chat works.

### Task 1.3 — Fix voice `requestCode` overflow

**Files:** `MainActivity.kt` (primary).

- [ ] Root cause: Compose `rememberLauncherForActivityResult` permission launches hitting FragmentActivity 16-bit requestCode validation (`MainActivity.kt:389` stream path; also `:374` mic path).
- [ ] Prefer **one shared** `RequestPermission` launcher (or Activity-scoped `registerForActivityResult` in `onCreate`) for RECORD_AUDIO / CAMERA / POST_NOTIFICATIONS rather than many Compose-remembered permission launchers.
- [ ] Keep photo/video/file/takePicture contracts; ensure they are not re-registered every recomposition (stable `remember` keys / move to Activity).
- [ ] If mic already granted, path must not call `launch` (already true) — still verify stream toggle after deny/allow.

**Test:**
- Fresh install / revoke mic → tap mic → system prompt (no crash) → grant → dictate into draft.
- Revoke → tap stream toggle → prompt → grant → stream starts.
- With mic already granted → both buttons work without launching permission.

### Task 1.4 — Build + sideload crashfix APK

- [ ] `./gradlew :app:assembleDebug` (or release signing path used for S22).
- [ ] `adb -s 100.105.213.54:40371 install -r <apk>`
- [ ] Smoke Task 1.2 + 1.3; save new logcat showing **no** FATAL for those actions.

**Success criteria (Phase 1):** New thread and voice/stream never crash; logcat clean for those gestures.

---

## Phase 2 — Fix thread list loading (lab partial + raj empty)

### Task 2.1 — Confirm live matrices (after Phase 0 restarts)

- [ ] With a valid host token (from phone store or operator login), compare for each host × sticky profile:

| Check | lab | raj |
|---|---|---|
| `GET /api/sessions?profile=<sticky>` REST count | | |
| RPC `session.list` count (via connected WS / mock) | | |
| App rail count | | |
| Profiles list + sticky id | | |

- [ ] Unauthed baseline already known: lab REST 401; raj REST 200 / 3 on `default`, 0 on `coder`.

**Test:** Write counts into NOTES or PR body; identify which hypothesis matched.

### Task 2.2 — Empty / short RPC → REST fallback for `listSessions`

**Files:** `DashboardClient.kt`, `DashboardClientTest.kt`.

- [ ] Mirror A18.8 policy: if RPC `session.list` returns `null` **or empty list**, fall through to `listSessionsRest`.
- [ ] Decide merge policy if RPC returns **non-empty but short** vs REST longer (prefer REST union by id, or “if rpc.size < rest.size use rest”, or always prefer REST for catch-up list). Document choice in code comment.
- [ ] Keep `stampProfile` / foreign-row filter.
- [ ] Add tests: `emptyRpcSessionsFallsBackToRest`, optionally `shortRpcSessionsPrefersRicherRest`.

**Test:** `./gradlew :data-remote:test --tests '*DashboardClientTest*'`; mock WS empty `session.list` + REST with N rows → client returns N.

### Task 2.3 — Empty-state vs auth vs wrong-profile UX

**Files:** `CompanionViewModel.kt`, threads chrome, sticky profile.

- [ ] On raj: if sticky profile is `coder` with 0 sessions, rail should show true empty for that profile **and** make profile switch obvious (not “broken gateway”).
- [ ] On lab: surface auth/401 distinctly from empty (`sessions failed` + RETRY), not `NO SESSIONS // idle`.
- [ ] Verify `HostClientPool` token for lab origin is attached on REST; no hub token reused.

**Test:** Switch default ↔ coder on raj; disconnect/reconnect lab with saved creds; rail matches REST counts.

### Task 2.4 — Deduped session list as loading hygiene

- [ ] After any `listSessions` / bus refetch, store `distinctBy { it.id }` ordered by `updatedAtEpochMs` (ties stable). Prevents Phase 1 crash class from server duplicates.

**Success criteria (Phase 2):** Lab shows full session set for sticky profile; raj shows `default`’s sessions (not falsely empty); empty profile is honest; unit tests cover RPC-empty → REST.

---

## Phase 3 — Skill + AGENTS.md shipping convention

### Task 3.1 — Expand `hermes-plugin/skills/hermes-companion/SKILL.md`

- [ ] Keep frontmatter; tighten **Always this order** to mandating:
  1. `mobile_status` / `mobile_devices`
  2. `mobile_select_device` when multi-device / ambiguous
  3. **`mobile_arm` immediately** (before long planning text / other tools) so the phone does not lock while the agent thinks
  4. `mobile_snapshot` → gestures → re-snapshot
  5. `mobile_disarm` when done / on user stop
- [ ] Document fail-closed codes, protected packages, pair/approve CLI, multi-device `device=` param.
- [ ] Explicit: status/devices allowed while disarmed; control ops require armed.

### Task 3.2 — Add shipping convention doc

- [ ] Create `AGENTS.md` at repo root (or `docs/AGENTS.md` + root pointer) stating:
  - Skill path: `hermes-plugin/skills/hermes-companion/SKILL.md`
  - Skill updates **ship with plugin** changes (same PR / same deploy to lab+raj)
  - `register()` loads skill via `register_skill`; do not fork a second copy outside the plugin
  - When changing `mobile_*` behavior, update skill + `__init__._prompt` + broker guards together

### Task 3.3 — Wire/verify register path

- [ ] Confirm `__init__.py` still `register_skill` + `register_system_prompt_section`.
- [ ] Plugin tests still pass (`hermes-plugin/tests`).

**Test:** Open skill file from installed plugin path on a host after deploy; agent tool list includes companion skill.

**Success criteria (Phase 3):** Single canonical skill in plugin; AGENTS.md documents sync rule; content teaches arm-first + fail-closed correctly.

---

## Phase 4 — Arm-first workflow (defense in depth)

### Task 4.1 — Skill + prompt (required)

- [ ] Skill (Task 3.1) is source of agent procedure.
- [ ] Strengthen `__init__._prompt()` / `armed_hint()`: e.g. “Call `mobile_arm` before snapshot/gestures; arm early to avoid lock-screen during long replies.”

### Task 4.2 — Tools/broker guard (optional but preferred)

**Files:** `tools.py`, optionally `broker.py`, tests.

- [ ] Keep `mobile_status`, `mobile_devices`, `mobile_select_device`, `mobile_arm`, `mobile_disarm` usable when disarmed.
- [ ] For control tools (`snapshot`, `click`, `type`, `swipe`, `scroll`, `press`, `open_app`, `screenshot`, `wait` as applicable): either
  - **refuse-with-hint** (preferred first): return `disarmed` + hint “call mobile_arm first”, or
  - **auto-arm once** then proceed (only if product wants less friction; still fail on `a11y_unavailable`).
- [ ] Prefer refuse-with-hint first (matches fail-closed); auto-arm only if Nyx explicitly wants it after nod.
- [ ] Broker already blocks non-META when disarmed — ensure tool JSON errors stay stable for agents.

**Test:** `tests/test_broker.py` / companion extension tests — snapshot while disarmed → structured `disarmed` (+ hint); after arm → ok.

### Task 4.3 — Align docs/protocol

- [ ] Short note in `docs/protocol/mobile-control.md` pointing at skill order + arm-first rationale (lock screen / delayed agent response).

**Success criteria (Phase 4):** Agents are instructed and prompted to arm first; control ops cannot silently proceed disarmed; status/devices remain available.

---

## Phase 5 — Build / sideload / redeploy plugin + smoke

### Task 5.1 — App

- [ ] Assemble APK; sideload to S22 (`100.105.213.54:40371`).
- [ ] Smoke checklist:
  - [ ] Connect lab → threads count ≈ authenticated REST for sticky profile
  - [ ] Connect raj → `default` shows sessions; `coder` honest empty
  - [ ] NEW thread ×5 — no crash; unique keys
  - [ ] Mic + stream permission paths — no crash
  - [ ] Open oldest/imported thread — history still REST-falls-back (A18.8 regression)

### Task 5.2 — Plugin deploy lab + raj

- [ ] Install/sync `hermes-plugin` (existing `install.sh` / host path under `~/.hermes` or project convention).
- [ ] **Do not** own gateway restart here if Phase 0 task still owns it; coordinate so plugin code is loaded once restarts complete.
- [ ] Verify skill file present on both hosts; `mobile_status` works; arm → snapshot → disarm.

### Task 5.3 — Agent smoke (hands)

- [ ] From Hermes chat: status → **arm first** → snapshot → trivial gesture → disarm.
- [ ] Multi-device: `mobile_devices` / `mobile_select_device` if two lanes.
- [ ] Protected package still fail-closed.

**Success criteria (Phase 5):** S22 stable for NEW + voice; both gateways show correct threads; plugin+skill live on lab+raj; arm-first path demonstrated once.

---

## Ordered execution summary

| Phase | Focus | Implement now? |
|---|---|---|
| 0 | Gateway restarts | No — separate task |
| 1 | Crash logcats + fix NEW + voice | After nod |
| 2 | Thread list RPC/REST + profile/auth | After nod |
| 3 | Skill + AGENTS.md | After nod |
| 4 | Arm-first skill/prompt/guards | After nod |
| 5 | Sideload + plugin deploy + smoke | After nod |

---

## Global success criteria

1. No FATAL on new-thread or voice/stream (verified via `logcat -b crash`).
2. Lab thread rail matches authenticated session list for sticky profile; raj shows standalone sessions for profiles that have them.
3. `hermes-companion` skill lives only under `hermes-plugin/skills/...` and AGENTS.md states it ships with plugin deploys.
4. Arm-first is documented in skill + system prompt; control tools fail closed (or auto-arm if approved) when disarmed; status/devices remain allowed.
5. APK on phone + plugin on lab+raj smoke-checked once.

---

## Out of scope / do not do in this plan’s first nod

- Gateway restarts (Phase 0 elsewhere)
- Full OpenClaw / Room hosts rewrite
- Long interactive debugging sessions beyond the logcat captures above
- Cloud Agent launches (unavailable)
- Implementing fixes before Nyx approval (except optional additional log capture)
