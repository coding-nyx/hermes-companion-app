# Bottom-bar redesign and navigation backlog

Item 1 is implemented and validated: the bottom-bar redesign. Item 2, the broader navigation-flow redesign, is recorded in the [current item list](2026-10-13-current-conduit-parity-backlog.md#requested-ux-increments). This interpretation was stated during implementation; existing routing semantics are preserved.

## Delivered interface

The bottom bar now has **Chats, Agents, Device and More**. Chats and Agents reuse the existing Threads and Console routes. More opens a scrollable sheet containing Code review, Schedules, Hosts, Profiles and Settings. Secondary routes select More, while an open conversation selects Chats.

Labels use readable theme typography. Each primary target is at least 64 dp high and more than 48 dp wide on a 360 dp phone. Selected tabs expose selected/Tab semantics. Spoken badge descriptions explain unread conversations, armed Hands and host-status attention. Unread badges use the visible profile scope.

Conversational voice moved from the bottom bar to the **Voice/End voice** action in the Chats header, visible on the Chats tab and in open conversations. The composer's existing **MIC** still handles dictation. The restored rich-text composer source was not edited: formatting controls, field dimensions and send/steer/interrupt actions remain. The header gives the title more space by placing service details in Hosts; Back and Settings targets are 48 dp with descriptions.

The bottom bar retains the existing keyboard behavior, giving its space to the keyboard. Existing chat/room exit and host/profile routing contracts are preserved. No mobile tool, plugin, canonical skill or broker guard behavior changed.

## Item 2 scope

The broader flow review remains a requested backlog item. It covers Chats list versus open-conversation behavior, explicit New and implicit blank-composer creation, back stacks, agent-detail return paths, draft/attachment preservation, deep-link ownership and confirmation, disconnected-host recovery and accessible journeys. The bar's More menu is part of item 1; those broader routing changes are item 2. The final UX review and composer-prototype requirement remain in the phased plan.

## Completed validation

- **426 unique unit/render tests pass**, including six navigation tests for reachability, touch targets, selected state, More actions, complete labels at double text size and preserved composer/header voice controls.
- Release lint passes with zero errors, 48 existing warnings and two informational notices. The minified release build passes. The repository uses its debug-signing fallback when no release keystore is configured.
- The final APK is installed on the S22. Its installed checksum was verified against the built artifact; saved relay origin/profile were also verified before the final capture.
- The prepared S22 navigation test passed on the final APK in **1.854 seconds**, then passed again with the final capture in **4.176 seconds**. It checks four primary destinations, More entries, chat exit before secondary routing, Device selection, visible rich-text controls and zero Voice callbacks.
- The inspected final More capture shows an opaque, readable surface after the native window animation finishes. Earlier captures caught the animation fade and showed faint underlying page text; the test now waits 600 ms of real time before taking its screen image.

The phone checks use synthetic content in the production shell, with local callbacks. They do not submit host prompts, change saved preferences or start speech/microphone tests. Real host conversation/back-stack journeys remain item 2; these checks do not claim those broader outcomes. Spoken-recognition/conversation tests remain deferred independently.

Earlier retries stopped at the normal-unlock precondition while the phone was locked; they did not exercise navigation. After the operator-ready reply, the prepared checks above passed. The temporary instrumentation package was removed and the normal app relaunched after the final capture. A subsequent wireless disconnect prevented a redundant final preference read and removal of the two synthetic screenshot cache files; evidence copies are retained locally. The fresh app process starts with Hands disarmed; the relay disarm attempt did not receive an acknowledgment after that disconnect.

## Artifacts and rollback

Final production debug APK SHA-256: `9230f700193aea5c282bb007b6556a16cd71541139798c59b9156cca8c1aee55`.

The final captures, passing log (`settled-device-check.log`), APK, Gradle log and machine-readable ledger are in `/private/tmp/companion-navigation-evidence/`. Final captures are `settled-navigation-device-chat.png` and `settled-navigation-device-more.png`.

The saved relay origin is `http://100.85.151.99:9120`. At these resumed checks the operator's profile was `ash`, which was preserved rather than forcing the earlier checkpoint's `knight` profile. The current ADB endpoint is `100.105.213.54:46343`; rediscover the endpoint if wireless debugging restarts. No native test tunnel or ADB reverse was added for this increment.

The previous verified speech/context APK remains at `/private/tmp/companion-phased-context-20261013/verified.apk`, SHA-256 `3525fe44872b1f3c4cb5806cbc75c1000458c1292fbe423cc2f1f2bfbb85c9d5`. Roll back while preserving data with `adb -s 100.105.213.54:46343 install -r /private/tmp/companion-phased-context-20261013/verified.apk`.
