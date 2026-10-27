"""Migration must never turn a clean Hermes update into a source patch."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("companion_voice_install", Path(__file__).resolve().parents[1] / "voice_install.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VoiceInstallTests(unittest.TestCase):
    def test_clean_newer_core_is_untouched_even_with_native_to_thread(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'hermes_cli/web_routers').mkdir(parents=True)
            files = {
                root / 'hermes_cli/config.py': 'def load_config():\n    return _load_config_impl(want_deepcopy=True)\n',
                root / 'hermes_cli/web_routers/audio.py': 'async def run():\n    return await asyncio.to_thread(_scoped)\n',
            }
            for path, content in files.items():
                path.write_text(content)
            self.assertFalse(module.remove_overlay(root))
            for path, content in files.items():
                self.assertEqual(content, path.read_text())

    def test_unrecognized_changes_refuse_migration_before_writing_either_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'hermes_cli/web_routers').mkdir(parents=True)
            config = root / 'hermes_cli/config.py'
            clean = 'def load_config():\n    return _load_config_impl(want_deepcopy=True)\n'
            old = clean.replace('    return _load_config_impl(want_deepcopy=True)', '    from hermes_cli.audio_config_snapshot import apply_snapshot\n    return apply_snapshot(_load_config_impl(want_deepcopy=True))')
            config.write_text(old)
            config.with_suffix('.py.before-companion-voice').write_text(clean)
            audio = root / 'hermes_cli/web_routers/audio.py'
            audio.write_text('from hermes_cli.audio_config_snapshot import with_voice_snapshot, snapshot_scope\n\nunknown_user_change = True\n')
            with self.assertRaises(RuntimeError):
                module.remove_overlay(root)
            self.assertEqual(old, config.read_text())
