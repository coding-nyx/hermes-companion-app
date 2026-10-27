# Native Bots and group chats

Bots is the default landing screen and the first bottom navigation destination. Chats remains a separate destination; Gateways is available through the host switcher or More. It operates on the selected native Hermes dashboard and does not require the Companion relay. Profiles remain available separately. Existing Companion agent rooms keep their existing routes, policies and storage.

## Contract reference

Implemented against NousResearch/hermes-agent `7f64198bc16a260880f542713793cb46a97dcc0d` (2026-10-26):

- `tui_gateway/contracts/profiles_vault_complete_foreign_subagents.py`
- `tui_gateway/contracts/sessions.py`
- `tui_gateway/contracts/groups_bot_relay.py`
- `gateway/hosted_room_discussion.py`, `gateway/hosted_rooms.py`
- `apps/desktop/src/plugins/hermes-bots/canonical-chat.ts`

Use native JSON-RPC over authenticated `/api/ws`, with the connection's proxy prefix intact. A dedicated socket per profile shares only that dashboard client's scoped authentication; bot/group operations cannot replace the ordinary chat socket or its runtime-id mapping. A method-not-found error surfaces an update requirement; it never selects a guessed REST or Companion equivalent.

Companion standalone returns JSON-RPC code `404` with `unknown method profiles.list` for the native roster. This explicit method rejection, as well as native code `-32601`, puts Bots into an unavailable state with Open Chats, Switch gateway and Retry actions. Search, empty-roster creation hints and the creation menu are withheld. Other 404s, authentication failures and connectivity errors remain ordinary errors. A successful explicit refresh after upgrading/reconnecting clears the unavailable state. The standalone relay's REST profile list does not provide the native canonical-chat, metadata or hosted-group contracts and is not substituted for them.

The host's startup setting decides the initial relay mode; this does not establish the
capabilities of the installed Hermes version or of desktop's backend. Device/pairing
and Gateway now expose a persistent, authenticated mode selector. Native mode is
verified before changing routing. A successful change reconnects the operator and
clears the previous Bots capability/unsupported state. Phone tools and arming remain
independent. The app uses a compact horizontal floating navigation bar with equal
48 dp targets, hidden inside every conversation (ordinary, Bot Chat, Companion room
or native group). The composer is one line and 56 dp high while inactive; focus reveals
an auto-growing field and a compact toolbar with a formatting menu. Collapsing it
preserves complete multiline drafts and their formatting.

## Bot roster and editing

`profiles.list` supplies canonical root/tip IDs, previews, model/provider, description, avatar availability and the `ui_meta.hermes-bots` namespace. Compact, avatar-led roster rows open the canonical chat directly and show its preview or role. The shared PageToolbar keeps host context under the title; its + menu offers New bot and New group chat. Search uses the shared SearchField with symmetric 16 dp page margins, a centered single line, search icon and clear action. All/Bots/Groups filters show counts. Group chats appear above bots and have their own filter. Search, hidden state, sections and pinned-first ordering remain available; secondary controls live in menus. Creation starts with three fields, with model/provider and presentation settings under Advanced. Form labels stay inside each field and float above entered text; the separate label/duplicate-placeholder rows are removed. The editable accessibility bounds stay at least 48 dp, with large text allowed to expand.

Create calls `profiles.create` with channel cloning disabled, then reads the new row and configures its presentation. Model and provider are optional together; omitted fields retain the profile's model. A profile-creation timeout is not automatically repeated. Refresh before retrying so an already-created profile can be edited.

Editing merges the complete existing `hermes-bots` namespace and sends `ui_meta_expected_revisions` whenever the host exposes revisions, including revision zero. Conflicts and partial application are errors, not successful saves. Unknown metadata keys are preserved. The app does not edit SOUL.md to inject the Bot Mode protocol.

## Canonical Bot Chat

Identity is `(dashboard base, profile, exact title "Bot Chat")`, not a saved session pointer or the newest session.

1. Query `session.list` with `title: "Bot Chat"` and `include_hidden: true`.
2. Open the returned compression tip when available. A known canonical row plus a temporarily empty registry fails without creating a replacement.
3. For an absent chat, save a stable creation key before calling `session.create` with `hidden: true` and `follow_profile_config: true`.
4. Materialize its canonical title using `session.title` before opening it. If another writer acquired the title, query again and adopt the winner.
5. An older host that explicitly rejects only the `idempotency_key` field during parameter validation is retried once without that unsupported field. No fallback runs after a timeout or other uncertain failure.
6. A lost acknowledgment retains the creation key for the next explicit open. No uncertain mutation is automatically replayed.

