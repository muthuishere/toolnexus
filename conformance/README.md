# Conformance checks

Cross-port guards that run in CI, independent of any single language suite.

## Option parity (`check_options_parity.py`)

Asserts every logical `ClientOptions` / `ToolkitOptions` field in `options_manifest.json`
exists in all six ports (matched by a normalized alias, so `onError`/`on_error`/`OnError` and
the `fetch`/`http_transport`/`HTTPClient` http-client aliases all count as one logical option).

This guards the class of silent drift where an option ships in five ports but not the sixth —
e.g. `disableTools`/`disableSkills` sat in five ports while the JS reference lacked them,
undetected until found by hand. **Add an option => add a row to the manifest for all six ports.**

```sh
python3 conformance/check_options_parity.py   # exit 0 = parity holds
```
