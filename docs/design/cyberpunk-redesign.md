# Hermes Companion: Signal Deck redesign

Date: 2026-10-20
Status: implementation design specification, not a claim that every item below has shipped.

## 1. Direction

Make Hermes feel like a capable personal command deck, not a terminal skin. Electric cyan, deep ink, restrained violet, clipped-corner accents, and a small static glitch signature provide the identity. Conversation text, permissions, connection truth, and stop controls remain calm and easy to read.

The current app is native Kotlin/Jetpack Compose with shared `core-design` components and existing `MainTab` feature routes. Evolve those components rather than introducing a parallel design system or a web layer. Preserve native chat, room, delivery, draft, pairing, and explicit-arm contracts.

The current README and source are the capability baseline. Older design boards and milestone documents describe historical states and must not override current behavior.

### Design outcomes

- Starting a chat is a visible labeled action, not a discovery task.
- Voice conversation and message dictation have distinct names and controls.
- The active host and agent profile are visible; switching does not masquerade as tapping a page title.
- The conversation model is reachable from its composer and shows host-confirmed state.
- Gateway has a primary destination and includes chat access and notification routing, not just telemetry.
- Missing permission, missing pairing, offline host, and disarmed control are different states.
- Cyberpunk decoration never competes with the transcript or resembles a real failure.

## 2. Navigation and ownership

### Compact phone

Use four destination items with one central action:

| Position | Visible name | Existing route or action |
| --- | --- | --- |
| 1 | Chats | `MainTab.THREADS` |
| 2 | Gateway | `MainTab.GATEWAY` |
| Center | Voice | Voice start or active-voice controls; not a selected tab |
| 4 | Device | `MainTab.DEVICE` |
| 5 | More | Scrollable secondary destination sheet |

More contains **Coding agents**, **Agent profiles**, **Code review**, **Schedules**, and **Settings**. Keep the `CONSOLE`, `PROFILES`, `REVIEW`, and `REMINDERS` identifiers and their state ownership. Calling terminal sessions “Coding agents” prevents confusion with the Hermes profile selector.

An open individual chat or room selects Chats. Gateway selects Gateway. Coding agents, profiles, review, and schedules select More. Voice remains an action even when a voice session is active.

Navigation items have visible labels and selected semantics. Do not use a glowing dot as the only selected or unread indicator. At very large text sizes, wrapping or a more spacious layout is preferable to permanently removing labels. All destinations remain available with TalkBack and switch access.

### Header and context

Use a page title row plus one compact context area. Do not stack a title, duplicate host metadata, a global model row, and a second set of composer controls.

- Page titles identify the screen; a top-level title is no longer the hidden profile switch.
- Make Host and Agent explicitly labeled controls. Two wrapping context chips work at ordinary phone sizes; a single “Host and agent” context launcher with current values is an acceptable compact variant.
- The context chooser shows the current host and profile first, offers host selection and agent-profile selection, and includes the host's connection state. Full origins belong in its details, not squeezed into the main header.
- In a chat, the title retains a visible menu affordance for rename, pin, archive, branch, context, and controls. Its accessible name is “Conversation options for [title]”.
- The model stays beside the composer, where its scope is clear. Outside an individual conversation, model selection belongs in Gateway's model catalog or the context chooser, labeled as the applicable default.
- Use “Agent” in quick controls and “Agent profile” in details. A profile's display name is not a host name or a coding-agent type.
- Selected and loading values are distinct. “Switching to Lab…” must not show Lab as confirmed while the old host's content remains visible.

Opening a chooser must not end voice. Only selecting a change that actually changes conversation ownership should invoke the existing end-voice confirmation. Apply this guard consistently to host, profile, new-chat, room, and deep-link changes.

### Action reachability targets

- New chat: one tap from Chats; one visible action in the chat header or conversation actions.
- Voice: one visible tap opens or starts the appropriate existing flow.
- Dictation: one tap in the composer; no long-press-only requirement.
- Host or profile: visible context control, then one selection.
- Current conversation model: one composer control, then one selection.
- Review a missing permission: Device, then a specific permission row; contextual warnings can link directly to the same detail.
- Gateway chats and notification setup: visible near the top of Gateway, before resource metrics.

