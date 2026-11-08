## ADDED Requirements

### Requirement: CLI presets load as styles
`loadMediaStyle` SHALL accept the preset names `xai-grok-cli`, `openai-codex-cli`, `google-agy-cli`, `xai-grok-cli-mock`, `openai-codex-cli-mock` and `google-agy-cli-mock` and return a style backed by the matching CLI client with the given config, and `createMedia` SHALL accept any loaded style wherever it accepts a client.

#### Scenario: Preset verbs
- **WHEN** the `verbCases` of `conformance/media/cli.json` are replayed
- **THEN** `verbs()` is `["image","video"]` for `xai-grok-cli` and `["image"]` for `openai-codex-cli` and `google-agy-cli`, and the tool names match

#### Scenario: Unsupported verb on styles
- **WHEN** `video` is generated on media built only from `openai-codex-cli`
- **THEN** it fails with `media: no style supports "video"` and no process is started

### Requirement: Size hints fold into the CLI prompt
A CLI style SHALL accept `width`, `height`, `aspectRatio` (image) and `seconds`, `aspectRatio` (video) and append them to the prompt as ` (h1, h2)` in canonical order, rendered `width <n> px`, `height <n> px`, `about <n> seconds long`, `aspect ratio <v>`.

#### Scenario: Aspect ratio on Grok
- **WHEN** case `grok_size_hints_folded_into_prompt` is replayed
- **THEN** the CLI receives `… a red apple (width 1024 px, aspect ratio 16:9). Do not write code. …` and no "not supported" error is raised

### Requirement: Mock presets and one-shot generation
Each `-mock` preset SHALL behave like its preset without starting a process, returning the fixed file `<gather.dir>/<label>-mock-<verb>.<png|mp4>`, text equal to the templated prompt and jobId `mock`; `generateMedia(style, verb, args, config?)` SHALL equal `createMedia(loadMediaStyle(style, config)).generate(verb, args)`.

#### Scenario: Mock Grok image
- **WHEN** case `mock_grok_cli_mock_image` is replayed, also through `generateMedia`
- **THEN** both return the same file, text and jobId `mock`
