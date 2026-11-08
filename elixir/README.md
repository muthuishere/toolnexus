# toolnexus (Elixir)

Your LLM, with MCP tools and agent skills built in — in 3 lines, on the BEAM.

`toolnexus` unifies every tool source an agent needs — **MCP servers** (stdio + streamable-HTTP),
**agent skills** (`SKILL.md` folders), your own functions, HTTP endpoints, ten built-in shell/file
tools, and remote **A2A agents** — behind one uniform `Tool`, emits the schema in **OpenAI /
Anthropic / Gemini** formats, and ships a unified client with a built-in tool-calling loop.

The Elixir port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Python, Go, Java, C#, Clojure and Rust**. The MCP client is
implemented in-house on OTP (supervised connections, no third-party MCP SDK), which is also why
this port ships the **full** elicitation bridge (form *and* URL mode).

## Install

```elixir
def deps do
  [{:toolnexus, "~> 0.27"}]
end
```

## Zero to agent

```elixir
{:ok, toolkit} = Toolnexus.create_toolkit(mcp_config: "mcp.json", skills_dir: ["skills"])
client = Toolnexus.Client.create(base_url: System.get_env("OPENAI_BASE_URL"),
                                 style: "openai", model: "gpt-4.1",
                                 api_key: System.get_env("OPENAI_API_KEY"))
result = Toolnexus.Client.run(client, "What tools do you have? Use one.", toolkit)
IO.puts(result.text)
```

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` / `servers` / `mcp` keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files, loaded on demand through one `skill` tool.
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never logged.

## API key by host (BREAKING in 0.22.0)

With no `api_key`, the client reads only `api_key_env` (the **name** of an env var) or the env
var that belongs to the `base_url` host, over https: `api.openai.com` → `OPENAI_API_KEY`,
`api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`. There is no
cross-provider fallback any more, so **a local or custom `base_url` must set `api_key` or
`api_key_env`**, or the run raises before any request:

```elixir
Toolnexus.Client.create(base_url: "http://localhost:11434/v1", model: "llama3", api_key: "unused")
Toolnexus.Client.create(base_url: "https://gateway.example/v1", model: "m", api_key_env: "GATEWAY_KEY")
```

## Prompt cache and tool tiers

```elixir
client =
  Toolnexus.Client.create(
    base_url: "https://api.anthropic.com", style: "anthropic", model: "claude-sonnet-4-5",
    prompt_cache: %{long_ttl: false},                    # cache breakpoints (anthropic) / prompt_cache_key (openai)
    tool_tiers: %{core: ["read", "grep"], deferred: []}  # other tools load via tool_search / call_tool
  )

result = Toolnexus.Client.run(client, "fix the failing test", toolkit)
result.usage[:cache_read_tokens]           # present when the provider reports cache hits
Toolnexus.Client.tool_tier_plan(client, toolkit)   # %{"read" => :core, "bash" => :listed, ...}
Toolnexus.Client.reset_tool_tiers(client, toolkit) # rebuild the frozen plan at a session start
```

The tools array never changes mid-session, so the cached prefix holds even when tools are added
later. Runnable: `mix run examples/prompt_cache_tiers.exs` (hermetic, no key needed).

## Host additions (0.22.0)

- `Toolnexus.Mcp.load(cfg, on_server: fn %{name, status, tools, error} -> ... end)` — use each
  MCP server as soon as it is ready; `Toolnexus.Mcp.resource_tools/1` lists and reads MCP resources.
- `wait_for_ctx: fn ctx, request -> answer end` — `ctx.signal` is the turn's pid, so a pending
  question ends with the turn.
- Tool `hints` (`%Toolnexus.ToolHints{read_only, destructive, open_world}`) on builtins, MCP
  annotations and every `before_tool` event — a permission gate is a `before_tool` hook.
- Opt-in builtins: `background: Toolnexus.Builtin.BackgroundShells.new()` (`bash_output`,
  `kill_shell`), `web_search: fn query, limit -> ... end`, `notebook_edit: true`; `grep`
  `context` / `before` / `after`; truncation in `read` / `grep` metadata.
- Compactor `on_compact` (counts, summary, `folded` messages); `Toolnexus.Agents.as_tool_with/2`
  and runtime `:wait_for`; `ctx.call_id` on every tool call; `Toolnexus.Client.list_models/1`.

## Simple judgments

`Toolnexus.Judge` is a thin layer over any `Toolnexus.Classifier` (SPEC §8B); the wire is unchanged.

```elixir
import Toolnexus.Judge