### Medium and expanded windows

Use available window width, not a device-name check:

- Below 600 dp: bottom navigation, single content column, modal sheets.
- 600–839 dp: compact side rail using the same destinations; constrain forms and transcript reading width rather than stretching every row.
- At least 840 dp: rail plus a 280–320 dp conversation list and flexible chat pane when in Chats. Constrain transcript text to approximately 720 dp; use a separate detail pane or bounded sheet for settings.
- At high font scale, fall back to the simpler single-column presentation when both panes cannot remain usable.
- Keyboard visibility may hide bottom navigation as today. It must not hide the composer, active microphone state, or the End voice action. Avoid double application of IME insets.

Adaptive two-pane behavior is a later implementation slice if the first pass only has phone layouts. Do not describe tablet support as validated until actual expanded-window renders and interactions pass.

## 3. Theme tokens

Follow system light/dark appearance; cyberpunk identity is not permission to force dark mode. The exact palette values below are applied in `core-design/.../Tokens.kt` and were checked against that source during this redesign. They use opaque backgrounds so contrast is predictable. This source check is not screenshot or device validation.

| Semantic token | Dark | Light | Use |
| --- | --- | --- | --- |
| Void | `#080D17` | `#F3F7FF` | Screen canvas |
| VoidElevated | `#101A2A` | `#FFFFFF` | Sheets and grouped settings |
| VoidSurface | `#17243A` | `#E5EEFA` | Recessed fields, user bubble, selected containers |
| Text | `#EDF6FF` | `#14253D` | Reading text and titles |
| TextDim | `#A9B8CE` | `#4B5F7C` | Supporting text |
| TextMute | `#91A2BC` | `#556780` | Lower-emphasis, still readable text |
| Signal | `#5BE7FF` | `#00677D` | Primary action, selected state, healthy signal |
| Accent | `#B9A0FF` | `#6744B8` | Agent identity and small decorative accents |
| Warn | `#FFC46B` | `#80520A` | Attention, armed device control |
| Danger | `#FF899F` | `#AA2948` | Destructive action, failed outcome |
| LineStrong | `#657D9F` | `#71839F` | Required control edges and focus affordances |

Using the WCAG relative-luminance formula, every proposed text/accent/status color above has at least **4.93:1** contrast against all three same-mode canvas colors. LineStrong has at least **3.29:1** against those canvases. These are token-pair checks, not a claim of whole-app accessibility conformance.

CardBorder may be quieter than LineStrong because it is decorative grouping. Never depend on a low-contrast decorative border as the only evidence that a field, button, or selected item exists.

Primary cyan buttons use dark ink text in dark mode and near-white text in light mode. Violet does not mean success or warning. Status always includes words or an icon with a semantic label.

### Type and geometry

- Keep the platform sans-serif family for human-readable UI and conversation text. Use monospace only for exact host values, timestamps, code, and small brand accents.
- Preserve the current 16/24 sp body baseline. Titles: 18/24 sp; display: 24/30 sp. Interactive labels: 14/20 sp or larger. Avoid all-caps paragraphs.
- Retain the 4, 8, 12, 16, 24, 32 dp spacing scale.
- Use 16 dp phone content gutters where density warrants it; use 24 dp for spacious forms and larger windows.
- Buttons and chips: minimum 48 × 48 dp hit areas. Principal actions: 52–56 dp high. Rows: at least 64–72 dp, allowed to grow with text.
- Cards: 12–16 dp radius. A clipped corner or short cyan/violet edge bracket can identify special panels. Do not clip button hit regions into small decorative polygons.
- Limit glow to a subtle edge accent. No bloom beneath reading text, glass blur behind permissions, or scanline overlay over messages.

## 4. Reusable element set

Implement these roles in `core-design`; existing shared components may be evolved or wrapped rather than duplicated.

