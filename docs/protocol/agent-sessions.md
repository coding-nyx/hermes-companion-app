# Coding-agent sessions (`/companion/agents/*`)

Plugin-owned routes (hermes-plugin/agents.py, relay.py). Gated like every `/companion/*` route:
loopback, or `Authorization: Companion <device_id>:<credential>`.

## Session

```
{ id, tool: "claude"|"codex"|"cline"|"agy"|"aider"|"opencode"|"shell",
  mode: "pty"|"structured", cwd, title, command,
  status: "starting"|"running"|"waiting_input"|"waiting_approval"|"exited",
  exit_code?, last_line, cols, rows, created_at, updated_at (float s),
  tmux: "hc-<id>" (pty) | "", attach: "tmux attach -t hc-<id>" | "<resume command>" }
```

* `pty` — the tool runs inside tmux; the phone renders `capture-pane -e` snapshots and sends keys.
* `structured` — Claude Code / Codex driven over their JSON protocols; the phone renders a chat
  transcript and answers permission prompts. No tmux needed.

## Routes

| Route | Body / query | Reply |
|---|---|---|
| `GET /companion/agents/tools[?refresh=1]` | | `{tools:[{id,label,glyph,installed,path,version,modes,install_hint,login_hint}], tmux, default_cwd}` |
| `GET /companion/agents/dirs?path=&hidden=` | | `{path, parent (null at a root edge), roots:[{path,label}], dirs:[{name,path,git,project}], recent:[cwd…], truncated, git}` — directories only, `node_modules`/`.git` skipped, git/project folders first; `403 cwd_denied` outside the allowed roots |
| `GET /companion/agents/sessions` | | `{sessions:[…]}` |
| `POST /companion/agents/sessions` | `{tool, mode, cwd, prompt?, title?, args?, cols?, rows?}` | `201 {session}` — `409 tool_missing`, `400 mode_unsupported`, `403 cwd_denied`, `503 tmux_missing` (pty only), `429 too_many_sessions` |
| `GET …/sessions/{id}/pane?cols=&rows=` | | pty: `{ansi, cursor:[x,y], cols, rows, session}` · structured: `{ansi:"", transcript:[events], approval, session}` |
| `POST …/sessions/{id}/keys` | `{text?, key?, keys?}` | `202` (structured: `c-c` = interrupt, text = prompt) |
| `POST …/sessions/{id}/prompt` | `{text}` | `202 {session}` — `409 turn_active` while a turn runs, `409 session_exited` |
| `POST …/sessions/{id}/approval` | `{request_id, decision: "once"|"deny"}` | `200` — `409 no_pending_approval` |
| `GET …/sessions/{id}/transcript?after=N` | | `{events:[…], approval, session}` (rows after index N) |
| `POST …/sessions/{id}/interrupt` | | `202` |
| `DELETE …/sessions/{id}[?forget=1]` | | `{session}` (kill; `forget` also drops the row) |
| `WS /companion/agents/events?session_id=&cols=&rows=` | | pty: `agent.pane {ansi,cursor}`, `agent.status {session}`, `agent.heartbeat` ≤4 Hz · structured: `agent.ready.replay {session, events, approval}` then live events below |

`GET /companion/health.agents = {tmux, tools:[installed ids], live}`.

## Structured events (`agent.*`)

All carry `session_id`.

| type | fields |
|---|---|
| `agent.ready` | `claude_session_id` (Claude session id / Codex thread id), `model`, `cwd` |
| `agent.user` | `text` (echo of the prompt) |
| `agent.turn.start` | |
| `agent.delta` | `text` (streamed assistant text) |
| `agent.tool.start` | `tool_id`, `name`, `detail` (command / path / first meaningful input) |
| `agent.tool.complete` | `tool_id`, `name`, `detail` (output, ≤400 chars), `error`, `duration_ms` |
| `agent.approval` | `request_id`, `kind:"approval"`, `tool`, `command`, `choices:["once","deny"]` |
| `agent.approval.resolved` | `request_id`, `decision` (`once`/`deny`/`timeout`) |
| `agent.turn.end` | `text` (full answer), `is_error`, `subtype`, `cost_usd`, `duration_ms`, `claude_session_id` |
| `agent.error` | `error` |
| `agent.exit` | `exit_code`, `stderr` (tail) |

### Claude Code runner
`claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages --permission-prompt-tool stdio [--resume <id>]`,
one process per session, stdin kept open. Prompts: `{"type":"user","message":{"role":"user","content":…},"session_id":…}`.
Permission prompts arrive as `control_request {subtype:"can_use_tool", tool_name, input}` and are answered with
`control_response {response:{subtype:"success", request_id, response:{behavior:"allow", updatedInput}|{behavior:"deny", message}}}`
once the phone posts `…/approval` (600 s timeout → deny). Interrupt = `control_request {subtype:"interrupt"}`.

### Codex runner
One `codex exec --json --skip-git-repo-check --cd <cwd> [resume <thread_id>] <prompt>` process per turn; the thread id from
`thread.started` keeps context across turns. `item.*` with `command_execution` / `file_change` / `mcp_tool_call` become tool rows,
`agent_message` becomes a delta. Exec mode has no approval prompts (the Codex sandbox policy decides).

## Lifecycle
Registry `~/.hermes/companion-agents.json` (mode 600) + `companion-agents.log` audit (start/prompt/approve/keys/kill).
Cap `HERMES_COMPANION_AGENT_MAX` (4 live). cwd must sit under `$HOME`, `HERMES_WORKSPACE`, `HERMES_HOME`, `/tmp` or
`HERMES_COMPANION_AGENT_ROOTS`. Device revoke → kill all. Relay exit → structured children are killed; tmux panes survive and
are reconciled on the next start.

## CLI
`hermes companion agent tools | list | start TOOL [--structured] [--cwd D] [PROMPT…] | prompt ID TEXT… | approve ID REQ once|deny | transcript ID | pane ID | keys ID … | kill ID`
(on hosts whose wrapper predates these verbs: `python3 ~/.hermes/plugins/hermes-companion/cli_main.py …`).
