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
    "description": "Accessibility tree of the current screen with @eN refs. Do not use on banking or authenticator apps.",
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
    "description": "Tap a node from the last snapshot (ref) or a point in screenshot space (x, y).",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name. Optional when exactly one device is connected or a default is set."},
            "ref": {"type": "string"},
            "x": {"type": "number"},
            "y": {"type": "number"},
        },
    },
}

TYPE = {
    "name": "mobile_type",
    "description": "Type into the focused field. Never log the text.",
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
    "description": "Scroll the screen or a snapshot node. direction: up, down, left, right.",
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
    "description": "Launch a package from a prior mobile_apps list. Blocked on protected packages.",
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
    "description": "Pause up to 5 seconds while ARMED.",
    "parameters": {
        "type": "object",
        "properties": {
            "device": {"type": "string", "description": "Device id or friendly name."},"ms": {"type": "integer", "minimum": 0, "maximum": 5000}},
        "required": ["ms"],
    },
}

ARM = {
    "name": "mobile_arm",
    "description": "Arm the paired Android companion so mobile_* gestures run. Fails if accessibility is off. Phone still fail-closes without a11y.",
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