| Element | Contract | Existing integration point |
| --- | --- | --- |
| Signal panel | Title, supporting text, optional status and trailing action; quiet border with one short accent edge | `GroupedSection`, `GroupSurface` |
| Context chip | Explicit role label, current value, chevron, selected/loading/disabled state; full accessible value | Header and profile/model selectors |
| Status badge | Icon plus word; healthy, warning, offline, unknown; no implied click when read-only | Gateway HUD, source and delivery labels |
| Launch tile | Large labeled route/action with short explanation and optional truthful count | Gateway and empty Chats |
| Permission row | Permission name, why it is used, current state, specific next action | Device permissions |
| Gateway route card | Host + agent + direction + enabled/blocked state; one manage action | Notification stream and host notifications |
| Attention strip | Clear issue, next action, optional dismiss; nonblocking unless a decision is necessary | Approval, connection, permission warnings |
| Session row | Title, source badge, time, unread state, accessible secondary actions | Thread rail |
| Primary action | Verb label, busy label, disabled reason where needed; does not disappear during work | New chat, connect, send |
| Glitch signature | Small decorative brand mark or corner treatment; no semantic duplication | Connect and page chrome |
| Persistent voice dock | Conversation identity, real stage, mute and End voice | Existing `VoiceDock` |

Keep public callbacks and test tags stable where possible. New components must expose roles and state through Compose semantics, not through decorative text alone. Do not replace standard switch/selectable semantics with a custom `pointerInput` gesture.

## 5. Chats and composer

### Conversation list

Top hierarchy: screen/context header → search → source/filter controls → conversations → labeled New chat action.

- Keep New chat visible above navigation with enough bottom list padding that it cannot obscure the final conversation.
- Label it “New chat”, not merely a plus. Provide a separate New room action when the relay capability is available.
- Preserve Continue unsent draft as a visible option scoped to the correct host and profile.
- Use All as the default source filter. Derive additional filters from actual reported source metadata, with canonical known labels such as Telegram and Discord. The current source model is a string; do not fabricate channel support or unread totals.
- Unknown or blank source means unreported, not “Hermes app”. Preserve exact matching semantics for known sources.
- Keep archived inclusion explicit because it changes the host query; ordinary source/search filters are view-only.
- Put unread meaning in text/semantics as well as a cyan mark. Timestamp and source must remain distinguishable from the title.

### Individual conversation

- Keep the transcript visually open. Use bounded panels for tools, attachments, and approvals, not a neon card around every assistant paragraph.
- Composer: multiline input; model/reasoning controls; attach; clearly named dictation; primary Send/Steer/Interrupt action according to existing runtime state.
- Model scope is “This conversation”. Pending, host-confirmed, unsupported, and unavailable are visibly different. A guessed default is not a confirmed model selection.
- Preserve expanded rich-text drafting and attachment ownership. A redesign must not clear drafts or move pending attachments to another host/profile.
- Delivery remains Queued, Sending, Sent, Failed, or Uncertain. Sent means acknowledged, not finished. Uncertain offers “Check”, not an automatic resend.
- Approval panels show exact action context and explicit Allow/Deny. Decorative effects never animate an approval, denial, or destructive action.

### Required list/chat states

Empty list, no search results, first load, refresh with retained content, host switch, offline cache, load failure, local draft, unsent attachments, streaming reply, pending approval, failed send, uncertain send, ended/archived thread, and unavailable optional room capability.

## 6. Voice

Voice and dictation are different jobs:

- **Voice** is a conversation session with a visible selected host, agent, and conversation.
- **Dictate message** puts speech into the composer and is independently available as a labeled accessible action.
- Long-press can remain a shortcut, never the only discoverable route.
- Do not show Listening until the microphone is ready. Keep Preparing microphone, Listening, Understanding, Waiting for Hermes, Speaking, Muted, Approval needed, and Ended as honest labels.
- Keep Mute and End voice reachable while scrolling, in another tab, and above the keyboard.
- Navigation to an incompatible conversation explains that voice will end; cancel leaves the original session untouched.
- Show microphone permission recovery with the specific reason and an action. Do not initiate recording merely because the user opened the Voice chooser.
- Native host speech/realtime/incoming-call acceptance gates remain unchanged. System speech availability must not be presented as proof that native incoming calls work.