st = state("You are Donkey Kong, you want to win.", %{message_received: "jump off the stage now"})

{:ok, answers} =
  ask(classifier, st, [
    noul(:is_appropriate, "Does message_received contain harmful language or topics?"),
    noul(:does_this_help, "Does message_received help Donkey Kong win?")
  ])

answers["is_appropriate"].band            #=> :yes | :no | :uncertain (cut-points 0.30 / 0.70, exclusive)
Toolnexus.Judge.Answer.value(answers["does_this_help"])

{:ok, outcome} =
  gate(classifier, st, questions, [%{question: "fixable", below: 0.3, action: "fail"}])
# outcome.escalated => a §10 `input` Request instead of a guess
```

The role lives in the state, and each question names the state field it judges. Also: `Policy`
(`default`, `skip_uncertain`), `Tape` (record / replay by call name), `Judge.static/4` (one-line
static classifier), and `Classifier.evaluate_batch/4` (many states, state order, fail-closed, 16 in flight).

Batteries (SPEC §8B): `Toolnexus.Judge.ToolGuard`, `ToolRelevance`, `SkillRelevance`,
`ToolResultFilter`, `IsComplete`, `AgentRouter`, `ContentGuard`, `ModelRouter` — each a typed
verdict from `check`/`select`/`filter`/`pick`, and `as_hook(battery, next)` where a hook seam
exists. A `before_llm` hook may also return `%{model: "id"}` to override the model for that turn.

### Backends, images, cache and helpers

Proposed defaults, owner to confirm (`openspec/changes/add-classifier-backends`, SPEC §8B).

```elixir
alias Toolnexus.Classifier
alias Toolnexus.Classifier.{MemoryCache, Thresholds}
import Toolnexus.Judge

# Backends are data: Classifier.backends() — typesafe (default) | openrouter
# (typesafe/jev-1.14) | cloudflare | vercel | custom. ${NAME} in base_url expands at
# call time; errors name the endpoint unexpanded. custom needs base_url + supports.
{:ok, c} = Classifier.create(backend: "openrouter", cache: MemoryCache.new(3_600_000, 1000))

# A ContentPart image in the state is lifted into `assets` (PNG/JPEG, 280..1568 px
# a side, 16 MiB each, 32 MiB a request; never resized). A backend whose row says no
# images fails BEFORE the network with %Classifier.Unsupported{}; `supports:` overrides.
photo = Toolnexus.ContentPart.image!("listing.png")

{:ok, d} = c |> Classifier.state(%{"photo" => photo}) |> Classifier.evaluate([noul(:red, "Is the photo's object red?")])
d.cached # true when served from the cache, no request sent

