"""Tool schemas the model sees. Keep the set small."""

STATUS = {
    "name": "mobile_status",
    "description": "Paired Android device: armed, foreground app, a11y, overlay. Fails if unpaired.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

DEVICES = {
    "name": "mobile_devices",
    "description": "List paired/connected Android companions with name, model, armed, foreground app, is_default, lane.",
    "parameters": {"type": "object", "properties": {}},
}

SELECT_DEVICE = {
    "name": "mobile_select_device",
    "description": "Set the default target device for subsequent mobile_* calls that omit device.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},
        },
        "required": ["device"],
    },
}

SNAPSHOT = {
    "name": "mobile_snapshot",
    "description": "Accessibility tree of the current screen with @eN refs. Work from this tree; refs die on the next snapshot. Prefer this over screenshot. Do not use on banking or authenticator apps.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "include_system_ui": {"type": "boolean", "default": False},
        },
    },
}

CLICK = {
    "name": "mobile_click",
    "description": "Tap a control. Prefer text= (visible label) or ref=@eN from the last snapshot. Avoid x,y. Returns a fresh snapshot; do not reuse old refs. Snapshot first if you have no tree.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "ref": {"type": "string", "description": "@eN from the last snapshot (invalid after any later snapshot/gesture)."},
            "text": {"type": "string", "description": "Visible label/content-desc to tap. Unique match required; ambiguous returns candidates."},
            "x": {"type": "number"},
            "y": {"type": "number"},
        },
    },
}

TYPE = {
    "name": "mobile_type",
    "description": "Type into the currently focused field. Click the input first. Returns a fresh snapshot. Never log the text.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},"text": {"type": "string"}},
        "required": ["text"],
    },
}

PRESS = {
    "name": "mobile_press",
    "description": "Hardware/global action: back, home, recents.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "key": {"type": "string", "enum": ["back", "home", "recents"]},
        },
        "required": ["key"],
    },
}

SWIPE = {
    "name": "mobile_swipe",
    "description": "Swipe from (x1,y1) to (x2,y2), by dx/dy from center, or between snapshot refs.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "x1": {"type": "number"},
            "y1": {"type": "number"},
            "x2": {"type": "number"},
            "y2": {"type": "number"},
            "dx": {"type": "number"},
            "dy": {"type": "number"},
            "from_ref": {"type": "string"},
            "to_ref": {"type": "string"},
        },
    },
}

SCROLL = {
    "name": "mobile_scroll",
    "description": "Scroll the screen or a snapshot node. direction is content movement: down reveals content below. Prefer ref= on the scrollable node.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
            "ref": {"type": "string"},
        },
        "required": ["direction"],
    },
}

OPEN = {
    "name": "mobile_open_app",
    "description": "Launch a package from a prior mobile_apps list. Then mobile_wait(800-1500) and mobile_snapshot — the UI has not settled. Blocked on protected packages.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},"package": {"type": "string"}},
        "required": ["package"],
    },
}

APPS = {
    "name": "mobile_apps",
    "description": "Launchable packages on the paired device.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},
        },
    },
}

WAIT = {
    "name": "mobile_wait",
    "description": "Pause up to 5 seconds while ARMED so the UI can settle (after open_app or a tap that navigates).",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},"ms": {"type": "integer", "minimum": 0, "maximum": 5000}},
        "required": ["ms"],
    },
}

ARM = {
    "name": "mobile_arm",
    "description": "Arm the phone for control. Returns {armed, a11y_bound, lanes}; lanes is 'l0+l1' when accessibility is bound and 'l0' when it is not. Arming never requires accessibility \u2014 an l0-only session can still do audio, media, clipboard writes, notification actions and device info. Snapshot/click/type/swipe/scroll/press need l1 and answer a11y_unavailable without it.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},
        },
    },
}

DISARM = {
    "name": "mobile_disarm",
    "description": "Disarm the paired Android companion immediately. Same kill switch as the phone DISARM control and volume-down chord.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},
        },
    },
}

SCREENSHOT = {
    "name": "mobile_screenshot",
    "description": "PNG screenshot, max edge 1080, no EXIF. Prefer mobile_snapshot.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},"max_edge": {"type": "integer", "minimum": 64, "maximum": 1080}},
    },
}

