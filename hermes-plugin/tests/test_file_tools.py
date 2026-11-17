from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _isolate  # noqa: F401,E402
from broker import Broker, BrokerError, MockDevice
from schemas import FILES_ACCESS, FILES_ENABLE, FILES, FILE_PULL, FILE_PUSH
from tickets import ALLOWLIST as TICKET_ALLOWLIST
from tools import make_handlers


class FileToolsTests(unittest.TestCase):
    def setUp(self):
        self.files = {"/phone/source": bytes(range(256)) * 1800}
        self.calls = []
        self.tick = 0
        self.corrupt = False
        self.device = MockDevice(armed=True, a11y_bound=False, handler=self.phone)
        self.broker = Broker(device=self.device, clock=self.clock)
        self.handlers = make_handlers(self.broker)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.local = Path(self.tmp.name) / "file.bin"

    def clock(self):
        self.tick += 0.2
        return self.tick

    def metadata(self, path):
        if path not in self.files:
            raise BrokerError("not_found", path)
        data = self.files[path]
        return {"path": path, "size": len(data), "directory": False, "version": f"{len(data)}:1"}

    def phone(self, action, args):
        self.calls.append((action, dict(args)))
        if action == "device.files.access":
            return {"granted": True, "roots": ["/phone"]}
        if action == "device.files.enable":
            return {"settings_opened": True}
        op, path = args["action"], args["path"]
        if op == "stat":
            return self.metadata(path)
        if op == "hash":
            return {**self.metadata(path), "sha256": "bad" if self.corrupt else hashlib.sha256(self.files[path]).hexdigest()}
        if op == "read":
            meta = self.metadata(path)
            offset = args["offset"]
            data = self.files[path][offset:offset + args["max_bytes"]]
            return {**meta, "offset": offset, "data_base64": base64.b64encode(data).decode()}
        if op == "write":
            if path in self.files and not args.get("overwrite"):
                raise BrokerError("already_exists", path)
            self.files[path] = base64.b64decode(args["data_base64"])
            return self.metadata(path)
        if op == "append":
            if len(self.files[path]) != args["offset"]:
                raise BrokerError("file_changed", path)
            self.files[path] += base64.b64decode(args["data_base64"])
            return self.metadata(path)
        if op == "move":
            target = args["destination"]
            if target in self.files and not args.get("overwrite"):
                raise BrokerError("already_exists", target)
            self.files[target] = self.files.pop(path)
            return self.metadata(target)
        if op == "delete":
            self.files.pop(path, None)
            return {"deleted": True}
        raise AssertionError(op)

    def transfer(self, tool, path, **extra):
        with patch("file_tools.time.sleep"):
            return json.loads(self.handlers[tool]({"device": "phone-id", "path": path, "local_path": str(self.local), **extra}))

    def test_registered_schemas_and_ticket_support(self):
        for schema in (FILES_ACCESS, FILES_ENABLE, FILES, FILE_PULL, FILE_PUSH):
            self.assertIn(schema["name"], self.handlers)
            self.assertIn(f"  - {schema['name']}\n", (Path(__file__).resolve().parents[1] / "plugin.yaml").read_text())
        self.assertTrue({"device.files", "device.files.access", "device.files.enable"} <= TICKET_ALLOWLIST)

    def test_disarmed_status_only_and_protected_paths_remain_blocked(self):
        self.device.armed = False
        self.assertTrue(json.loads(self.handlers["mobile_files_access"]({}))["ok"])
        for action in ("list", "stat", "read", "write", "append", "mkdir", "copy", "move", "delete", "hash"):
            result = json.loads(self.handlers["mobile_files"]({"action": action, "path": "/phone/source"}))
            self.assertEqual(result["error"]["code"], "disarmed")
        self.assertEqual(self.transfer("mobile_file_pull", "/phone/source")["error"]["code"], "disarmed")
        self.assertFalse(self.local.exists())
        self.device.armed = True
        self.broker.extra_protected = ("com.private.*",)
        for field in ("path", "destination"):
            result = json.loads(self.handlers["mobile_files"]({"action": "copy", "path": "safe", field: "/sdcard/foo/../Android/media/com.private.app/file"}))
            self.assertEqual(result["error"]["code"], "protected_package")

    def test_pull_and_push_multichunk_binary_without_exposing_bytes(self):
        result = self.transfer("mobile_file_pull", "/phone/source")
        self.assertTrue(result["ok"], result)
        self.assertEqual(self.local.read_bytes(), self.files["/phone/source"])
        self.assertNotIn("data_base64", json.dumps(result))
        result = self.transfer("mobile_file_push", "/phone/dest")
        self.assertTrue(result["ok"], result)
        self.assertEqual(self.files["/phone/dest"], self.local.read_bytes())
        self.assertFalse(any(".hermes-upload-" in path for path in self.files))
        self.assertTrue(all(args.get("_device") == "phone-id" for action, args in self.calls if action == "device.files"))
        self.assertGreater(sum(args.get("action") == "read" for _, args in self.calls), 1)

    def test_existing_destinations_survive_and_explicit_overwrite_works(self):
        self.local.write_bytes(b"host kept")
        self.assertEqual(self.transfer("mobile_file_pull", "/phone/source")["error"]["code"], "already_exists")
        self.assertEqual(self.local.read_bytes(), b"host kept")
        self.files["/phone/dest"] = b"phone kept"
        self.assertEqual(self.transfer("mobile_file_push", "/phone/dest")["error"]["code"], "already_exists")
        self.assertEqual(self.files["/phone/dest"], b"phone kept")
        self.assertTrue(self.transfer("mobile_file_push", "/phone/dest", overwrite=True)["ok"])
        self.assertEqual(self.files["/phone/dest"], b"host kept")

    def test_checksum_failure_publishes_nothing_and_removes_stages(self):
        self.corrupt = True
        result = self.transfer("mobile_file_pull", "/phone/source")
        self.assertEqual(result["error"]["code"], "file_changed")
        self.assertEqual(list(Path(self.tmp.name).iterdir()), [])
        self.local.write_bytes(b"upload")
        result = self.transfer("mobile_file_push", "/phone/dest")
        self.assertEqual(result["error"]["code"], "file_changed")
        self.assertEqual(list(self.files), ["/phone/source"])

    def test_disarm_during_upload_preserves_destination_and_reports_blocked_cleanup(self):
        self.local.write_bytes(b"x" * 300_000)
        self.files["/phone/dest"] = b"kept"
        original = self.device.handler
        def phone(action, args):
            result = original(action, args)
            if args.get("action") == "write":
                self.device.armed = False
            return result
        self.device.handler = phone
        result = self.transfer("mobile_file_push", "/phone/dest", overwrite=True)
        self.assertEqual(result["error"]["code"], "disarmed")
        self.assertIn("temporary phone file may remain", result["error"]["message"])
        self.assertEqual(self.files["/phone/dest"], b"kept")

    def test_empty_file_round_trip(self):
        self.local.write_bytes(b"")
        self.assertTrue(self.transfer("mobile_file_push", "/phone/empty")["ok"])
        self.local.unlink()
        self.assertTrue(self.transfer("mobile_file_pull", "/phone/empty")["ok"])
        self.assertEqual(self.local.read_bytes(), b"")


if __name__ == "__main__":
    unittest.main()