The existing native chat engine handles history, streaming, attachments, model controls and decisions. `/new` and `/reset` issued inside Bot Chat route to `/compact`; renaming the canonical session is blocked. Edit the bot's display name from Bots instead.

## Hosted native groups

The app reads `groups.capabilities` before offering actions. Creation requires an available driver and the create/send methods. When present, `seatable_profiles` determines eligible members; an explicitly empty set grants no seats. Groups created by this screen contain 2–6 distinct profiles on the selected gateway. Cross-host Companion rooms remain a separate explicit workflow; saved gateways are not automatically given peer execution grants.

- Catalog: paginated `groups.list`.
- Create: stable caller-owned room ID, member IDs, handles and display names through `groups.create`. The group name is optional in the form, matching desktop: selecting two bots is enough. A blank name defaults to the selected display names (up to 64 characters), with a numeric suffix if already used. The resolved name is persisted in the creation envelope before transmission, so recovery retains that exact name and room identity.
- Read: `groups.state` plus every `groups.log` page after the last verified cursor. Events are deduplicated by event ID, ordered by sequence, and retain their thread identity. Other-room events, stale/invalid cursors and authority changes stop writable admission until reconciliation.
- Send: `groups.send` with a persisted event ID and `{text, thread_id}` payload. Reply in thread preserves the selected thread; a new topic gets a new thread ID. No synthetic actor is submitted.
- Recovery: retain the complete original envelope after an unknown outcome. An explicit Check / recover uses that same native idempotency key. A replayed server event `user:sha256(client_event_id)` resolves delivery without another send.
- Controls: `groups.rename`, `groups.stop`, `groups.disband` where advertised. Disband requires a user confirmation.
- Approvals: `groups.approve` with the exact member, task, execution generation and request ID. The inspected driver accepts `once` and `deny`; incomplete coordinates are never exposed as actionable approvals.
- Indeterminate work: `groups.retry` is offered only for a host-reported pending retry and requires explicit confirmation about repeated work.

Group logs are polled while visible with bounded reconnect backoff. Native gateway scheduling continues independently of the phone. Member replies and failures come from authoritative room events. Drafts and operation envelopes use separate encrypted local stores with exact gateway/profile ownership. Polling does not send prompts or grant/arm phone controls.

## Gateway ownership

The saved gateway book, operator credentials, device credentials and selected-profile keys now preserve reverse-proxy paths. Two dashboards at `/one` and `/two` on the same authority remain distinct. Legacy root credentials are retained but never automatically adopted by a proxy path whose original owner cannot be proven; sign in/pair that exact path again if required.

## Validation boundaries

Focused tests cover native wire contracts, metadata conflicts, canonical identity, separate profile sockets, replay pagination, stale-owner fencing, stable operation recovery, persisted journal ownership and gateway credential isolation. Render tests inspect all main screens, the roster, full shell, group conversation, creation sheets, light/dark palettes and large text. Regression checks require six fully visible conversations at 360×720, exact 48 dp search height and symmetric margins, functioning search/clear, and equal navigation item geometry. The five dock items share icon and label slots; voice has the same visual weight as the other actions. Toolbars, search, cards, sheets, buttons and dock indicators retain the established cyberpunk signal signature, cyan/violet accents and cut-corner geometry. Compact layout and input density are independent of those visual treatments.

On 2026-10-26 the final debug APK was installed on Samsung SM-S901E (S22) and its installed SHA-256 matched the local build. `NativeBotsDeviceTest` passed against Lab: UUID-scoped profile creation/editing, canonical chat reuse, group creation with an automatically generated name/rename, a real member reply, duplicate-send suppression, stop/disband and fixture cleanup. Four `HostCredentialIsolationDeviceTest` checks also passed on the phone. The live reply test pins fixture profiles to the existing default bot’s model/provider, since the launch profile’s inherited Codex model was rejected by its provider. No existing profile settings were changed. The toolbar/navigation revision was also checked through the canonical mobile control lane on S22: real roster and keyboard-visible search screenshots, filtering and clearing, bot form and Advanced disclosure, two-member group selection, Chats/Device/Bots navigation, and screenshot inspection of all of those states. Forms were closed without saving; the phone was returned to Bots and Hands disarmed. Subsequent field compaction was render-verified with empty and filled values, dark/light themes and large text; its regular New Bot form stays within 440 dp at 360×720 with Save visible. No plugin protocol change or host deployment is required by this implementation.

