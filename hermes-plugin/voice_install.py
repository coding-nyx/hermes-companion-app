"""Verify native compatibility and retire the old source overlay once, without re-patching.

Normal plugin installs/updates copy only plugin files. Hermes upgrades/reinstalls
load dashboard/api.py through the supported enabled-plugin API hook.
"""
from pathlib import Path
import argparse
import importlib.util
import sys
import os
import shutil


def native_root():
    spec = importlib.util.find_spec('hermes_cli')
    if spec is None or not spec.submodule_search_locations:
        raise RuntimeError('Hermes is unavailable to this interpreter; set HERMES_VENV')
    return Path(next(iter(spec.submodule_search_locations))).parent


def remove_overlay(root):
    targets = [root / 'hermes_cli/config.py', root / 'hermes_cli/web_routers/audio.py']
    planned = []
    for path in targets:
        text = path.read_text()
        if 'hermes_cli.audio_config_snapshot' not in text and 'from hermes_cli.web_routers.audio_settings import router as settings_router' not in text:
            continue  # Clean installs/upgrades must never be rewritten.
        backup = path.with_suffix('.py.before-companion-voice')
        if path.name == 'config.py':
            for flag in ('True', 'False'):
                text = text.replace(f'    from hermes_cli.audio_config_snapshot import apply_snapshot\n    return apply_snapshot(_load_config_impl(want_deepcopy={flag}))', f'    return _load_config_impl(want_deepcopy={flag})')
        else:
            text = text.replace('from hermes_cli.audio_config_snapshot import with_voice_snapshot, snapshot_scope\n\n', '')
            text = text.replace('\nfrom hermes_cli.web_routers.audio_settings import router as settings_router\nrouter.include_router(settings_router)\n', '')
            text = text.replace('@with_voice_snapshot\n', '')
            text = text.replace(', voice_snapshot: Optional[str] = None', '')
            text = text.replace('return await asyncio.to_thread(_scoped)', 'return await asyncio.get_running_loop().run_in_executor(None, _scoped)')
            text = text.replace('with snapshot_scope(profile, ws.query_params.get("voice_snapshot")), _config_profile_scope(profile):', 'with _config_profile_scope(profile):')
        if text != path.read_text():
            if not backup.exists() or text != backup.read_text():
                raise RuntimeError(f'Refusing to overwrite changed Hermes source: {path}; reconcile the legacy overlay first')
            planned.append((path, text))
    # Validate every file before touching either one. Backups remain for recovery.
    for path, text in planned:
        path.write_text(text)
        print(f'Restored clean native source: {path}')
    return bool(planned)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--enable-profile', action='append', default=[])
    args = parser.parse_args()
    root = args.source_root or native_root()
    # Source checkouts include Hermes's own top-level helper modules here.
    sys.path.insert(0, str(root))
    if not (root / 'hermes_cli/web_server_dashboard.py').is_file():
        raise RuntimeError('This Hermes version has no native dashboard plugin API loader')
    remove_overlay(root)
    # Retire only our recognizable, untracked overlay artifacts. Keep a recovery
    # copy outside the Hermes source tree and outside rsync's plugin destination.
    archive = Path(os.environ.get('HERMES_HOME', str(Path.home() / '.hermes'))) / 'state/companion-voice-legacy'
    legacy = {
        'hermes_cli/audio_config_snapshot.py': 'Context-local speech snapshots.',
        'hermes_cli/web_routers/audio_settings.py': 'Native Hermes audio settings:',
        'tests/hermes_cli/test_audio_settings.py': 'Run in Hermes with scripts/run_tests.sh',
    }
    for relative, marker in legacy.items():
        path = root / relative
        if path.is_file() and marker in path.read_text()[:400]:
            dest = archive / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists() and dest.read_bytes() != path.read_bytes():
                raise RuntimeError(f'Refusing to overwrite a previous migration backup: {dest}')
            shutil.move(str(path), str(dest))
    for relative in ('hermes_cli/config.py', 'hermes_cli/web_routers/audio.py'):
        backup = (root / relative).with_suffix('.py.before-companion-voice')
        if backup.is_file():
            dest = archive / backup.relative_to(root)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.exists():
                shutil.move(str(backup), str(dest))
    # Native API trust is profile-scoped. An installed plugin must also be
    # explicitly enabled for each requested profile, not just the machine root.
    if args.enable_profile:
        from hermes_cli.web_server import _CONFIG_MUTATION_LOCK
        from hermes_cli.web_server_profiles import _profile_scope
        from hermes_cli.config import require_readable_config_before_write, save_config
        for profile in args.enable_profile:
            with _profile_scope(profile), _CONFIG_MUTATION_LOCK:
                cfg = require_readable_config_before_write()
                plugins = cfg.setdefault('plugins', {})
                enabled = plugins.setdefault('enabled', [])
                if 'hermes-companion' not in enabled:
                    enabled.append('hermes-companion')
                plugins['disabled'] = [name for name in plugins.get('disabled', []) if name != 'hermes-companion']
                save_config(cfg)
            print(f'Companion enabled for profile: {profile}')
    print('Companion voice ships in dashboard/api.py; no Hermes source patch is installed.')


if __name__ == '__main__':
    main()