The primary center action can be visually expressive. The live voice dock and stop controls must be stable and free of glitch motion.

## 7. Gateway as a first-class workspace

Gateway's first screen should answer: “Which Hermes am I using, can I talk to it, and where do notifications go?”

### Above-the-fold hierarchy

1. Current host with explicit connection state and Switch host action.
2. Quick actions: Open Hermes chats, Notifications, Permissions.
3. Capability summary distinguishing native chat from the optional Companion relay.
4. Saved hosts and agent/model context.
5. Health, linked hosts, background connection, and updates.

Open Hermes chats routes into the existing host/profile-scoped Chats surface. If source-specific gateway conversations are exposed, navigate with an explicit filter rather than maintaining a second incompatible session list. A card showing a chat count must use data already scoped to the same host/profile.

### Connection states

- Connected: the relevant connection is confirmed.
- Connecting: show target identity, do not display old-host conversation content under that identity.
- Offline: state what is unavailable and offer retry; cached conversations, if present, must say they are cached.
- Unknown: not checked or no capability report. Do not render unknown as green.
- Native chat available / Companion relay unavailable: chat remains usable; rooms, phone control, and stream capabilities explain their separate requirement.

### Two directions of notifications

Do not blend these into one ambiguous toggle:

1. **Hermes → phone:** host chat/approval notifications and the existing push/background-topic settings. Show actual setup state and relevant Android notification permission.
2. **Phone → Hermes:** optional notification sharing through the Companion relay. Show the destination host and profile, Android notification-listener access, stream enabled/disabled, and exclusions.

These are setup/routing surfaces unless the existing data layer supplies an actual notification history. Do not fabricate an inbox, delivery receipts, last-sync timestamps, unread counts, or notification bodies.

Enabling phone-notification sharing is never a side effect of switching hosts. Its saved destination may differ from the currently viewed chat host; label that explicitly. Do not silently redirect the stream when the user changes the chat context.

Preserve current suppression of Companion service channels and Telegram clients. State that allowed notification content is shared with the chosen host. Injection into conversations remains explicit and is not enabled by this design.

If a notification opens a chat, resolve the exact host/profile/session and preserve existing deep-link ownership confirmation. Never interpret an unverified notification body as a command to change settings.

## 8. Device and permission updates

Device is the safety and access center, not a wall of undifferentiated permission toggles.

Top hierarchy:

1. Paired phone and control-connection state.
2. Hands state, with prominent Disarm when armed.
3. Permission readiness for the feature the user wants.
4. Notification sharing and its exact destination.
5. Protected apps, audit, and advanced privacy settings.

Permission rows explain purpose in ordinary language:

- Accessibility: “Allow Hermes to read the screen and use supported controls when Hands is armed.”
- Display over other apps: “Show the visible control indicator.”
- App notifications: “Show Companion status and alerts.”
- Notification access: “Read allowed phone notifications for the selected sharing destination.”
- Microphone: “Use dictation or voice when you start it.”

Use “Allowed”, “Not allowed”, “Not requested”, or a more precise existing state. Never infer a grant from having opened Android settings. Recheck on app resume and update the row.

Ordinary app-notification and microphone permission repair must also be reachable without relay pairing. A native-chat-only user must not land on a Pair phone screen when trying to enable incoming Hermes alerts. Keep Hands and phone-notification sharing capability-gated separately.

Avoid a generic “Enable all” button. Missing count must apply to the chosen capability, not to every optional permission. Pairing and granting permissions do not arm Hands. The user explicitly arms it; Disarm remains easy and the volume-down emergency stop remains documented.

Potentially consequential settings retain their existing confirmation and platform permission flows. Notification sharing, locked-screen access, host linking, pairing revocation, and host updates are not ordinary cosmetic toggles.