The optional-name correction is covered by a UI test that selects two bots without entering a name, a controller test that persists/reuses the generated name after a lost acknowledgment, naming/collision tests, and the live S22 native-group test. All five device checks passed after installation of this correction.

Work gateway diagnosis on 2026-10-26: ADB captured `unknown method profiles.list` on S22 at `http://100.86.138.6:9120`. Its health and status report `mode: standalone` / `version: companion-standalone`; its ordinary REST roster still lists six profiles. Running the read-only `hermes --version` command through the app's Work Shell reported `v0.22.5+1524.g000861b.dirty (2026.9.24)`. The app now recognizes the standalone method rejection and explains the native-dashboard requirement. All 310 app unit/render tests and lint passed; dark/light/large-text unavailable screens were visually inspected. The resulting APK was installed on S22 and Open Chats successfully displayed Work's existing default-profile conversations. This correction does not add native Bots/groups to the standalone backend or upgrade the Work installation.

On 2026-10-27, the mode-setting plugin update was deployed to both Lab and Work.
Work was switched through the paired app to its verified native backend; health now
reports `mode: proxy` and `mode_source: saved`. Native `profiles.list` returns default,
coder, designer, qa, researcher and verifier; `groups.capabilities` reports an available
driver and create/send support. A fresh ADB hierarchy on S22 confirmed `Bots 6` on
Work. This establishes a relay-routing error rather than a missing feature inferred
from the Hermes version string.

The final editor revision gives active text the full width and puts the six actions
on a single toolbar without individual secondary-button borders. Model, Reasoning
and Expand draft share a controls menu. The explicit expanded editor uses compact
16 dp margins and a smaller minimum text area; writing starts at the top with the
cursor at the end of the draft, and large text can grow naturally.
App (315), chat (28) and remote (138) regressions, plugin tests (195) and lint passed. The
S22 navigation/editor/model/voice-control test passed. Its fresh
device captures were visually inspected. The installed refined-editor APK matched
SHA-256 `921d2b521f4212c70bd36e3274372610215004bca465730aaf2221eabc818e73`.
The phone was then left on Work's populated Bots screen and Hands was disarmed.
The final top-alignment/cursor correction was installed after reconnecting wireless
ADB, and the on-device APK matched SHA-256
`c4f21d623070a89ec1080b6817d39b19ffb47ed9b53e35011345e23f94a8d068`.
The final popup-focus correction keeps the editor active while a formatting or
conversation-controls menu is open and does not treat the menu's IME transition as
leaving the editor. Its regression explicitly drops field focus with the menu open.
The final installed APK matched SHA-256
`7e798cb8a8df272534ae3df4edd50059a0e75973f346b17cc1bfbd78fd29a1db`,
and the S22 navigation/editor/model/voice-control test passed in 18.702 seconds.

The 2026-10-27 transcript correction resolves reply labels, pending labels, composer
hints and canonical-thread presentation from the configured bot title or agent
profile. The native database title `Bot Chat` and transcript roles remain unchanged.
Group events retain actor identity, and peer members retain their own roster names
instead of borrowing a same-named local profile. Companion room speakers use the
same local display-name catalog; missing room authors fall back to the room title.

Transcript boundaries now use 12 dp between operator turns/speakers and 4 dp between
steps of one agent turn. Consecutive list and quote lines use 2 dp, paragraphs 8 dp,
and headings 4 dp after/10 dp before. Copy/read and native-group reply actions sit
beside the speaker instead of adding another footer row, with 48 dp targets. Table
columns grow with font size. Full chat, room and group screens, code/tables and the
expanded draft were visually inspected in dark/light palettes and at large text.

811 affected unit/render regressions and Android lint passed. The S22 transcript
test passed in 9.963 seconds, checking bot/agent/group names, list spacing, message
controls and hidden in-chat navigation. Fresh device screenshots were inspected.
The installed ARM64 debug APK matched SHA-256
`ceed5037bd4e0e37cba33152bb6767c312e77140008f0218596eaa7382970ae7`.
The phone was returned to the live Work roster with all six bots visible and Hands
disarmed. No plugin or Hermes host update was needed for this correction.
