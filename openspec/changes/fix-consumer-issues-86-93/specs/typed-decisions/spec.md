## MODIFIED Requirements

### Requirement: The System One style is reachable at more than one endpoint

The System One style SHALL be configurable at any endpoint serving that wire, through the existing
`baseUrl`, `model` and `apiKeyEnv` options and no new API. Documentation SHALL name both TypeSafe's
first-party API and a gateway that serves the same wire, SHALL state that they are equivalent in
latency, and SHALL state which response fields are backend-specific.

Because those three options are valid only in specific combinations, every port SHALL additionally
expose a named `backend` preset — `typesafe` and `openrouter` — that sets `baseUrl`, `model` and
`apiKeyEnv` **as a unit**. The individual options SHALL remain available for a self-hosted origin,
and an explicitly supplied option SHALL override the preset's value for that option alone.

The default model SHALL remain the first-party API's own floating spelling; it is the only id that
endpoint serves. A classifier constructed with the gateway's base URL and an unqualified
first-party model id SHALL fail **at construction**, before any request is sent, with a message
naming the correct gateway spelling. A wrong-endpoint rejection that arrives as an "unknown model"
response is not an acceptable substitute for that message.

#### Scenario: The same questions through either endpoint

- **WHEN** a classifier is pointed at TypeSafe's own API rather than the gateway, by `baseUrl`, `model` and `apiKeyEnv` alone
- **THEN** the returned `Decision` has the same shape and the same typed accessors, differing only in the absent cost

#### Scenario: A runnable example works with either key or none

- **WHEN** the runnable judge example runs with `TYPESAFE_API_KEY`, with `OPENROUTER_API_KEY`, or with neither
- **THEN** it reports which backend it used and produces a decision, using the recorded `static` replay when no key is present

#### Scenario: A preset sets the three options together

- **WHEN** a host constructs a classifier naming only the gateway preset
- **THEN** the base URL, the model id and the credential environment variable name are all the gateway's, with no further configuration

#### Scenario: The known mismatch fails before the wire

- **WHEN** a host sets the gateway's base URL and leaves the first-party model id in place
- **THEN** construction fails with a message naming the gateway's own spelling for that model
- **AND** no request is sent

#### Scenario: An explicit option still wins over the preset

- **WHEN** a host names a preset and also supplies an explicit base URL
- **THEN** the explicit base URL is used and the preset's other values are unchanged

### Requirement: Credentials resolve at call time and are never logged

A `Classifier` SHALL read its credential from an environment variable **named** in its options,
resolved at call time. Custom headers SHALL expand `${ENV_VAR}` references from the environment at
call time, identically to remote MCP headers. No credential value SHALL appear in any log, error
message, or returned value.

This guarantee SHALL extend to what an error message may contain on **any** path, the client loop
included, and SHALL cover credential-adjacent data as well as the credential itself: an error
carrying a provider's response body SHALL have its account-identifier keys redacted and SHALL
carry no body at all for an authentication or authorisation status. A host SHALL NOT have to
re-implement that scrubbing to hold the guarantee.

#### Scenario: A failure names no secret

- **WHEN** a request fails with an authentication error
- **THEN** the raised error names the status and the endpoint
- **AND** contains no credential value and no expanded header value

#### Scenario: The guarantee covers the client loop's errors too

- **WHEN** a client-loop request fails with a provider body carrying an account identifier
- **THEN** the identifier is redacted before the message exists, by the library rather than by the host
