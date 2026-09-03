"""Tool schemas the model sees. Keep the set small."""

STATUS = {
    "name": "mobile_status",
    "description": "Paired Android device status: armed, foreground app. Fails if unpaired.",
    "parameters": {"type": "object", "properties": {}},
}

SNAPSHOT = {
    "name": "mobile_snapshot",
    "description": "Accessibility tree of the current screen with @eN refs. Do not use on banking or authenticator apps.",
    "parameters": {
        "type": "object",
        "properties": {
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
        "properties": {"text": {"type": "string"}},
        "required": ["text"],
    },
}

PRESS = {
    "name": "mobile_press",
    "description": "Hardware/global action: back, home, recents.",
    "parameters": {
        "type": "object",
        "properties": {
            "key": {"type": "string", "enum": ["back", "home", "recents"]},
        },
        "required": ["key"],
    },
}