# Batch: per-item %BatchResult{decision | error}, state order, 16 in flight.
{:ok, results} = c |> Classifier.states(["a", "b"]) |> Classifier.evaluate([noul(:q, "…")])
```

- `state(v) |> evaluate(qs)` validates the reply fail-closed (`validate_decision/2`); plain
  `evaluate/3` keeps its old behaviour and does not.
- The cache is opt-in (`%{get:, set:}` or `MemoryCache`), per request, keyed on the SERVED model,
  and stores only valid replies. Key bytes match every port (`model_cache_key/2`,
  `decision_cache_key/5`).
- Helpers: `validate_decision/2`, `Thresholds.band/2` (inclusive 0.8 / 0.2 / 0.6; `ask`/`gate` keep
  their bands), `chunk_questions/2`, `Judge.raw/4` (an unmodelled question type; unknown answer
  types decode to `%RawAnswer{}`).
- `classifier.evaluate` metric events carry `questions`, `questions_hash` and `cached`.
- `style: "llm"` is **deprecated**: it still works unchanged for at least one minor release, with
  no runtime warning.

## MCP 2026-07-28 + OAuth

Every MCP server is probed with `server/discover` first (SPEC §2.2). A server on
`2026-07-28` is used directly — no `initialize`, no session, stdio and HTTP, MRTR input
requests through `wait_for` — and anything older drops to the existing (frozen) client.
Nothing in `mcp.json` has to change.

```elixir
tk =
  Toolnexus.Toolkit.build(
    mcp_config: %{
      "mcpServers" => %{
        "docs" => %{
          "url" => "https://mcp.example.com/mcp",
          "auth" => %{"clientId" => "my-registered-app"},   # or clientMetadataUrl (CIMD); never DCR
          "toolSearch" => true,                             # expose docs_search_tools + docs_call_tool
          "description" => "the product docs"
        }
      }
    },
    wait_for: &MyHost.wait_for/1,                # MRTR input + auth "pause" answered here
    token_store: MyHost.TokenStore.new(),        # optional; default in-memory per load
    open_url: &MyHost.open/1                     # optional; "auto" mode browser opener
  )
```

- `auth.mode` is `"auto"` (browser + loopback callback) or `"pause"` (a §10
  `kind:"authorization"` request; answer it with `data: %{"callbackUrl" => url}`).
  Without `wait_for`, a pause during load marks the server `failed` (`authorization
  pending`); during a tool call it returns a §10 pending result, resumed by the next
  execution carrying `ctx.answer`.
- Remote skills (the MCP skills extension) join the same `skill` tool as local skills.
- Tokens are never logged; `inspect/1` on a `Toolnexus.Mcp.Token` is redacted.
- **Gap:** the frozen fallback gets the bearer token as a static `Authorization` header
  when it starts; a 401 *later* on the fallback path does not re-run the OAuth flow.

## Why the BEAM port

Long-running agents want supervision. Every MCP connection is a supervised process; a crashed
stdio server is isolated (status `"failed"`) without taking your toolkit down; parallel tool calls
ride `Task.async_stream`. Same contract as the other six ports, native OTP underneath.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```elixir
alias Toolnexus.Media

{:ok, style} = Media.load_style("examples/media-styles/a2e.json")
media = Media.new(style, on_progress: &IO.inspect/1)
{:ok, img} = Media.image(media, %{prompt: "a red fox in fresh snow", width: 1024, height: 768})
{:ok, vid} = Media.video(media, %{prompt: "the fox blinks", image: hd(img.files).url, seconds: 5})

{:ok, tk} = Toolnexus.create_toolkit(media: media)    # generate_image, …
res = Toolnexus.Client.run(client, "Draw a fox, then animate it.", tk)
for f <- res.files, do: IO.puts("#{f.from} #{f.mime_type} #{f[:url]}")
```

## Embeddable MCP handler

`Toolnexus.mcp_handler(toolkit, cfg)` is a `Plug` you mount on **your** listener (Bandit, Phoenix),
behind your own TLS, CORS and OAuth. Every policy is a hook: `authenticate` (401 +
`www_authenticate`), `list_tools` per request (a name outside it is never executed), `before_call`
(`:allow` / `{:deny, msg}` / `{:confirm, msg}` — confirm elicits on the same POST's SSE stream when
the client can), `on_call`, `server_info`, resources/prompts, pluggable `session_ids`, `stateless`.
`Toolnexus.McpHandler.notify_tools_changed/1` pushes `tools/list_changed` to open GET streams.
SPEC §7C.1; runnable: `mix run examples/mcp_handler.exs`.

```elixir
handler =
  Toolnexus.mcp_handler(toolkit,
    authenticate: fn conn -> MyAuth.verify(conn) end,          # {:ok, identity} | {:error, _}
    list_tools: fn ctx -> MyTools.for(Toolnexus.McpHandler.identity(ctx)) end,
    before_call: fn _ctx, tool, _args ->
      if tool.annotations && tool.annotations.destructive_hint, do: {:confirm, "Sure?"}, else: :allow
    end
  )