NOTIFICATIONS = {
    "name": "mobile_notifications",
    "description": "Read recent phone notification stream events (package, title, text). Read-only; does not post to chat. Stream must be enabled on the phone.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "limit": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
            "since_ms": {"type": "integer", "description": "Only events with ts_ms greater than this."},
            "profile": {"type": "string", "description": "Optional sink profile filter (e.g. ash)."},
        },
    },
}

NOTIFICATIONS_INJECT = {
    "name": "mobile_notifications_inject",
    "description": "Explicitly inject a summarized phone-notification note into the active session/thread for this profile. Never auto-called; only when you decide the user would want it in chat. Dedupes by notification_key.",
    "parameters": {
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Agent-authored summary to post into the active session."},
            "session_id": {"type": "string", "description": "Optional session id; defaults to most recent session for the device profile."},
            "notification_key": {"type": "string", "description": "Optional key from mobile_notifications to dedupe."},
            "device": {"type": "string", "description": "Device id or friendly name."},
            "profile": {"type": "string", "description": "Profile id override; defaults to the device's bound profile."},
        },
        "required": ["text"],
    },
}

# ---------- L0 (direct API) tools — do not require a11y, not scoped to the foreground app ----------

AUDIO_VOLUME_GET = {
    "name": "mobile_audio_volume_get",
    "description": "Read per-stream volume (music / ring / notification / alarm / system / voice_call). With no stream= returns all of them. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "stream": {"type": "string", "enum": ["music", "ring", "notification", "alarm", "system", "voice_call"]},
        },
    },
}

AUDIO_VOLUME_SET = {
    "name": "mobile_audio_volume_set",
    "description": "Set a volume stream. Pass level=0..max for an absolute value, or step=<int delta> for a relative nudge. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "stream": {"type": "string", "enum": ["music", "ring", "notification", "alarm", "system", "voice_call"]},
            "level": {"type": "integer", "minimum": 0, "description": "Absolute level, clamped to [0, max]."},
            "step": {"type": "integer", "description": "Relative delta applied to the current level."},
        },
        "required": ["stream"],
    },
}

AUDIO_RINGER = {
    "name": "mobile_audio_ringer",
    "description": "Read or set the ringer mode (normal / vibrate / silent). silent requires Do Not Disturb access — otherwise returns policy_denied. Requires ARMED for the destructive set.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "mode": {"type": "string", "enum": ["normal", "vibrate", "silent"]},
        },
    },
}

MEDIA_SESSIONS = {
    "name": "mobile_media_sessions",
    "description": "List active media sessions (Spotify, YouTube, etc.) with package, is_active, playback_state. Requires the user to have granted Hermes notification access. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

MEDIA_NOW_PLAYING = {
    "name": "mobile_media_now_playing",
    "description": "Read the now-playing metadata (title / artist / album / duration_ms) for a media session, identified by its owning package. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "session_id": {"type": "string", "description": "Owning package of the session (from mobile_media_sessions)."},
        },
        "required": ["session_id"],
    },
}

MEDIA_CONTROL = {
    "name": "mobile_media_control",
    "description": "Send a transport action (play / pause / toggle / next / prev / stop / seek) to a media session. seek needs position_ms. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "session_id": {"type": "string", "description": "Owning package of the session (from mobile_media_sessions)."},
            "action": {"type": "string", "enum": ["play", "pause", "toggle", "next", "prev", "stop", "seek"]},
            "position_ms": {"type": "integer", "minimum": 0, "description": "Required for action=seek."},
        },
        "required": ["session_id", "action"],
    },
}

CLIPBOARD_GET = {
    "name": "mobile_clipboard_get",
    "description": "Read the primary clipboard as plain text. Android 10+ refuses the read unless Companion is the focused app or the default IME, so this normally fails with clipboard_read_blocked; to read what the user is looking at, snapshot the focused field instead. Reading also shows the user a paste toast on Android 12+. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

CLIPBOARD_SET = {
    "name": "mobile_clipboard_set",
    "description": "Write plain text to the primary clipboard. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "text": {"type": "string", "description": "Text to place on the clipboard. Never log the value."},
        },
        "required": ["text"],
    },
}

