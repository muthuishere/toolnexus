## ADDED Requirements

### Requirement: Classifier key goes only to its own host

When the caller has not named `apiKeyEnv`, the classifier SHALL read the backend's own key variable only if the dialled endpoint, after `${NAME}` expansion, is that backend's own host over https (the host of the preset `baseUrl`, compared case-insensitively; `openrouter` also `*.openrouter.ai`). On any other host, over plain http, and always for `custom`, it SHALL send no `Authorization` header and SHALL NOT fail. A caller-named `apiKeyEnv` SHALL be sent wherever `baseUrl` points, and caller `headers` SHALL be sent as given.

#### Scenario: custom never gets the TypeSafe key
- **WHEN** `TYPESAFE_API_KEY` is set and a `custom` classifier calls a local server
- **THEN** the request carries no `Authorization` header

#### Scenario: overridden baseUrl
- **WHEN** a `typesafe` classifier has `baseUrl` on another host and no `apiKeyEnv`
- **THEN** the request carries no `Authorization` header

#### Scenario: look-alike host
- **WHEN** the endpoint is `https://api.typesafe.ai@evil.example/v1`
- **THEN** no key is resolved

#### Scenario: caller names the variable
- **WHEN** `apiKeyEnv` is `MY_KEY` on a `custom` classifier and `MY_KEY` is set
- **THEN** the request carries `Authorization: Bearer <value of MY_KEY>`