Bandit.start_link(plug: {Toolnexus.McpHandler, handler}, port: 4000)
```

Tools may also return `%ToolResult{structured: map, meta: map}` (→ `structuredContent` / `_meta`)
and declare `output_schema` / `annotations`; absent, the wire is unchanged.

### MCP 2026-07-28 (opt-in)

List `"2026-07-28"` in `protocol_versions` (alone, or next to `"2025-06-18"` for both wires on one
mount) and the handler also speaks the stateless 2026 wire: `server/discover`, header validation,
`resultType` + `_meta` serverInfo, and MRTR — a `{:confirm, msg}` or a tool's §10 suspension
answers `input_required`, and the retry's answer reaches the tool as `ctx.answer`. `initialize`
never negotiates 2026-07-28; without it in the list the handler is unchanged. `list_skills`
publishes skills (`skills/list`, `skills/get`, `skill://` reads); `Toolnexus.load_mcp_skills/1`
builds them from a directory. `Toolnexus.McpServerAuth` makes the handler an OAuth protected
resource: bearer JWT (RS256, JWKS discovered from the issuer) or your own `verify`, per-tool
scopes, exact 401/403 challenges, and an RFC 9728 metadata plug. SPEC §7C.2.

```elixir
auth = Toolnexus.McpServerAuth.new(issuer: "https://login.example", scopes: %{"*" => ["mcp"], "wipe" => ["mcp:admin"]})

handler =
  Toolnexus.mcp_handler(toolkit,
    protocol_versions: ["2026-07-28", "2025-06-18"],
    authenticate: Toolnexus.McpServerAuth.authenticator(auth),  # identity = %Toolnexus.McpPrincipal{}
    list_skills: fn _ctx -> Toolnexus.load_mcp_skills("skills") end
  )
# mount {Toolnexus.McpHandler, handler} at /mcp and
# Toolnexus.McpServerAuth.metadata(auth) at /.well-known/oauth-protected-resource[/mcp]
```

Bring your own authorization server: `Toolnexus.McpServerAuth` verifies its tokens. The auto-approving dev
authorization server the suite uses is test tooling (`test/support/mcp_dev_auth_server.ex`), not part of the library.

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Dreaming](https://muthuishere.github.io/toolnexus/dreaming/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | **[Elixir](https://muthuishere.github.io/toolnexus/api/elixir/)** |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).

## Agents via ACP (SPEC §8C)

toolnexus ships no per-agent client. Three vendor-neutral pieces compose instead:
`Toolnexus.Toolkit.as_mcp_server/2` serves the toolkit to a local agent
(`%{url, token, close}`, loopback + bearer), `Toolnexus.Acp.connect/2` drives any
ACP agent (`:mcp_servers` sent verbatim, `:session_meta`, `:client_caps_meta`,
`:model`), and `Toolnexus.Acp.generate_stream/1` plugs into
`Toolnexus.Client.create_in_process(generate_stream: …)` for incremental streaming.

```elixir
srv = Toolnexus.Toolkit.as_mcp_server(tk)
entry = %{"type" => "http", "name" => "toolnexus", "url" => srv.url,
          "headers" => [%{"name" => "Authorization", "value" => "Bearer " <> srv.token}]}
{:ok, acp} = Toolnexus.Acp.connect(["my-agent", "acp"], mcp_servers: [entry])
client = Toolnexus.Client.create_in_process(model: "acp", generate_stream: Toolnexus.Acp.generate_stream(acp))
```