DEVICE_INFO = {
    "name": "mobile_device_info",
    "description": "Read-only device metadata: manufacturer, model, SDK, screen geometry, refresh rate, locale, battery %, ringer mode, a11y/NLS binding. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

NOTIFICATIONS_NLS = {
    "name": "mobile_notifications_nls",
    "description": "Read whether the system has granted Hermes Companion notification-listener access (NLS) and which component would be bound. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

NOTIFICATIONS_NLS_ENABLE = {
    "name": "mobile_notifications_nls_enable",
    "description": "Open the system Notification Listener settings page so the user can grant Hermes Companion access. The app cannot programmatically grant NLS (signature-only); the user must toggle on the Companion entry. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

NOTIFICATIONS_STREAM = {
    "name": "mobile_notifications_stream",
    "description": "Read the phone's shade-notification stream config: enabled, configured origin, target origin (resolved), profile, nls_bound. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
        },
    },
}

NOTIFICATIONS_STREAM_SET = {
    "name": "mobile_notifications_stream_set",
    "description": "Set the phone's shade-notification stream config. Pass enabled=true|false to toggle the stream; pass origin= and/or profile= to change the target. Re-reads as `mobile_notifications_stream` to confirm. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "enabled": {"type": "boolean", "description": "true to turn the stream on, false to turn it off."},
            "origin": {"type": "string", "description": "Target gateway origin (e.g. http://100.85.151.99:9120). Empty clears the override and falls back to the active host."},
            "profile": {"type": "string", "description": "Target profile id on the chosen origin (e.g. coder, ash). Empty clears the override."},
        },
    },
}

DEV = {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."}

NOTIFICATION_ACTIONS = {
    "name": "mobile_notification_actions",
    "description": "List the buttons on one shade notification, as {index, title, accepts_text}. Use it before firing anything: `accepts_text` buttons must go through mobile_notification_reply, not mobile_notification_action. Takes a `key` from mobile_notifications. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "key": {"type": "string", "description": "Notification key from mobile_notifications."},
        },
        "required": ["key"],
    },
}

NOTIFICATION_ACTION = {
    "name": "mobile_notification_action",
    "description": "Press a plain (non-text) button on a shade notification by index — 'Mark as read', 'Archive', 'Snooze'. Far more reliable than finding the button with snapshot/click, and it does not bring the app to the foreground. Refuses buttons that expect text. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "key": {"type": "string", "description": "Notification key from mobile_notifications."},
            "index": {"type": "integer", "description": "Button index from mobile_notification_actions."},
        },
        "required": ["key", "index"],
    },
}

NOTIFICATION_REPLY = {
    "name": "mobile_notification_reply",
    "description": "Reply to a message notification through its inline reply box (RemoteInput), without opening the app. Only works on notifications whose action reports accepts_text. The text is delivered to the owning app as if the user typed it, so treat it as sending a real message to a real person: confirm the wording with the user first, and never send speculative or placeholder text. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "key": {"type": "string", "description": "Notification key from mobile_notifications."},
            "text": {"type": "string", "description": "Exact message to send. Never logged or echoed back."},
        },
        "required": ["key", "text"],
    },
}

NOTIFICATION_DISMISS = {
    "name": "mobile_notification_dismiss",
    "description": "Clear one notification from the shade. Does not act on its content — the message stays unread in the app. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "key": {"type": "string", "description": "Notification key from mobile_notifications."},
        },
        "required": ["key"],
    },
}

NOTIFICATION_SNOOZE = {
    "name": "mobile_notification_snooze",
    "description": "Snooze one notification; the platform re-posts it when the time is up. Clamped to 1s..24h. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "key": {"type": "string", "description": "Notification key from mobile_notifications."},
            "duration_ms": {"type": "integer", "description": "Snooze duration in milliseconds (1000..86400000)."},
        },
        "required": ["key", "duration_ms"],
    },
}

NOTIFICATION_POLICY = {
    "name": "mobile_notification_policy",
    "description": "Read the per-app notification policy: which packages are off (never reach the host), silent (readable with mobile_notifications but never wake an agent) or respond (wake the profile). Every app defaults to respond. Shows where each rule came from and flags host rules that can never take effect because the phone already suppresses that app at source. Allowed while disarmed.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "profile": {"type": "string", "description": "Resolve per-profile overrides for this profile. Optional."},
        },
    },
}

