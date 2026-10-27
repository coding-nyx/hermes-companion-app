# Retired native source overlay

Voice host support now ships entirely in the [Companion plugin](../hermes-plugin/README.md).
Install/update it with `bash hermes-plugin/install.sh`. Do not patch Hermes core.

The plugin's `voice_install.py` can retire the original overlay once, refusing to
replace unrecognized source changes. Recovery copies are retained under
`$HERMES_HOME/state/companion-voice-legacy/`. Clean Hermes installations and updates
are not rewritten. The supported dashboard plugin API loader mounts the extension.
