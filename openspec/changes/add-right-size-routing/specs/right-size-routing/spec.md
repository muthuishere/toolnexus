## ADDED Requirements

### Requirement: The caller-configured model id is routed unchanged (model faithfulness)
The client SHALL transmit the `model` id configured on `ClientOptions` (`toolnexus run --model`)
to the LLM endpoint **verbatim** on every turn of a run. It SHALL NOT rewrite, alias, truncate,
or substitute a default for a caller-supplied model. This guarantees that a route the operator
chose as "cheap" (e.g. `deepseek/deepseek-chat`) is the model actually billed, and is the
substrate the fleet's right-size routing depends on. This behavior is identical across all five
ports.

#### Scenario: A classify job routed to a cheap tier hits exactly that model
- **WHEN** a client configured with `model = "deepseek/deepseek-chat"`, `style = "openai"`, and a
  `baseUrl` pointed at an OpenAI-style endpoint runs a single classify/verify prompt
- **THEN** the request the endpoint receives carries `"model": "deepseek/deepseek-chat"`
  unchanged, and the run returns the model's answer text as the result

#### Scenario: Model id is not defaulted away when the caller supplied one
- **WHEN** the caller supplies a non-empty `model`
- **THEN** no built-in default model replaces it on any turn, for both `openai` and `anthropic`
  styles

### Requirement: An expensive-tier route can be gated before it runs
When a `beforeLLM` hook is configured, the client SHALL invoke it before each model call, passing
the `model` for that turn, and SHALL honor a hook that aborts the call (by raising/erroring in
the idiomatic way for the port). This is the documented seam for a cost/route gate: a host MAY
require a justification (e.g. shelling out to `brain check "<why this model>" --json` and
aborting unless the shield returns `guaranteed`) before an expensive-tier model is called. A
cheap-tier route with no gate, or with a gate that permits it, proceeds unchanged. toolnexus
SHALL NOT hard-depend on `brain`; the adapter is host-supplied.

#### Scenario: Gate blocks an expensive-tier route lacking a reason
- **WHEN** a `beforeLLM` gate is configured to abort when the model is an expensive-tier slug and
  no justification is provided, and a run is started on that expensive model
- **THEN** the model endpoint is never called and the run surfaces the gate's error

#### Scenario: Gate permits a cheap-tier route
- **WHEN** the same gate is configured and a run is started on a cheap-tier model it permits
- **THEN** the model call proceeds and the run returns the model's answer normally

### Requirement: The routing-tier registry contract is documented for fleet interop
`SPEC.md` SHALL document the routing-tier registry shape that `fleet-nexus` / `huddle-nexus`
consume to drive toolnexus: a config object exposing `base_url`, `style`, `api_key_env` (the
NAME of the env var holding the key — never the value), and a `models` map from job-class tier
(`classify_verify`, `route`, `aux_default`, `huddle_voice`, `fleet_agentic`) to model slug. The
documented contract SHALL state that toolnexus reads the key from the named env var at call time
and never logs it, consistent with the existing secrets rule.

#### Scenario: The documented shape matches what the fleet passes to the client
- **WHEN** a fleet skill reads `{base_url, style, api_key_env, models.<tier>}` from the registry
  and constructs a client with `baseUrl = base_url`, `style = style`, `model = models.<tier>`
- **THEN** those fields map one-to-one onto `ClientOptions` and the run executes against the
  chosen tier without toolnexus needing to read the registry file itself