NOTIFICATION_POLICY_SET = {
    "name": "mobile_notification_policy_set",
    "description": "Set one package's notification policy on this host: mode=off|silent|respond, or mode=default to clear the override. Pass profile= to scope the rule to one profile instead of the whole host. Host rules override the phone's own per-app rules, except that a package the phone suppresses at source can never be loosened from here — the notification never arrives. Writes host config, not the phone, so it needs no ARM.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "package": {"type": "string", "description": "Android package name, e.g. com.zoho.chat."},
            "mode": {"type": "string", "enum": ["off", "silent", "respond", "default"], "description": "off = drop before the ring; silent = ring but never wake; respond = ring and wake; default = remove the override."},
            "profile": {"type": "string", "description": "Scope this rule to one profile. Omit for a host-wide rule."},
        },
        "required": ["package", "mode"],
    },
}

SMS_LIST = {
    "name": "mobile_sms_list",
    "description": "Read the phone's SMS history, newest first, as {id, address, body, date_ms, type, read, thread_id}. Filter with address= (substring, so a local-format number matches an internationally stored one), query= (substring of the body), since_ms=, box=inbox|sent. This is the user's private message history: read the narrowest slice that answers the question, never dump the whole inbox, and do not repeat bodies back into a shared channel. Requires ARMED and the READ_SMS grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "limit": {"type": "integer", "description": "Max messages, 1..200. Default 20."},
            "address": {"type": "string", "description": "Only messages to/from this number."},
            "query": {"type": "string", "description": "Only messages whose body contains this text."},
            "since_ms": {"type": "integer", "description": "Only messages newer than this epoch-millis."},
            "box": {"type": "string", "enum": ["inbox", "sent"], "description": "Restrict to received or sent."},
        },
    },
}

SMS_SEND = {
    "name": "mobile_sms_send",
    "description": "Send an SMS silently from the user's number. This puts a real message on the network to a real person and cannot be recalled: confirm the exact recipient and wording with the user first, never send speculative or placeholder text, and prefer mobile_sms_compose (which opens the messaging app pre-filled for the user to send) unless the user has asked for it to go automatically. Long messages are split into a multipart message. Requires ARMED and the SEND_SMS grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "address": {"type": "string", "description": "Recipient number. Resolve names with mobile_contacts_search first."},
            "body": {"type": "string", "description": "Exact message text. Never logged or echoed back."},
        },
        "required": ["address", "body"],
    },
}

SMS_COMPOSE = {
    "name": "mobile_sms_compose",
    "description": "Open the messaging app with the recipient and text pre-filled, for the user to send. Needs no SMS permission at all, so this is the preferred way to text someone. Puts an app on screen (returns screen_changed) and needs_user=true — the message is NOT sent until the user taps send. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "address": {"type": "string", "description": "Recipient number."},
            "body": {"type": "string", "description": "Draft text to pre-fill."},
        },
        "required": ["address"],
    },
}

CALL_LOG = {
    "name": "mobile_calls_log",
    "description": "Read the call log, newest first, as {id, number, name, type, date_ms, duration_s}. type is incoming/outgoing/missed/voicemail/rejected/blocked; filter with type= and since_ms=. Private history — read the narrowest slice that answers the question. Requires ARMED and the READ_CALL_LOG grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "limit": {"type": "integer", "description": "Max entries, 1..200. Default 20."},
            "type": {"type": "string", "enum": ["incoming", "outgoing", "missed"], "description": "Restrict to one direction."},
            "since_ms": {"type": "integer", "description": "Only calls newer than this epoch-millis."},
        },
    },
}

CALL_DIAL = {
    "name": "mobile_calls_dial",
    "description": "Open the dialer with a number pre-filled, for the user to press call. Needs no permission. Puts an app on screen (screen_changed) and needs_user=true — no call is placed. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "address": {"type": "string", "description": "Number to dial."},
        },
        "required": ["address"],
    },
}

CALL_PLACE = {
    "name": "mobile_calls_place",
    "description": "Place a phone call immediately, without the user confirming. This rings a real person from the user's number: confirm who is being called first, and prefer mobile_calls_dial unless the user asked for it to dial automatically. Requires ARMED and the CALL_PHONE grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "address": {"type": "string", "description": "Number to call. Resolve names with mobile_contacts_search first."},
        },
        "required": ["address"],
    },
}

