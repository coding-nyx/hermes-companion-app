# Desk Grok broker

Companion 0.3 uses this optional small service for native Grok inference using the operator's existing host OAuth account. Android owns its local conversation/history; this service does not run a Hermes agent turn. It never returns provider access/refresh tokens. It is separate from `hermes-plugin`, whose code and mobile-control behavior are unchanged.

Requirements: a Linux systemd user session, installed Hermes with `hermes_cli.proxy.adapters.xai`, its Python/httpx runtime, an authenticated loopback dashboard on port 9119, a working Grok OAuth credential pool and Tailscale. The current tablet derives the broker address from the saved Hermes hostname with port 9121 and path `/desk-grok`. Reverse-proxy/custom-port discovery is not implemented.

Install on the selected host, choosing the credential profile explicitly:

```sh
DESK_HERMES_HOME="$HOME/.hermes/profiles/ash" ./desk-bridge/install.sh
systemctl --user status hermes-desk-grok.service
```

The installer binds only to `tailscale ip -4`, or an explicit `DESK_BRIDGE_IP`. The default profile without `DESK_HERMES_HOME` is `~/.hermes`; use the profile where the Grok account is configured. No new provider key is required. Do not expose this bind through a public forwarding rule.

Routes are exactly `GET /desk-grok/v1/models` and `POST /desk-grok/v1/responses`. The caller sends its existing dashboard session as a bearer credential. Each request is checked against `http://127.0.0.1:9119/api/profiles`. Missing, rejected or unreachable dashboard authentication fails closed. Only after this check does the server obtain a Grok OAuth credential using the installed Hermes adapter. The caller credential is never forwarded to xAI.

Inference is limited to native streamed, stateless Responses requests, text messages and inline JPEG inputs. The broker refuses tool/function items, external image URLs, file paths, arbitrary request fields and remote conversation IDs. The upstream is pinned to `https://api.x.ai/v1`; redirects are disabled. Request bodies are capped at 1.5 MB and output at 2 MB. Requests are not automatically replayed on provider errors. No CORS/HTML endpoints, token export, shell execution, Hermes tools, camera capture or device control are exposed. Prompts, images and credentials are not logged.

On lab, `hermes-desk-grok.service` is installed with `HERMES_HOME=~/.hermes/profiles/ash`. Missing/invalid caller probes returned 401 and the authenticated model catalog returned 200. The Lenovo native Grok acceptance test verifies a live reply and restoration of the saved Hermes conversation. Account revocation or dashboard restart can require reconnecting Grok from Connections; reauthentication stays on the host.

Tests:

```sh
python -m unittest discover -s desk-bridge/tests -v
```

To stop this optional integration without affecting the Hermes dashboard/plugin:

```sh
systemctl --user disable --now hermes-desk-grok.service
```

See the [provider, voice and device-control plan](../docs/superpowers/plans/2026-10-15-desk-providers-voice-and-device-control.md).