## 9. Glitch and motion contract

Default: static cyberpunk accents. An opt-in animation setting may enable short decorative glitches only after the motion policy is implemented and tested.

- Static glitch: one or two offset cyan/violet fragments around a small brand wordmark or edge bracket. The primary lettering remains intact.
- Optional animated glitch: one nonlooping 100–160 ms displacement of at most 1–2 dp on a small decorative mark after an intentional interaction. No whole-screen flicker, brightness flash, random timer, text scrambling, or repeated autoplay.
- Never affect transcripts, input text, permissions, connection truth, error messages, amounts, approval actions, or voice controls.
- Respect Android's disabled-animation setting and an in-app reduce-motion choice; either disables decorative motion. Do not assume a web reduced-motion query is sufficient for Android.
- Essential progress uses a stable textual state. A cursor, waveform, or spinner is supplementary, not the only indication.
- The decorative node has no separate accessibility description and is excluded from traversal. It must not duplicate the screen reader's reading of a heading.
- Focus, active, warning, and busy states stay understandable in a still screenshot.

## 10. Accessibility acceptance

These are release checks, not a conformance claim:

- Minimum interactive bounds of 48 × 48 dp, with no overlapping expanded hit regions.
- Text contrast at least 4.5:1; essential non-text control boundaries and focus indicators at least 3:1. Recheck composed alpha colors, not just the opaque token table.
- Verify light/dark themes, 320/360/412 dp phone widths, landscape with IME, and medium/expanded widths.
- Verify font scales 1.0, 1.3, 1.5, and 2.0. Permission reasons, destructive labels, and current context must not become inaccessible through truncation.
- TalkBack traversal follows header → context → screen content → persistent controls → navigation. Sheets announce their title, provide Close/Back, and return focus to the opener.
- Use Button, Tab, Switch, selected, disabled, and stateDescription semantics as appropriate. Noninteractive decoration is silent.
- Announce important voice/state changes politely, not each animation frame or streaming token.
- Keyboard and switch users can reach New chat, host/profile/model selectors, dictation, Send/Interrupt, Disarm, and End voice.
- No color-only distinction for unread, failed, armed, offline, or selected.
- Required actions are never available only through swipe, long press, hover, or a small glyph.