CONTACTS_SEARCH = {
    "name": "mobile_contacts_search",
    "description": "Search contacts by name or number; returns {contact_id, name, number, label}. Omit query= to list contacts. This is how you turn 'text Amma' into a number before mobile_sms_compose / mobile_sms_send / mobile_calls_dial. Results are NOT ranked by how often the user contacts someone (Android 10 removed contacts affinity), so when several match, ask the user which rather than guessing. Requires ARMED and the READ_CONTACTS grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "query": {"type": "string", "description": "Name or number substring."},
            "limit": {"type": "integer", "description": "Max results, 1..200. Default 20."},
        },
    },
}

VOICE_SPEAK = {
    "name": "mobile_speak",
    "description": "Make the phone say something out loud. Needs no Android permission and works with the screen off, so it is the cheapest way to reach a human who is near the phone but not looking at it. Speech plays on the music stream: the result includes music_volume and audible=false when the phone is muted, which is the one way this silently fails — check it before assuming the user heard you. Returns as soon as the utterance is queued unless you pass wait_ms. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "text": {"type": "string", "description": "What to say. Plain prose; max 4000 chars."},
            "queue": {"type": "boolean", "description": "true appends after speech already playing; false (default) interrupts it."},
            "rate": {"type": "number", "description": "Speech rate 0.5..2.0. Omit for the engine default."},
            "pitch": {"type": "number", "description": "Pitch 0.5..2.0. Omit for the engine default."},
            "wait_ms": {"type": "integer", "description": "Block until the phone finishes speaking, up to this many ms (cap 120000). Omit or 0 to return immediately; the phone keeps speaking."},
        },
        "required": ["text"],
    },
}

VOICE_STOP = {
    "name": "mobile_speak_stop",
    "description": "Immediately halt any speech the phone is playing or has queued. Requires ARMED.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

VOICE_STATUS = {
    "name": "mobile_speak_status",
    "description": "Check whether the phone can speak: engine availability, any init error, the default locale, the languages it can speak, and the music volume with audible=false when muted. Use it when mobile_speak appeared to work but the user heard nothing. Allowed while disarmed.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

# ---- L2 intent lane ----

ALARM_SET = {
    "name": "mobile_alarm_set",
    "description": "Set an alarm on the phone's clock app. Install-time permission only, no runtime prompt. skip_ui (default true) asks the clock app to set it without coming to the foreground — the one intent handoff that can be silent — but no clock app is obliged to honour it, so the result reports skip_ui_requested rather than promising the screen stayed put. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "hour": {"type": "integer", "description": "0-23, phone local time."},
            "minute": {"type": "integer", "description": "0-59."},
            "label": {"type": "string", "description": "Alarm label shown to the user."},
            "skip_ui": {"type": "boolean", "description": "Ask the clock app not to open. Default true."},
        },
        "required": ["hour", "minute"],
    },
}

TIMER_SET = {
    "name": "mobile_timer_set",
    "description": "Start a countdown timer on the phone's clock app. Same skip_ui caveat as mobile_alarm_set. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "seconds": {"type": "integer", "description": "Duration in seconds."},
            "label": {"type": "string", "description": "Timer label."},
            "skip_ui": {"type": "boolean", "description": "Ask the clock app not to open. Default true."},
        },
        "required": ["seconds"],
    },
}

MEDIA_PLAY_SEARCH = {
    "name": "mobile_media_play_search",
    "description": "Start playback by search query — 'play <artist>', 'play <song>'. Fills the gap in the media tools: mobile_media_control needs a session that already exists, so nothing could start playback from cold. A media app takes the query and begins playing, which changes what is on screen. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "query": {"type": "string", "description": "What to play, e.g. an artist, album or song."},
        },
        "required": ["query"],
    },
}

CALENDAR_ADD = {
    "name": "mobile_calendar_add",
    "description": "Open the calendar app with a new event pre-filled for the user to save. Needs no calendar-write permission, which is why it is preferred over a silent insert. Puts an app on screen; the event is NOT saved until the user confirms. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "title": {"type": "string", "description": "Event title."},
            "begin_ms": {"type": "integer", "description": "Start time, epoch millis."},
            "end_ms": {"type": "integer", "description": "End time, epoch millis. Defaults to one hour after begin."},
            "location": {"type": "string", "description": "Optional location."},
            "description": {"type": "string", "description": "Optional notes."},
        },
        "required": ["title", "begin_ms"],
    },
}

