# Edge protocol compatibility

Source: https://github.com/rany2/edge-tts/tree/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac

Unmodified upstream drm.py, constants.py and exceptions.py. The companion plugin
uses this protocol implementation only for installed Edge clients still emitting
v1 tokens. No dependencies or files in the Hermes environment are replaced.
The Edge provider, endpoint, voice and speed remain unchanged. LGPLv3 license
is included. Newer installed clients using v2 are left alone.