Primary guidance: [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [Android touch-target guidance](https://support.google.com/accessibility/android/answer/7101858), and [WCAG 2.2](https://www.w3.org/TR/WCAG22/). Android guidance supplies the native interaction baseline; WCAG provides useful measurable visual and motion criteria.

## 11. Implementation slices and verification

### Slice A: visual foundation and shell

Update shared tokens/components; add the decorative signature; promote Gateway; clarify More labels; expose explicit context switching. Keep underlying data and scope boundaries unchanged.

Primary files: `core-design/.../Tokens.kt`, `Theme.kt`, `Controls.kt`, `Grouped.kt`, `Chrome.kt`, `AppNavigation.kt`, and `CompanionShell.kt`.

### Slice B: highest-value routes

Improve New chat and source-filter discoverability, Gateway's chat/notification quick actions, and Device's permission summary/detail entry. Reuse existing screens and callbacks before introducing new backend state.

### Slice C: supported depth

Implement a notification route summary only from actual state, lifecycle permission refresh where needed, and any additional source filtering with tests. Add adaptive rail/list-detail layouts and opt-in motion only with dedicated verification.

### Regression matrix

1. Open/close every destination; More selection is correct on all secondary routes.
2. Open and cancel host/profile/model selectors without modifying state.
3. Switch hosts with a saved draft; old content never appears owned by the new host.
4. Change agent profile with pending attachments; ownership stays intact.
5. Start, cancel, and repeat New chat without duplicate sessions or lost draft.
6. Voice start, mute, return-to-conversation, End voice, and cancelled context switching preserve existing semantics.
7. Model selection distinguishes loading, confirmed, rejected, and unsupported states.
8. Native-only host still supports chats without relay pairing.
9. Gateway notification setup links to the correct existing destination; no fake history or delivery state appears.
10. Phone-notification destination does not change when the active chat host changes.
11. Return from Android settings and verify permission grants and revocations, not just the grant happy path.
12. Pairing, permissions, and notification setup never auto-arm Hands.
13. Failed input offers explicit retry; uncertain input reads history before any resend.
14. Deep-link navigation checks exact ownership and respects cancellation.
15. Back, Close, repeated taps, IME open/close, large text, and rotation leave the screen and draft state coherent.

Run relevant unit/render tests, debug build, release lint, and available aggregate checks against the final changes. Separately report device-only checks, screenshots, native audio behavior, and real-host integration that could not be run. Old milestone test counts are not evidence for this redesign.

## 12. Implementation checkpoint

Source review during this redesign confirms the exact palette in section 3, reusable `SignalSignature` / `ContextControl` / `ProtocolStatus` elements, primary Gateway navigation, explicit Host/Agent controls, and a direct Android notification-permission action in Gateway that does not depend on pairing.

The decorative signature is static by default. Its optional motion is a 160 ms, 2 dp one-shot displacement and respects the Android animator setting. The header no longer duplicates the host/profile metadata above the new context controls. Voice ownership guards sit on actual host/profile selections, including the full Gateway and Profiles routes.

The compact navigation currently uses a pragmatic large-text fallback: at font scales of 1.5 or more, the selected destination retains a wrapping label while unselected icons retain full accessible names. This is not the ideal of keeping every visible label; it must be disclosed and tested rather than presented as a fully resolved large-text layout.

### Reviewable visual milestone

The actual Compose component specimens now render in both light and dark themes. Standard Chats, Gateway, Device, the host switcher, and a double-text-size Chats screen were also rendered and visually inspected. The component test class passed 2 tests and the shell render class passed 6 tests, with zero failures, errors, or skips in their XML results.

The light/dark specimens and standard Chats screenshot are suitable for design review: no blocking clipping, overlapping labels, or missing primary controls were found in those captures. They use synthetic sample data and are not evidence of a live connected host.

Current render outputs:

- `app/build/reports/cyberpunk-elements/elements-dark.png`
- `app/build/reports/cyberpunk-elements/elements-light.png`
- `app/build/reports/cyberpunk/chats-dark.png`
- `app/build/reports/cyberpunk/gateway-dark.png`
- `app/build/reports/cyberpunk/device-dark.png`
- `app/build/reports/cyberpunk/host-switcher-dark.png`
- `app/build/reports/cyberpunk/chats-large-text.png`

The follow-on implementation resolves the initial visual refinements: Device's repeated title is reduced to a caption, wrapped Chats filters have an 8 dp gap, and Host/Agent controls stack at large text so names remain readable. Agent-profile choices use explicit current-state labels and wrapping metadata, and their chooser has a visible Close action.

The model sheets distinguish last host-confirmed values from an unconfirmed request. Voice start identifies the host, agent, and conversation before microphone activation; the active voice sheet pins Mute, output, and End voice outside the scrolling detail area. The narrow large-text dock uses a concise visible status while preserving the full accessible description.

New actual previews are under `app/build/reports/cyberpunk-chat-controls/`: `conversation-model.png`, `model-requested-not-confirmed.png`, `voice-start.png`, `voice-controls-pinned-stop-2x.png`, and `voice-dock-preparing-2x.png`. Updated agent chooser previews are `app/build/reports/cyberpunk/agent-switcher-dark.png` and `agent-switcher-large-text.png`.

An initial test setup enlarged only the activity, not modal windows. That limitation was corrected using the platform font-scale setting and fresh screenshots. The genuine double-text-size context, agent chooser, voice sheet, and dock were visually reviewed; no additional visual layout blocker was found. Shared Close/Back controls now have explicit 48 dp bounds, and the final app XML results report 242 tests with zero failures, errors, or skips, including the corrected bound regression. The large-text navigation compromise described above remains.

Real-device operation, real-host integration, native audio, expanded-window layouts, and the broader acceptance matrix are not established by this visual milestone.
