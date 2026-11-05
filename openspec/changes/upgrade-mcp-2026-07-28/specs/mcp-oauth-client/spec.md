## ADDED Requirements

### Requirement: Discover and obtain a token on 401
On HTTP 401 the client SHALL read Protected Resource Metadata (the `WWW-Authenticate` `resource_metadata` URL, else the well-known path), then the authorization server's metadata (RFC 8414, else OIDC discovery), and run authorization code with PKCE S256 and the `resource` parameter. It SHALL validate `state` and, when the server advertises it, `iss`. It SHALL then re-send the original request with the token. Tokens SHALL never be logged.

#### Scenario: Zero-config login
- **WHEN** a configured server answers 401 and its AS supports CIMD
- **THEN** the client logs in with its CIMD URL as client_id and the original call returns its result

#### Scenario: Mix-up attack rejected
- **WHEN** the callback's `iss` differs from the expected issuer
- **THEN** the login fails and no token is stored

### Requirement: Client identity without DCR
The client SHALL use, in order: a pre-registered `clientId` bound to the issuer, then a Client ID Metadata Document when the AS advertises support. It SHALL NOT perform Dynamic Client Registration. When neither applies it SHALL fail with an error naming the issuer and telling the user to register an app and set `clientId`.

#### Scenario: GitHub needs a registered app
- **WHEN** the AS supports neither CIMD nor has a configured clientId
- **THEN** the error says to register an app with that issuer and set clientId

### Requirement: Two authorization modes
The client SHALL support automatic mode (open a browser, receive the callback on `127.0.0.1:<random>/callback`) and pause mode, which returns a SPEC §10 `authorization` pending carrying the URL; resuming it with the callback URL SHALL complete the original call exactly once.

#### Scenario: Web app pause and resume
- **WHEN** pause mode is configured and the user approves via the pending URL
- **THEN** resuming with the callback URL returns the original tool result and the tool ran once

### Requirement: Refresh, step-up and provider tolerance
The client SHALL refresh expired tokens (one refresh for concurrent callers), re-authorize for the added scope on 403 `insufficient_scope`, send `Accept: application/json` to the token endpoint and accept a form-encoded reply, and allow omitting `resource` on refresh per server.

#### Scenario: GitHub form-encoded token response
- **WHEN** the token endpoint replies `application/x-www-form-urlencoded`
- **THEN** the token is parsed and used

#### Scenario: Concurrent expiry
- **WHEN** 100 calls hit an expired token at once
- **THEN** exactly one refresh request reaches the AS and all calls succeed