SHARE = {
    "name": "mobile_share",
    "description": "Open the Android share sheet with text, for the user to pick a destination. No permission. Puts the sheet on screen and nothing is sent until the user chooses. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "text": {"type": "string", "description": "Text to share."},
            "subject": {"type": "string", "description": "Optional subject line for destinations that use one."},
        },
        "required": ["text"],
    },
}

SETTINGS_OPEN = {
    "name": "mobile_settings_open",
    "description": "Deep-link a Settings page. This is the honest answer for everything the platform refuses to let an app change directly — Wi-Fi, Bluetooth, airplane mode — and the way to send the user to grant a special permission. Pages: wifi, bluetooth, airplane, location, accessibility, notification_listener, dnd_access, usage_access, write_settings, battery_optimization, app_details, date, sound, display. Puts Settings on screen; a human must finish. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "page": {"type": "string", "description": "Which Settings page to open."},
        },
        "required": ["page"],
    },
}

APP_UNINSTALL = {
    "name": "mobile_app_uninstall",
    "description": "Ask the system to uninstall a package. The user confirms in the system dialog; nothing is removed silently. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "package": {"type": "string", "description": "Package name from mobile_apps."},
        },
        "required": ["package"],
    },
}

# ---- L0 read surfaces ----

CALENDAR_LIST = {
    "name": "mobile_calendar_list",
    "description": "Upcoming calendar events, soonest first, as {event_id, title, begin_ms, end_ms, location, all_day, calendar}. Defaults to the next 7 days; narrow with from_ms/to_ms. Queries event *instances*, so a recurring meeting appears on the days it actually falls rather than as one series row. Private data: read the narrowest window that answers the question. Requires ARMED and the READ_CALENDAR grant.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "from_ms": {"type": "integer", "description": "Window start, epoch millis. Default now."},
            "to_ms": {"type": "integer", "description": "Window end, epoch millis. Default 7 days out."},
            "limit": {"type": "integer", "description": "Max events, 1..200. Default 20."},
        },
    },
}

LOCATION_LAST = {
    "name": "mobile_location_last",
    "description": "The most recent position the phone already holds, as {latitude, longitude, accuracy_m, provider, time_ms, age_ms, precise}. Does not request a new fix, so it is instant and costs no battery — but check age_ms: a stale fix is not where the phone is now. precise=false means only coarse permission was granted. Requires ARMED and a location grant.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

BATTERY = {
    "name": "mobile_battery",
    "description": "Battery state: {percent, charging, status, power_source, temperature_c, health}. No permission. Allowed while disarmed.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

POWER_STATE = {
    "name": "mobile_power_state",
    "description": "Power state: {interactive, power_save_mode, device_idle, ignoring_battery_optimizations}. Check `interactive` before planning a snapshot/tap run — driving UI on a phone with the screen off usually does nothing useful. No permission. Allowed while disarmed.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

THERMAL = {
    "name": "mobile_thermal",
    "description": "Thermal throttling: {status, level, throttling}. When throttling is true a long accessibility run will crawl, so prefer L0 tools or wait. No permission. Allowed while disarmed.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

APP_USAGE = {
    "name": "mobile_app_usage",
    "description": "Foreground time per app over a window, busiest first, as {package, foreground_ms, last_used_ms}. Defaults to the last 24h. Needs Usage access, a SPECIAL permission granted under Settings > Special app access > Usage access — an ungranted app gets an empty list from Android, so this reports permission_denied instead of claiming the user used nothing. Private data. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "since_ms": {"type": "integer", "description": "Window length in ms looking back. Default 86400000."},
            "limit": {"type": "integer", "description": "Max apps, 1..200. Default 20."},
        },
    },
}

DND_GET = {
    "name": "mobile_dnd",
    "description": "Read Do Not Disturb: {filter, policy_access_granted}. filter is all/priority/none/alarms. Allowed while disarmed.",
    "parameters": {"type": "object", "properties": {"device": DEV}},
}

DND_SET = {
    "name": "mobile_dnd_set",
    "description": "Set Do Not Disturb. mode=all (off) | priority | none (total silence) | alarms. Needs DND access, a SPECIAL permission the user grants in Settings — this checks first and names the page rather than letting the platform silently ignore the change. Silencing a phone can make the user miss something real: say what you are doing and why. Requires ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": DEV,
            "mode": {"type": "string", "enum": ["all", "priority", "none", "alarms"], "description": "Target filter."},
        },
        "required": ["mode"],
    },
}
