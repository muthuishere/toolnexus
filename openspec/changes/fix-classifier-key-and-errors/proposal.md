## Why

Issue #155: the classifier sends the env-default key (`TYPESAFE_API_KEY` for `custom` and for any `baseUrl`
override) to whatever host `baseUrl` names. The §8 client fixed the same hazard in 0.22.0; the classifier
path never applied it. Issue #156: a failed Cloudflare envelope drops `errors[0]`, so a missing balance
looks like a bad request.

## What Changes

- **BREAKING (security)**: a backend's own key variable is sent only to that backend's own https host;
  `custom` has no default key; a caller-named `apiKeyEnv` still goes where `baseUrl` points.
- A failed Cloudflare envelope reports `: <code> <message>` of `errors[0]`, redacted and capped (the "Cloudflare envelope" requirement is amended in place in `add-classifier-backends`, which is not yet archived).
- SPEC §8B gains *Key to host*; presets table `custom` key becomes none.

## Impact

All eight ports; fixtures `examples/judge/backends/key-routing.json`, `cloudflare-replies.json`,
`presets.json`. Amends `add-classifier-backends` (shipped 0.26.0).
